package vn.cane.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.bootstrap.cli.OperationalDatabase;
import vn.cane.fixture.LinkTestKeys;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.orders.domain.InvalidOrderLinkException;
import vn.cane.orders.domain.OrderLinkState;
import vn.cane.orders.infrastructure.JdbcOrderLinkRepository;
import vn.cane.orders.infrastructure.security.HmacOrderLinkTokens;
import vn.cane.orders.infrastructure.security.LinkKeyRing;
import vn.cane.support.transaction.Transactions;

/** Exercises link components, not the future guest/Owner HTTP use cases. */
@Testcontainers
class OrderLinksIT {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-11-15T00:00:00.123Z");

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private final MutableClock clock = new MutableClock(NOW);
    private com.zaxxer.hikari.HikariDataSource source;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate owner;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private Transactions transactions;
    private JdbcOrderLinkRepository repository;
    private OrderLinkTokens tokens;
    private OrderLinkService service;
    private UUID orderId;

    @BeforeEach
    void prepare() throws Exception {
        String database = "cane_links_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        owner = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api,cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        source = OperationalDatabase.open(Map.of(
                "CANE_OPERATIONS_DB_URL",
                url,
                "CANE_OPERATIONS_DB_USER",
                "cane_api",
                "CANE_OPERATIONS_DB_PASSWORD",
                TestInfrastructure.API_PASSWORD));
        source.setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(TransactionConfiguration.class, Transactions.class);
        context.refresh();
        transactions = context.getBean(Transactions.class);
        repository = new JdbcOrderLinkRepository(named);
        tokens = new HmacOrderLinkTokens(LinkKeyRing.parse(LinkTestKeys.JSON, "fixture"));
        service = new OrderLinkService(tokens, repository, clock);
        orderId = UUID.randomUUID();
        insert(orderId, "CANELINK01");
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (source != null) source.close();
    }

    @Test
    void mintsFromCurrentSqlAndAuthorizesOnlyThatOrderWithoutPersistingToken() {
        UUID other = UUID.randomUUID();
        insert(other, "CANELINK02");
        String before = snapshot();
        String token = service.mintCurrent(orderId).value();
        assertThat(service.authorizeRead(token)).isEqualTo(new OrderLinkState(orderId, 1, EXPIRY));
        assertThat(service.authorizeRead(service.mintCurrent(other).value()).orderId())
                .isEqualTo(other);
        assertThat(snapshot()).isEqualTo(before).doesNotContain(token);
        assertNoHistory();
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "expiry", "missing"})
    void signedButRevokedChangedExpiryOrMissingOrderHasTheSameError(String fault) {
        String token = service.mintCurrent(orderId).value();
        if (fault.equals("version"))
            owner.update("UPDATE cane.orders SET link_version=link_version+1 WHERE id=?", orderId);
        if (fault.equals("expiry"))
            owner.update(
                    "UPDATE cane.orders SET link_expires_at=link_expires_at+interval '1 millisecond' WHERE id=?",
                    orderId);
        if (fault.equals("missing"))
            token = tokens.sign(new OrderLinkState(UUID.randomUUID(), 1, EXPIRY))
                    .value();
        String input = token;
        assertThatThrownBy(() -> service.authorizeRead(input))
                .isInstanceOf(InvalidOrderLinkException.class)
                .hasMessage("Order link is invalid");
        assertNoHistory();
    }

    @Test
    void comparesInstantsAcrossDatabaseTimezonesAndRejectsAtExactExpiry() {
        String token = service.mintCurrent(orderId).value();
        owner.update(
                "UPDATE cane.orders SET link_expires_at=TIMESTAMPTZ '2026-11-15 07:00:00.123+07' WHERE id=?", orderId);
        clock.set(EXPIRY.minusNanos(1));
        assertThat(service.authorizeRead(token).expiresAt()).isEqualTo(EXPIRY);
        clock.set(EXPIRY);
        assertThatThrownBy(() -> service.authorizeRead(token)).isInstanceOf(InvalidOrderLinkException.class);
        assertThatThrownBy(() -> service.mintCurrent(orderId)).isInstanceOf(InvalidOrderLinkException.class);
        clock.set(EXPIRY.plusMillis(1));
        assertThatThrownBy(() -> service.authorizeRead(token)).isInstanceOf(InvalidOrderLinkException.class);
        assertNoHistory();
    }

    @Test
    void storedSubmillisecondExpiryIsNotRoundedToGrantAValidToken() {
        String token = service.mintCurrent(orderId).value();
        owner.update(
                "UPDATE cane.orders SET link_expires_at=link_expires_at+interval '1 microsecond' WHERE id=?", orderId);
        assertThatThrownBy(() -> service.authorizeRead(token)).isInstanceOf(InvalidOrderLinkException.class);
        assertNoHistory();
    }

    @Test
    void malformedOrExpiredSignatureIsRejectedBeforeDatabaseAccess() {
        var unavailable = new OrderLinkRepository() {
            public java.util.Optional<OrderLinkState> find(UUID id) {
                throw new AssertionError("Invalid token must not query SQL");
            }

            public java.util.Optional<OrderLinkState> lock(UUID id) {
                throw new AssertionError("Invalid token must not lock SQL");
            }
        };
        var guarded = new OrderLinkService(tokens, unavailable, clock);
        assertThatThrownBy(() -> guarded.authorizeRead("private-invalid-token"))
                .isInstanceOf(InvalidOrderLinkException.class);
        String expired = tokens.sign(new OrderLinkState(orderId, 1, NOW)).value();
        assertThatThrownBy(() -> guarded.authorizeRead(expired)).isInstanceOf(InvalidOrderLinkException.class);
    }

    @Test
    void databaseFailureIsTechnicalNotAnInvalidLink() {
        String token = service.mintCurrent(orderId).value();
        owner.execute("REVOKE SELECT ON cane.orders FROM cane_api");
        assertThatThrownBy(() -> service.authorizeRead(token))
                .isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(InvalidOrderLinkException.class);
    }

    @Test
    void writeRequiresCallerTransactionAndRechecksExpiryAfterDatabaseRoundTrip() {
        String token = service.mintCurrent(orderId).value();
        assertThatThrownBy(() -> service.authorizeWrite(token)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.readSnapshot(() -> service.authorizeWrite(token)))
                .isInstanceOf(IllegalStateException.class);
        var elapsed = new JdbcOrderLinkRepository(named) {
            @Override
            public java.util.Optional<OrderLinkState> lock(UUID id) {
                var state = super.lock(id);
                clock.set(EXPIRY);
                return state;
            }
        };
        assertThatThrownBy(() ->
                        transactions.write(() -> new OrderLinkService(tokens, elapsed, clock).authorizeWrite(token)))
                .isInstanceOf(InvalidOrderLinkException.class);
        assertNoHistory();
    }

    @Test
    void concurrentRotationCommitsBeforeGuestLockAndGuestCannotWriteUsingAnEarlierRead() throws Exception {
        String token = service.mintCurrent(orderId).value();
        assertThat(service.authorizeRead(token).version()).isEqualTo(1);
        var rotated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var enteringLock = new CountDownLatch(1);
        var guestRepo = new JdbcOrderLinkRepository(named) {
            @Override
            public java.util.Optional<OrderLinkState> lock(UUID id) {
                enteringLock.countDown();
                return super.lock(id);
            }
        };
        var guest = new OrderLinkService(tokens, guestRepo, clock);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var rotate = executor.submit(() -> transactions.write(() -> {
                jdbc.update(
                        "UPDATE cane.orders SET link_version=link_version+1,link_expires_at=link_expires_at+interval '90 days' WHERE id=?",
                        orderId);
                rotated.countDown();
                await(release);
                return true;
            }));
            java.util.concurrent.Future<Boolean> attempt = null;
            try {
                assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();
                attempt = executor.submit(() -> transactions.write(() -> {
                    guest.authorizeWrite(token);
                    jdbc.update("UPDATE cane.orders SET cancel_request_note='fixture write' WHERE id=?", orderId);
                    return true;
                }));
                assertThat(enteringLock.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(rotate.get(10, TimeUnit.SECONDS)).isTrue();
            var pending = attempt;
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidOrderLinkException.class);
        }
        assertThat(owner.queryForObject(
                        "SELECT cancel_request_note FROM cane.orders WHERE id=?", String.class, orderId))
                .isNull();
        assertThatThrownBy(() -> service.authorizeRead(token)).isInstanceOf(InvalidOrderLinkException.class);
        assertThat(service.authorizeRead(service.mintCurrent(orderId).value()).version())
                .isEqualTo(2);
        assertNoHistory();
    }

    @Test
    void rotationRollbackLeavesOldLinkValidAndMintNeverRenewsIt() {
        String token = service.mintCurrent(orderId).value();
        assertThatThrownBy(() -> transactions.write(() -> {
                    jdbc.update(
                            "UPDATE cane.orders SET link_version=link_version+1,link_expires_at=link_expires_at+interval '90 days' WHERE id=?",
                            orderId);
                    throw new IllegalStateException("Controlled rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.authorizeRead(token)).isEqualTo(new OrderLinkState(orderId, 1, EXPIRY));
        assertThat(service.mintCurrent(orderId).value()).isEqualTo(token);
        assertNoHistory();
    }

    private void insert(UUID id, String number) {
        owner.update(
                """
            INSERT INTO cane.orders(id,order_number,status,payment_method,fulfillment_method,
              recipient_name,recipient_phone,recipient_email,address,subtotal_vnd,shipping_fee_vnd,
              created_at,link_expires_at,idempotency_key,request_hash)
            VALUES (?,?,'confirmed','cod','delivery','Fixture','0900000000','fixture@example.test',
              '{"wardCode":"TEST","line1":"Fixture"}',25000,15000,'2026-11-01T00:00:00Z',?,?,decode(repeat('01',32),'hex'))
            """,
                id,
                number,
                java.time.OffsetDateTime.ofInstant(EXPIRY, ZoneOffset.UTC),
                "fixture-link-order-" + id);
    }

    private String snapshot() {
        return owner.queryForObject("SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM cane.orders o", String.class);
    }

    private void assertNoHistory() {
        for (String table : java.util.List.of("business_events", "stock_movements", "notification_outbox"))
            assertThat(owner.queryForObject("SELECT count(*) FROM cane." + table, Integer.class))
                    .isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
