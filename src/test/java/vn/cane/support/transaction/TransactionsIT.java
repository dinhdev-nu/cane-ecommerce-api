package vn.cane.support.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.cane.admin.application.SettingsLocks;
import vn.cane.admin.application.SettingsValidator;
import vn.cane.admin.infrastructure.JdbcSettingsLocks;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.catalog.application.ProductLocks;
import vn.cane.catalog.infrastructure.JdbcProductLocks;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.orders.application.OrderLocks;
import vn.cane.orders.infrastructure.JdbcOrderLocks;
import vn.cane.payments.application.PaymentLocks;
import vn.cane.payments.infrastructure.JdbcPaymentLocks;

/** Mechanism harness only: no HTTP, provider calls or claims about future business guards. */
@Testcontainers
class TransactionsIT {
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<UUID> IDS = List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
    private static final List<String> SKUS = List.of("TX-A", "TX-B");

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private AnnotationConfigApplicationContext context;
    private HikariDataSource source;
    private JdbcTemplate owner;
    private JdbcTemplate jdbc;
    private Transactions transactions;
    private AdvisoryLocks advisory;
    private OrderLocks orders;
    private PaymentLocks payments;
    private ProductLocks products;
    private SettingsLocks settings;

    @BeforeEach
    void prepare() throws Exception {
        String database = "cane_tx_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        owner = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api, cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        seed();
        source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername("cane_api");
        source.setPassword(TestInfrastructure.API_PASSWORD);
        source.setMaximumPoolSize(5);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(3000);
        source.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        source.addDataSourceProperty(
                "options", "-c search_path=cane,pg_catalog -c statement_timeout=5000 -c lock_timeout=2000");
        source.addDataSourceProperty("connectTimeout", "3");
        source.addDataSourceProperty("socketTimeout", "6");
        jdbc = new JdbcTemplate(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(jdbc));
        context.register(
                TransactionConfiguration.class,
                Transactions.class,
                AdvisoryLocks.class,
                JdbcOrderLocks.class,
                JdbcPaymentLocks.class,
                JdbcProductLocks.class,
                JdbcSettingsLocks.class);
        context.refresh();
        transactions = context.getBean(Transactions.class);
        advisory = context.getBean(AdvisoryLocks.class);
        orders = context.getBean(OrderLocks.class);
        payments = context.getBean(PaymentLocks.class);
        products = context.getBean(ProductLocks.class);
        settings = context.getBean(SettingsLocks.class);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (source != null) source.close();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
    }

    @Test
    void nestedServicesShareOneConnectionAndReadCommittedTransaction() {
        transactions.write(() -> {
            Map<String, Object> first = jdbc.queryForMap(
                    "SELECT pg_backend_pid() AS pid, pg_current_xact_id()::text AS tx, current_setting('transaction_isolation') AS isolation");
            assertThat(first.get("isolation")).isEqualTo("read committed");
            transactions.write(() -> {
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class))
                        .isEqualTo(first.get("pid"));
                assertThat(jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class))
                        .isEqualTo(first.get("tx"));
                return jdbc.update("UPDATE cane.products SET stock_qty=1 WHERE sku=?", SKUS.getFirst());
            });
            assertThat(owner.queryForObject(
                            "SELECT stock_qty FROM cane.products WHERE sku=?", Integer.class, SKUS.getFirst()))
                    .isZero();
            return null;
        });
        assertThat(owner.queryForObject(
                        "SELECT stock_qty FROM cane.products WHERE sku=?", Integer.class, SKUS.getFirst()))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    void rollsBackStockReceiptEventAnchorLedgerAndOutboxAtEveryBoundary(int failAfter) {
        String before = state();
        var afterCommit = new AtomicInteger();
        assertThatThrownBy(() -> transactions.write(() -> {
                    transactions.afterCommit(afterCommit::incrementAndGet);
                    writeFixture(failAfter);
                    return null;
                }))
                .isInstanceOf(InjectedFailure.class);
        assertThat(afterCommit.get()).isZero();
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void multiSelectSnapshotRemainsConsistentAndIsEnforcedReadOnly() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transactions.readSnapshot(() -> {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                        .isEqualTo("repeatable read");
                assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class))
                        .isEqualTo("on");
                assertThat(jdbc.queryForObject("SELECT stock_qty FROM cane.products WHERE sku='TX-A'", Integer.class))
                        .isZero();
                try {
                    executor.submit(() -> owner.update("UPDATE cane.products SET stock_qty=2 WHERE sku='TX-A'"))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                assertThat(jdbc.queryForObject("SELECT sum(stock_qty) FROM cane.products", Integer.class))
                        .isZero();
                return null;
            });
        }
        assertThat(jdbc.queryForObject("SELECT stock_qty FROM cane.products WHERE sku='TX-A'", Integer.class))
                .isEqualTo(2);
        var failure = catchThrowable(() ->
                transactions.readSnapshot(() -> jdbc.update("UPDATE cane.products SET stock_qty=3 WHERE sku='TX-A'")));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(failure)).isEqualTo("25006");
        transactions.write(() -> {
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                    .isEqualTo("read committed");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class))
                    .isEqualTo("off");
            return null;
        });
    }

    @Test
    void preventsLockOrAfterCommitUsageOutsideAWriteTransaction() {
        List<Runnable> operations = List.of(
                () -> orders.lockIds(IDS),
                () -> payments.lockIds(IDS),
                () -> products.lockSkus(SKUS),
                () -> settings.lockAll(),
                () -> advisory.acquire("fixture", STAFF, "fixture-key-0001"),
                () -> transactions.afterCommit(() -> {}));
        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> transactions.readSnapshot(() -> {
                        operation.run();
                        return null;
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void concurrentCallersLockOrdersThenReceiptsThenSkusInDatabaseOrder() throws Exception {
        var start = new CyclicBarrier(2);
        var attempts = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (boolean reverse : List.of(false, true)) {
                futures.add(executor.submit(() -> transactions.write(() -> {
                    attempts.incrementAndGet();
                    advisory.acquire("ordered-locks", STAFF, "fixture-ordered-key-" + reverse);
                    await(start);
                    var ids = new ArrayList<>(IDS);
                    var skus = new ArrayList<>(SKUS);
                    if (reverse) {
                        Collections.reverse(ids);
                        Collections.reverse(skus);
                    }
                    assertThat(orders.lockIds(ids)).containsExactlyElementsOf(IDS);
                    assertThat(payments.lockIds(ids)).containsExactlyElementsOf(IDS);
                    assertThat(products.lockSkus(skus)).containsExactlyElementsOf(SKUS);
                    // Parameter values remain bound even when a SKU looks like SQL.
                    assertThat(products.lockSkus(List.of("' OR true --"))).isEmpty();
                    return jdbc.update("UPDATE cane.products SET stock_qty=stock_qty+1 WHERE sku='TX-A'");
                })));
            }
            for (var future : futures)
                assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT stock_qty FROM cane.products WHERE sku='TX-A'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void advisoryContentionIsNonBlockingAndSeparatedByActorAndNamespace() throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> transactions.write(() -> {
                advisory.acquire("fixture", STAFF, "fixture-key-0001");
                acquired.countDown();
                await(release);
                return 1;
            }));
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> transactions.write(() -> {
                            advisory.acquire("fixture", STAFF, "fixture-key-0001");
                            return null;
                        }))
                        .isInstanceOf(RequestInProgressException.class);
                transactions.write(() -> {
                    advisory.acquire("fixture", UUID.randomUUID(), "fixture-key-0001");
                    return null;
                });
                transactions.write(() -> {
                    advisory.acquire("another", STAFF, "fixture-key-0001");
                    return null;
                });
            } finally {
                release.countDown();
            }
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThatThrownBy(() -> transactions.write(() -> {
                    advisory.acquire("fixture", STAFF, "fixture-key-0001");
                    throw new InjectedFailure();
                }))
                .isInstanceOf(InjectedFailure.class);
        transactions.write(() -> {
            advisory.acquire("fixture", STAFF, "fixture-key-0001");
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"orders_idempotency_key_key", "payments_provider_ref_unique", "business_events_source_unique"})
    void uniqueFailureRollsBackBeforeWinnerReadAndKeepsConstraintIdentity(String constraint) {
        if (constraint.equals("payments_provider_ref_unique")) insertBank(owner);
        if (constraint.equals("business_events_source_unique"))
            owner.update(
                    "INSERT INTO cane.business_events(kind,actor,source_key) VALUES ('fixture','system','tx:source')");
        String before = state();
        var failure = catchThrowable(() -> transactions.write(() -> {
            jdbc.update("UPDATE cane.products SET stock_qty=1 WHERE sku='TX-A'");
            switch (constraint) {
                case "orders_idempotency_key_key" ->
                    insertOrder(jdbc, UUID.randomUUID(), "CANENEW001", "fixture-order-key-0001");
                case "payments_provider_ref_unique" -> insertBank(jdbc);
                case "business_events_source_unique" ->
                    jdbc.update(
                            "INSERT INTO cane.business_events(kind,actor,source_key) VALUES ('fixture','system','tx:source')");
                default -> throw new AssertionError();
            }
            return null;
        }));
        assertThat(SqlFailures.uniqueConstraint(failure)).contains(constraint);
        assertThat(SqlFailures.isDeadlock(failure)).isFalse();
        assertThat(state()).isEqualTo(before);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT id FROM cane.orders WHERE idempotency_key=?", UUID.class, "fixture-order-key-0001"))
                .isEqualTo(IDS.getFirst());
        if (!constraint.equals("orders_idempotency_key_key"))
            assertThat(SqlFailures.uniqueConstraint(failure))
                    .hasValueSatisfying(actual -> assertThat(actual).isNotEqualTo("orders_idempotency_key_key"));
    }

    @Test
    void realPostgresDeadlockRetriesTheWholeOperationInANewTransaction() throws Exception {
        // An external actor deliberately reverses lock order to exercise the recovery mechanism.
        owner.execute("CREATE TABLE cane.tx_deadlock_probe(id integer PRIMARY KEY, value integer NOT NULL)");
        owner.update("INSERT INTO cane.tx_deadlock_probe VALUES (1,0),(2,0)");
        owner.execute("GRANT SELECT,UPDATE ON cane.tx_deadlock_probe TO cane_api");
        var pauseCount = new AtomicInteger();
        var retrying = new Transactions(context.getBean(PlatformTransactionManager.class), () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            pauseCount.incrementAndGet();
        });
        var barrier = new CyclicBarrier(2);
        var transactionIds = ConcurrentHashMap.<String>newKeySet();
        var completed = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int first : List.of(1, 2)) {
                var attempts = new AtomicInteger();
                futures.add(executor.submit(() -> retrying.write(() -> {
                    advisory.acquire("deadlock-recovery", STAFF, "fixture-deadlock-key-" + first);
                    transactions.afterCommit(completed::incrementAndGet);
                    transactionIds.add(jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class));
                    jdbc.update("UPDATE cane.tx_deadlock_probe SET value=value+1 WHERE id=?", first);
                    jdbc.update(
                            "INSERT INTO cane.business_events(kind,actor,operation_key,request_hash,operation_result) VALUES ('operation_noop',?,?,decode(repeat('03',32),'hex'),'{}')",
                            STAFF.toString(),
                            "fixture-deadlock-key-" + first);
                    if (attempts.incrementAndGet() == 1) await(barrier);
                    return jdbc.update("UPDATE cane.tx_deadlock_probe SET value=value+1 WHERE id=?", 3 - first);
                })));
            }
            for (var future : futures)
                assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(pauseCount.get()).isEqualTo(1);
        assertThat(transactionIds).hasSize(3);
        assertThat(completed.get()).isEqualTo(2);
        assertThat(jdbc.queryForList(
                        "SELECT operation_key FROM cane.business_events ORDER BY operation_key", String.class))
                .containsExactly("fixture-deadlock-key-1", "fixture-deadlock-key-2");
        assertThat(jdbc.queryForList("SELECT value FROM cane.tx_deadlock_probe ORDER BY id", Integer.class))
                .containsExactly(2, 2);
    }

    @Test
    void caughtInnerFailureStillRollsBackTheOuterOperation() {
        String before = state();
        assertThatThrownBy(() -> transactions.write(() -> {
                    try {
                        transactions.write(() -> {
                            jdbc.update("UPDATE cane.products SET stock_qty=1 WHERE sku='TX-A'");
                            throw new InjectedFailure();
                        });
                    } catch (InjectedFailure expected) {
                        /* An inner rollback must never become a partial commit. */
                    }
                    jdbc.update("UPDATE cane.products SET stock_qty=2 WHERE sku='TX-B'");
                    return null;
                }))
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void postCommitFailureCannotTurnACommitIntoFailureOrRetryAndDoesNotLogItsInput() {
        var logger = (Logger) LoggerFactory.getLogger(Transactions.class);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        var calls = new AtomicInteger();
        try {
            String result = transactions.write(() -> {
                advisory.acquire("post-commit", STAFF, "fixture-key-0001");
                jdbc.update("UPDATE cane.products SET stock_qty=1 WHERE sku='TX-A'");
                transactions.afterCommit(() -> {
                    assertThat(owner.queryForObject(
                                    "SELECT pg_try_advisory_xact_lock(?)",
                                    Boolean.class,
                                    AdvisoryLocks.lockId("post-commit", STAFF, "fixture-key-0001")))
                            .isTrue();
                    calls.incrementAndGet();
                    throw new DataAccessResourceFailureException(
                            "PRIVATE-CACHE-COOKIE-SQL", new SQLException("PRIVATE-TOKEN", "40P01"));
                });
                assertThat(calls.get()).isZero();
                return "committed";
            });
            assertThat(result).isEqualTo("committed");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT stock_qty FROM cane.products WHERE sku='TX-A'", Integer.class))
                    .isEqualTo(1);
            assertThat(capture.list).hasSize(1);
            assertThat(capture.list.getFirst().getFormattedMessage()).isEqualTo("Post-commit action failed");
            assertThat(capture.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void settingsLockAllSixteenInTheirOwnTransactionAndRejectMissingRows() {
        var expected = SettingsValidator.KEYS.stream().sorted().toList();
        assertThat(transactions.write(settings::lockAll)).containsExactlyElementsOf(expected);
        owner.update("DELETE FROM cane.settings WHERE key='store'");
        assertThatThrownBy(() -> transactions.write(settings::lockAll)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aRawSpringPostCommitCallbackCannotCauseAnAlreadyCommittedTransactionToRetry() {
        var calls = new AtomicInteger();
        String result = transactions.write(() -> {
            calls.incrementAndGet();
            jdbc.update("UPDATE cane.products SET stock_qty=stock_qty+1 WHERE sku='TX-A'");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new DataAccessResourceFailureException(
                            "fixture callback", new SQLException("fixture", "40P01"));
                }
            });
            return "committed";
        });
        assertThat(result).isEqualTo("committed");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock_qty FROM cane.products WHERE sku='TX-A'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void cannotSilentlyJoinAnIncompatibleIsolationOrReadOnlyBoundary() {
        assertThatThrownBy(() -> transactions.write(() -> transactions.readSnapshot(() -> null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.readSnapshot(() -> transactions.write(() -> null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void seed() {
        owner.update(
                "INSERT INTO cane.staff_users(id,username,password_hash,role) VALUES (?,'tx-owner','fixture-hash','owner')",
                STAFF);
        for (String sku : SKUS)
            owner.update(
                    "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope) VALUES (?,'Fixture','unit',25000,'city')",
                    sku);
        for (int n = 0; n < IDS.size(); n++) {
            insertOrder(owner, IDS.get(n), "CANELOCK0" + (n + 1), "fixture-order-key-000" + (n + 1));
            owner.update(
                    "INSERT INTO cane.payments(id,order_id,method,amount_vnd,order_link_verified,confirmed_by) VALUES (?,?,'cash',40000,true,'fixture')",
                    IDS.get(n),
                    IDS.get(n));
        }
    }

    private static void insertOrder(JdbcTemplate writer, UUID id, String number, String key) {
        writer.update("""
                INSERT INTO cane.orders(id,order_number,status,payment_method,fulfillment_method,
                  recipient_name,recipient_phone,recipient_email,address,subtotal_vnd,shipping_fee_vnd,
                  link_expires_at,idempotency_key,request_hash)
                VALUES (?,?,'confirmed','cod','delivery','Fixture','0900000000','tx@example.invalid',
                  '{"wardCode":"TEST","line1":"Fixture"}',25000,15000,now()+interval '90 days',?,decode(repeat('01',32),'hex'))
                """, id, number, key);
    }

    private static void insertBank(JdbcTemplate writer) {
        writer.update("""
                INSERT INTO cane.payments(method,amount_vnd,provider_ref,bank_receipt,confirmed_by)
                VALUES ('bank',40000,'tx-bank-reference','{"bankCode":"TEST","accountNo":"TEST","transferContent":"fixture","transactionAt":"2026-11-10T00:00:00Z"}','fixture')
                """);
    }

    private void writeFixture(int failAfter) {
        UUID event = UUID.randomUUID();
        UUID receipt = UUID.randomUUID();
        jdbc.update("UPDATE cane.products SET stock_qty=stock_qty+1 WHERE sku='TX-A'");
        failAt(failAfter, 1);
        jdbc.update(
                "INSERT INTO cane.payments(id,order_id,method,amount_vnd,order_link_verified,confirmed_by) VALUES (?,?,'cash',40000,true,?)",
                receipt,
                IDS.getFirst(),
                STAFF.toString());
        failAt(failAfter, 2);
        jdbc.update(
                "INSERT INTO cane.business_events(id,kind,order_id,payment_id,actor) VALUES (?,'fixture',?,?,?)",
                event,
                IDS.getFirst(),
                receipt,
                STAFF.toString());
        failAt(failAfter, 3);
        jdbc.update(
                "INSERT INTO cane.business_events(kind,actor,operation_key,request_hash,operation_result) VALUES ('operation_noop',?,'fixture-operation-key',decode(repeat('02',32),'hex'),'{}')",
                STAFF.toString());
        failAt(failAfter, 4);
        jdbc.update(
                "INSERT INTO cane.stock_movements(sku,delta,reason,event_id,actor) VALUES ('TX-A',1,'restock',?,?)",
                event,
                STAFF.toString());
        failAt(failAfter, 5);
        jdbc.update(
                "INSERT INTO cane.notification_outbox(kind,order_id,payment_id,event_id,channel,recipient) VALUES ('owner_alert',?,?,?,'email','tx@example.invalid')",
                IDS.getFirst(),
                receipt,
                event);
        failAt(failAfter, 6);
        if (failAfter == 7) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new InjectedFailure();
                }
            });
        }
    }

    private String state() {
        return owner.queryForObject("""
                SELECT jsonb_build_object(
                  'products',(SELECT jsonb_agg(to_jsonb(t) ORDER BY sku) FROM cane.products t),
                  'orders',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.orders t),
                  'payments',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.payments t),
                  'events',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.business_events t),
                  'movements',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.stock_movements t),
                  'outbox',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.notification_outbox t))::text
                """, String.class);
    }

    private static void failAt(int requested, int step) {
        if (requested == step) throw new InjectedFailure();
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql) return sql.getSQLState();
        throw new AssertionError("Expected SQLException", failure);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class InjectedFailure extends RuntimeException {}
}
