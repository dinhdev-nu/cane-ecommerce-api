package vn.cane.worker;

import static org.assertj.core.api.Assertions.*;

import jakarta.validation.Validation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.SettingsReader;
import vn.cane.fixture.MutableClock;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.orders.application.NotificationOrderReader;
import vn.cane.orders.infrastructure.JdbcGuestOrderRepository;
import vn.cane.payments.infrastructure.JdbcReceiptNotificationReader;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputNormalizer;

/** Real worker role, SQL queue locks/fences and atomic alert history. No reminder jobs or real provider. */
@Testcontainers
class NotificationWorkerIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JdbcTemplate owner;
    private static NamedParameterJdbcTemplate named;
    private static Transactions tx;
    private static JdbcOutboxQueue queue;
    private static NotificationAlerts alerts;
    private static SettingsReader settings;
    private static jakarta.validation.ValidatorFactory validation;
    private final MutableClock clock =
            new MutableClock(Instant.now().plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    private OutboxDispatchService service;

    @BeforeAll
    static void prepare() throws Exception {
        TestInfrastructure.prepareCore(POSTGRES);
        String url = POSTGRES.getJdbcUrl();
        owner = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        DataSource worker = new DriverManagerDataSource(url, "cane_worker", TestInfrastructure.WORKER_PASSWORD);
        named = new NamedParameterJdbcTemplate(worker);
        tx = new Transactions(new DataSourceTransactionManager(worker));
        queue = new JdbcOutboxQueue(named, JSON);
        settings = () -> {
            var values = new HashMap<String, tools.jackson.databind.JsonNode>();
            named.query("SELECT key,value::text FROM cane.settings", Map.of(), r -> {
                values.put(r.getString(1), JSON.readTree(r.getString(2)));
            });
            return values;
        };
        owner.update(
                "UPDATE cane.settings SET value=CAST(? AS jsonb) WHERE key='owner_notify'",
                "{\"email\":\"owner@example.invalid\",\"telegramChatId\":\"12345678\"}");
        validation = Validation.buildDefaultValidatorFactory();
        alerts = new NotificationAlerts(
                tx,
                new AdvisoryLocks(named),
                queue,
                new BusinessEventWriter(named, JSON),
                new OutboxWriter(named, JSON, new InputNormalizer(validation.getValidator())),
                settings,
                JSON);
        owner.update(
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope) VALUES ('worker-fixture','MOCK <item>','1 box',10000,100,true,'city')");
    }

    @AfterAll
    static void close() {
        if (validation != null) validation.close();
    }

    @BeforeEach
    void reset() {
        // Finalize mutable queue state from earlier cases without deleting append-only events.
        owner.update(
                "UPDATE cane.notification_outbox SET status='skipped',locked_at=NULL WHERE status IN ('pending','processing')");
        service = new OutboxDispatchService(tx, queue, alerts, clock);
    }

    @Test
    void twoWorkersClaimDisjointAttemptsAndNeverReopenFinalRows() throws Exception {
        UUID order = order();
        for (int i = 0; i < 24; i++) enqueue(order);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            Callable<List<OutboxLease>> task = () -> {
                ready.countDown();
                go.await();
                return service.claim();
            };
            var a = pool.submit(task);
            var b = pool.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertThat(first.size() + second.size()).isEqualTo(24);
            assertThat(first.stream().map(OutboxLease::id).toList())
                    .doesNotContainAnyElementsOf(
                            second.stream().map(OutboxLease::id).toList());
            first.forEach(service::sent);
            second.forEach(service::skipped);
            clock.set(clock.instant().plusSeconds(600));
            assertThat(service.claim()).isEmpty();
        }
    }

    @Test
    void reclaimedAttemptFencesEveryOldCompletionIncludingRetryAndExhaustedAlert() {
        enqueue(order());
        var old = service.claim().getFirst();
        clock.set(clock.instant().plusSeconds(301));
        var current = service.claim().getFirst();
        assertThat(current.attempts()).isEqualTo(2);
        service.sent(old);
        service.skipped(old);
        service.failed(old, "PROVIDER_FAILED");
        assertThat(status(current.id())).isEqualTo("processing");
        service.sent(current);
        assertThat(status(current.id())).isEqualTo("sent");
    }

    @Test
    void retryHasBoundedBackoffAndFinalFailureCreatesOneAlternateChannelAlertAtomically() {
        UUID id = enqueue(order());
        var lease = service.claim().getFirst();
        service.failed(lease, "PROVIDER_FAILED");
        var next = owner.queryForObject(
                        "SELECT next_attempt_at FROM cane.notification_outbox WHERE id=?", OffsetDateTime.class, id)
                .toInstant();
        assertThat(next)
                .isBetween(clock.instant().plusSeconds(30), clock.instant().plusSeconds(36));
        owner.update(
                "UPDATE cane.notification_outbox SET attempts=7,next_attempt_at=? WHERE id=?", at(clock.instant()), id);
        var last = service.claim().getFirst();
        assertThat(last.attempts()).isEqualTo(8);
        service.failed(last, "PROVIDER_TIMEOUT");
        assertThat(status(id)).isEqualTo("exhausted");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM cane.business_events WHERE source_key=?",
                        Long.class,
                        "outbox:" + id + ":exhausted"))
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM cane.notification_outbox WHERE event_id=(SELECT id FROM cane.business_events WHERE source_key=?) AND channel='telegram'",
                        Long.class,
                        "outbox:" + id + ":exhausted"))
                .isEqualTo(1);
        service.failed(last, "PROVIDER_FAILED");
        assertThat(status(id)).isEqualTo("exhausted");
    }

    @Test
    void crashedEighthAttemptBecomesExhaustedAndAlertOfAlertDoesNotRecurse() {
        UUID id = enqueue(order());
        owner.update("UPDATE cane.notification_outbox SET attempts=7 WHERE id=?", id);
        service.claim();
        clock.set(clock.instant().plusSeconds(301));
        assertThat(service.claim()).isEmpty();
        assertThat(status(id)).isEqualTo("exhausted");
        var alert = service.claim().getFirst();
        owner.update("UPDATE cane.notification_outbox SET attempts=8 WHERE id=?", alert.id());
        var last = new OutboxLease(
                alert.id(),
                alert.kind(),
                alert.orderId(),
                alert.paymentId(),
                alert.eventId(),
                alert.channel(),
                alert.recipient(),
                8,
                alert.lockedAt());
        service.failed(last, "PROVIDER_FAILED");
        UUID event = owner.queryForObject(
                "SELECT id FROM cane.business_events WHERE source_key=?",
                UUID.class,
                "outbox:" + alert.id() + ":exhausted");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM cane.notification_outbox WHERE event_id=?", Long.class, event))
                .isZero();
    }

    @Test
    void expiredVersionAlertsAreDeduplicatedAndResolvedConditionsAreSkipped() throws Exception {
        UUID id = order();
        alerts.expired(id, 1);
        alerts.expired(id, 1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM cane.business_events WHERE source_key=?",
                        Long.class,
                        "order:" + id + ":link_expired:1"))
                .isEqualTo(1);
        var loader = loader();
        for (var lease : service.claim()) assertThat(loader.load(lease).skip()).isTrue(); // Link is still valid.
        UUID event = UUID.randomUUID();
        owner.update(
                "INSERT INTO cane.business_events(id,order_id,kind,actor,data) VALUES (?,?,'owner_alert','system','{\"alertType\":\"refund_pending\"}')",
                event,
                id);
        UUID out = UUID.randomUUID();
        owner.update(
                "INSERT INTO cane.notification_outbox(id,kind,order_id,event_id,channel,recipient,next_attempt_at) VALUES (?,'owner_alert',?,?,'email','owner@example.invalid',?)",
                out,
                id,
                event,
                at(clock.instant()));
        assertThat(loader.load(service.claim().getFirst()).skip()).isTrue();
    }

    @Test
    void sourceIsHistoricalAndNeverSubstitutesAReceiptAfterRelink() {
        Instant confirmedAt = clock.instant().minusSeconds(3600);
        UUID original = order(),
                other = order(),
                receipt = UUID.randomUUID(),
                event = UUID.randomUUID(),
                out = UUID.randomUUID();
        owner.update(
                "INSERT INTO cane.payments(id,order_id,method,amount_vnd,provider_ref,bank_receipt,confirmed_by,order_link_verified,confirmed_at) VALUES (?,?,'bank',10000,?,'{}','system',true,?)",
                receipt,
                other,
                vn.cane.support.validation.BankReceiptKey.encode("ICB", "0338825982", "MOCK reference"),
                at(confirmedAt));
        String facts = JSON.createObjectNode()
                .put("orderId", original.toString())
                .put("paymentId", receipt.toString())
                .put("amountVnd", 10000)
                .put("method", "bank")
                .put("reference", "MOCK reference")
                .put("isPrincipal", false)
                .put("orderLinkVerified", true)
                .toString();
        owner.update(
                "INSERT INTO cane.business_events(id,order_id,payment_id,kind,actor,data) VALUES (?,?,?,'payment_received','system',CAST(? AS jsonb))",
                event,
                original,
                receipt,
                facts);
        owner.update(
                "INSERT INTO cane.notification_outbox(id,kind,order_id,payment_id,event_id,channel,recipient,next_attempt_at) VALUES (?,'payment_received',?,?,?,'email','customer@example.invalid',?)",
                out,
                original,
                receipt,
                event,
                at(clock.instant()));
        var lease = service.claim().getFirst();
        var source = loader().load(lease);
        assertThat(source.order().order().id()).isEqualTo(original);
        assertThat(source.receiptConfirmedAt()).isEqualTo(confirmedAt);
        owner.update("UPDATE cane.notification_outbox SET attempts=8 WHERE id=?", out);
        var last = new OutboxLease(
                lease.id(),
                lease.kind(),
                lease.orderId(),
                lease.paymentId(),
                lease.eventId(),
                lease.channel(),
                lease.recipient(),
                8,
                lease.lockedAt());
        service.failed(last, "PROVIDER_FAILED");
        assertThat(status(out)).isEqualTo("exhausted"); // Audit accepts the frozen source.
    }

    private NotificationSourceLoader loader() {
        return new NotificationSourceLoader(
                tx,
                queue,
                new NotificationOrderReader(new JdbcGuestOrderRepository(named, JSON)),
                new JdbcReceiptNotificationReader(named),
                settings,
                clock);
    }

    @Test
    void inconsistentEventKindIsABoundedSourceFailureWithoutSubstitutingAnotherEvent() {
        UUID id = enqueue(order());
        owner.update("UPDATE cane.notification_outbox SET kind='shipped' WHERE id=?", id);
        var lease = service.claim().getFirst();
        assertThatThrownBy(() -> loader().load(lease)).isInstanceOf(IllegalStateException.class);
        service.failed(lease, "SOURCE_INVALID");
        assertThat(status(id)).isEqualTo("pending");
        assertThat(owner.queryForObject("SELECT last_error FROM cane.notification_outbox WHERE id=?", String.class, id))
                .isEqualTo("SOURCE_INVALID");
    }

    private UUID order() {
        UUID id = UUID.randomUUID();
        owner.update(
                """
            INSERT INTO cane.orders(id,order_number,status,payment_status,fulfillment_method,payment_method,recipient_name,recipient_phone,recipient_email,
              store_snapshot,subtotal_vnd,shipping_fee_vnd,link_expires_at,idempotency_key,request_hash,created_at)
            VALUES (?,?,'confirmed','unpaid','pickup','cod','MOCK TEST','0338825982','customer@example.invalid','{"name":"MOCK shop","address":"MOCK area"}',10000,0,?,?,decode(repeat('00',32),'hex'),?)
            """,
                id,
                "CANE" + id.toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT),
                at(clock.instant().plusSeconds(86400)),
                UUID.randomUUID().toString(),
                at(clock.instant().minusSeconds(100)));
        owner.update(
                "INSERT INTO cane.order_items(order_id,sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,delivery_scope_snapshot) VALUES (?,'worker-fixture','MOCK <item>','1 box',10000,1,'city')",
                id);
        return id;
    }

    private UUID enqueue(UUID order) {
        UUID event = UUID.randomUUID(), id = UUID.randomUUID();
        owner.update(
                "INSERT INTO cane.business_events(id,order_id,kind,to_status,actor,data) VALUES (?,?,'order_placed','confirmed','guest','{\"status\":\"confirmed\",\"paymentStatus\":\"unpaid\"}')",
                event,
                order);
        owner.update(
                "INSERT INTO cane.notification_outbox(id,kind,order_id,event_id,channel,recipient,next_attempt_at) VALUES (?,'order_placed',?,?,'email','customer@example.invalid',?)",
                id,
                order,
                event,
                at(clock.instant()));
        return id;
    }

    private String status(UUID id) {
        return owner.queryForObject("SELECT status FROM cane.notification_outbox WHERE id=?", String.class, id);
    }

    private static OffsetDateTime at(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
