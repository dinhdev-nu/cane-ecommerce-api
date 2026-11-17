package vn.cane.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.cane.admin.application.SessionCleanupService;
import vn.cane.admin.infrastructure.JdbcExpiredSessions;
import vn.cane.admin.infrastructure.JdbcSettingsReader;
import vn.cane.fixture.AdminHttpFixture;
import vn.cane.fixture.MutableClock;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.orders.application.NotificationOrderReader;
import vn.cane.orders.application.OrderLinkTokens;
import vn.cane.orders.infrastructure.JdbcGuestOrderRepository;
import vn.cane.orders.infrastructure.JdbcOrderLocks;
import vn.cane.orders.infrastructure.JdbcOrderReminderReader;
import vn.cane.payments.infrastructure.JdbcReceiptNotificationReader;
import vn.cane.payments.infrastructure.JdbcReceiptReminderReader;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputNormalizer;

/** New HTTP producers -> real worker-role readers/queue/rendering, with controlled reminder time. */
class ReminderWorkerIT extends AdminHttpFixture {
    private MutableClock clock;
    private ReminderService reminders;
    private Transactions workerTx;
    private NamedParameterJdbcTemplate worker;
    private JdbcReminderHistory history;
    private NotificationSourceLoader sources;
    private OutboxDispatchService dispatch;
    private NotificationRenderer renderer;

    @BeforeEach
    void worker() throws Exception {
        String url;
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            url = connection.getMetaData().getURL();
        }
        var ds = new DriverManagerDataSource(url, "cane_worker", TestInfrastructure.WORKER_PASSWORD);
        worker = new NamedParameterJdbcTemplate(ds);
        workerTx = new Transactions(new DataSourceTransactionManager(ds));
        clock = new MutableClock(Instant.now().plusSeconds(5));
        var settings = new JdbcSettingsReader(worker, JSON);
        var events = new BusinessEventWriter(worker, JSON);
        var outbox = new OutboxWriter(worker, JSON, context.getBean(InputNormalizer.class));
        var queue = new JdbcOutboxQueue(worker, JSON);
        var alerts = new NotificationAlerts(workerTx, new AdvisoryLocks(worker), queue, events, outbox, settings, JSON);
        dispatch = new OutboxDispatchService(workerTx, queue, alerts, clock);
        sources = new NotificationSourceLoader(
                workerTx,
                queue,
                new NotificationOrderReader(new JdbcGuestOrderRepository(worker, JSON)),
                new JdbcReceiptNotificationReader(worker),
                settings,
                clock);
        renderer = new NotificationRenderer(
                context.getBean(OrderLinkTokens.class), clock, "https://shop.example.invalid", "vi");
        history = new JdbcReminderHistory(worker);
        reminders = new ReminderService(
                workerTx,
                new JdbcOrderLocks(worker),
                new JdbcOrderReminderReader(worker),
                new JdbcReceiptReminderReader(worker),
                history,
                events,
                outbox,
                settings,
                JSON,
                clock);
        sql.update(
                "UPDATE cane.notification_outbox SET status='skipped',locked_at=NULL WHERE status IN ('pending','processing')");
    }

    @Test
    void manualReminderIsAtomicOwnerOnlyOnceAcrossWorkersAndKeepsTheOrderAndStock() throws Exception {
        var order = place("delivery", "bank_transfer");
        due(order);
        long stock = stock(order);
        sql.execute(
                "CREATE FUNCTION cane.wp06_fail_reminder() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.kind='owner_payment_due' THEN RAISE EXCEPTION 'MOCK queue failure'; END IF; RETURN NEW; END $$");
        sql.execute(
                "CREATE TRIGGER wp06_fail_reminder BEFORE INSERT ON cane.notification_outbox FOR EACH ROW EXECUTE FUNCTION cane.wp06_fail_reminder()");
        try {
            reminders.manualDue();
            assertThat(history.exists("order:" + order.id() + ":payment_due")).isFalse();
        } finally {
            sql.execute("DROP TRIGGER wp06_fail_reminder ON cane.notification_outbox");
            sql.execute("DROP FUNCTION cane.wp06_fail_reminder()");
        }
        race(reminders::manualDue);
        reminders.manualDue();
        assertThat(events(order, "owner_payment_due")).isEqualTo(1);
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.notification_outbox WHERE order_id=? AND kind='owner_payment_due'",
                        Long.class,
                        order.id()))
                .isEqualTo(1);
        assertThat(sql.queryForObject("SELECT status FROM cane.orders WHERE id=?", String.class, order.id()))
                .isEqualTo("awaiting_payment");
        assertThat(stock(order)).isEqualTo(stock);
        var notification = dispatch.claim().stream()
                .filter(l -> l.kind().equals("owner_payment_due") && l.orderId().equals(order.id()))
                .findFirst()
                .orElseThrow();
        assertThat(sources.load(notification).skip()).isFalse();
        assertThat(renderer.render(notification, sources.load(notification)).text())
                .contains(order.number());
        // Resolved after claim: dispatch rechecks current state and skips, without contacting a provider.
        ok(post(route(order, "cancel"), command("awaiting_payment").put("reason", "MOCK resolved"), key(), owner));
        assertThat(sources.load(notification).skip()).isTrue();
        dispatch.skipped(notification);
    }

    @Test
    void hourlyUsesRealStatusEventsAndCurrentThresholdWithoutChangingBusinessState() throws Exception {
        var confirmed = place("delivery", "cod");
        var preparing = place("delivery", "cod");
        step(preparing, "prepare", "confirmed");
        var ready = place("pickup", "cod");
        step(ready, "prepare", "confirmed");
        step(ready, "ready-for-pickup", "preparing");
        var completed = place("delivery", "cod");
        step(completed, "prepare", "confirmed");
        step(completed, "ship", "preparing");
        step(completed, "deliver", "shipping");
        var refund = place("delivery", "cod");
        ok(post(route(refund, "confirm-payment"), cash("confirmed", refund.total()), key(), owner));
        ok(post(route(refund, "cancel"), command("confirmed").put("reason", "MOCK refund pending"), key(), owner));
        clock.set(clock.instant().plusSeconds(25 * 3600));
        setting("stale_alert_hours", JSON.valueToTree(30));
        reminders.stale();
        assertThat(alerts(confirmed, "confirmed")).isZero();
        setting("stale_alert_hours", JSON.valueToTree(24));
        race(reminders::stale);
        for (var pair : List.of(
                Map.entry(confirmed, "confirmed"),
                Map.entry(preparing, "preparing"),
                Map.entry(ready, "ready_for_pickup"),
                Map.entry(completed, "cod_unpaid"),
                Map.entry(refund, "refund_pending")))
            assertThat(alerts(pair.getKey(), pair.getValue())).isEqualTo(1);
        reminders.stale();
        assertThat(alerts(completed, "cod_unpaid")).isEqualTo(1);
        assertThat(sql.queryForObject(
                        "SELECT payment_status FROM cane.orders WHERE id=?", String.class, completed.id()))
                .isEqualTo("unpaid");
        assertThat(sql.queryForObject("SELECT payment_status FROM cane.orders WHERE id=?", String.class, refund.id()))
                .isEqualTo("refund_pending");
    }

    @Test
    void editingIssueReasonDoesNotResetActiveAgeButClearingAndSettingDoes() throws Exception {
        var order = place("delivery", "cod");
        step(order, "prepare", "confirmed");
        step(order, "ship", "preparing");
        ok(post(
                route(order, "delivery-issue"),
                command("shipping").put("clear", false).put("reason", "MOCK initial"),
                key(),
                operator));
        oldEvent(order, "delivery_issue_set", clock.instant().minusSeconds(25 * 3600));
        ok(post(
                route(order, "delivery-issue"),
                command("shipping").put("clear", false).put("reason", "MOCK edited"),
                key(),
                operator));
        reminders.stale();
        assertThat(alerts(order, "delivery_issue")).isEqualTo(1);
        ok(post(route(order, "delivery-issue"), command("shipping").put("clear", true), key(), operator));
        ok(post(
                route(order, "delivery-issue"),
                command("shipping").put("clear", false).put("reason", "MOCK new incident"),
                key(),
                operator));
        var current = new JdbcOrderReminderReader(worker).current("delivery_issue", order.id());
        assertThat(current.since()).isAfter(clock.instant().minusSeconds(60));
        var lease = dispatch.claim().stream()
                .filter(l -> l.kind().equals("owner_alert") && l.orderId().equals(order.id()))
                .findFirst()
                .orElseThrow();
        ok(post(route(order, "delivery-issue"), command("shipping").put("clear", true), key(), operator));
        assertThat(sources.load(lease).skip()).isTrue();
    }

    @Test
    void recentInitialManualReminderBlocksNextDayUntilTwentyFourHoursEvenAfterDateChanges() throws Exception {
        var order = place("delivery", "bank_transfer");
        due(order);
        Instant first = Instant.parse("2026-11-16T16:30:00Z"); // 23:30 HCM
        clock.set(Instant.parse("2026-11-16T17:30:00Z")); // next local day, only one hour later
        sql.update(
                "UPDATE cane.orders SET created_at=?,payment_due_at=? WHERE id=?",
                at(first.minusSeconds(26 * 3600)),
                at(first.minusSeconds(25 * 3600)),
                order.id());
        UUID event = UUID.randomUUID();
        sql.update(
                "INSERT INTO cane.business_events(id,order_id,kind,actor,source_key,created_at) VALUES (?,?,'owner_payment_due','system',?,?)",
                event,
                order.id(),
                "order:" + order.id() + ":payment_due",
                at(first));
        sql.update(
                "INSERT INTO cane.notification_outbox(kind,order_id,event_id,channel,recipient) VALUES ('owner_payment_due',?,?,'email','owner@example.invalid')",
                order.id(),
                event);
        reminders.stale();
        assertThat(alerts(order, "payment_due")).isZero();
        clock.set(first.plusSeconds(86400));
        reminders.stale();
        assertThat(alerts(order, "payment_due")).isEqualTo(1);
        assertThat(history.exists("alert:payment_due:" + order.id() + ":2026-11-17"))
                .isTrue();
    }

    @Test
    void manualBatchIsBoundedAndContinuesAfterAlreadyRemindedRows() {
        reminders.manualDue(); // Finalize earlier scenarios before measuring this batch.
        String prefix = "wp06-manual-" + key();
        String bank = setting("bank_details").toString();
        for (int i = 0; i < 51; i++) {
            sql.update(
                    """
                INSERT INTO cane.orders(order_number,status,payment_status,fulfillment_method,payment_method,recipient_name,recipient_phone,recipient_email,
                  store_snapshot,subtotal_vnd,shipping_fee_vnd,bank_snapshot,bank_confirmation_mode,payment_due_at,created_at,link_expires_at,idempotency_key,request_hash)
                VALUES (?,'awaiting_payment','unpaid','pickup','bank_transfer','MOCK','0338825982','mock@example.invalid','{"name":"MOCK"}',10000,0,?::jsonb,'manual',?,?,now()+interval '7 days',?,decode(repeat('00',32),'hex'))
                """,
                    "CANEM" + String.format(java.util.Locale.ROOT, "%05d", i),
                    bank,
                    at(clock.instant().minusSeconds(7200 - i)),
                    at(clock.instant().minusSeconds(14400)),
                    prefix + "-" + i);
        }
        reminders.manualDue();
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events e JOIN cane.orders o ON o.id=e.order_id WHERE o.idempotency_key LIKE ? AND e.kind='owner_payment_due'",
                        Long.class,
                        prefix + "%"))
                .isEqualTo(50);
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events e JOIN cane.orders o ON o.id=e.order_id WHERE o.order_number='CANEM00050' AND e.kind='owner_payment_due'",
                        Long.class))
                .isZero();
        reminders.manualDue();
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events e JOIN cane.orders o ON o.id=e.order_id WHERE o.idempotency_key LIKE ? AND e.kind='owner_payment_due'",
                        Long.class,
                        prefix + "%"))
                .isEqualTo(51);
    }

    @Test
    void continuesBeyondOneHundredCandidatesAndAlreadyRemindedRowsDoNotStarveTheNextPage() {
        String prefix = "wp06-page-" + key();
        for (int i = 0; i < 105; i++) {
            UUID id = UUID.randomUUID();
            sql.update("""
                INSERT INTO cane.orders(id,order_number,status,payment_status,fulfillment_method,payment_method,recipient_name,recipient_phone,recipient_email,
                  store_snapshot,subtotal_vnd,shipping_fee_vnd,link_expires_at,idempotency_key,request_hash)
                VALUES (?,?,'confirmed','unpaid','pickup','cod','MOCK','0338825982','mock@example.invalid','{"name":"MOCK"}',10000,0,now()+interval '7 days',?,decode(repeat('00',32),'hex'))
                """, id, "CANEP" + String.format(java.util.Locale.ROOT, "%05d", i), prefix + "-" + i);
            sql.update(
                    "INSERT INTO cane.business_events(order_id,kind,to_status,actor,created_at) VALUES (?,'order_placed','confirmed','guest',?)",
                    id,
                    at(clock.instant().minusSeconds(25 * 3600)));
        }
        reminders.stale();
        reminders.stale();
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events e JOIN cane.orders o ON o.id=e.order_id WHERE o.idempotency_key LIKE ? AND e.kind='owner_alert'",
                        Long.class,
                        prefix + "%"))
                .isEqualTo(105);
    }

    @Test
    void reviewAndExhaustedKeepReceiptReferencesAndDoNotGenerateAlertRecursion() {
        UUID receipt = UUID.randomUUID();
        sql.update(
                "INSERT INTO cane.payments(id,method,amount_vnd,provider_ref,bank_receipt,confirmed_by,confirmed_at,needs_review) VALUES (?,'bank',10000,?,'{}','MOCK',?,true)",
                receipt,
                vn.cane.support.validation.BankReceiptKey.encode("MOCK", "000000", "MOCK-" + key()),
                at(clock.instant().minusSeconds(25 * 3600)));
        reminders.stale();
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events WHERE kind='owner_alert' AND payment_id=? AND data->>'alertType'='payment_review'",
                        Long.class,
                        receipt))
                .isEqualTo(1);
        var review = dispatch.claim().stream()
                .filter(l -> receipt.equals(l.paymentId()))
                .findFirst()
                .orElseThrow();
        assertThat(sources.load(review).skip()).isFalse();
        sql.update("UPDATE cane.payments SET needs_review=false WHERE id=?", receipt);
        assertThat(sources.load(review).skip()).isTrue();
        // Exhausting an alert about a receipt creates the immediate alternate-channel alert.
        sql.update("UPDATE cane.notification_outbox SET attempts=8 WHERE id=?", review.id());
        var last = new OutboxLease(
                review.id(),
                review.kind(),
                review.orderId(),
                review.paymentId(),
                review.eventId(),
                review.channel(),
                review.recipient(),
                8,
                review.lockedAt());
        dispatch.failed(last, "PROVIDER_FAILED");
        clock.set(clock.instant().plusSeconds(25 * 3600));
        reminders.stale();
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events WHERE kind='owner_alert' AND data->>'alertType'='notification_exhausted' AND data->>'failedOutboxId'=?",
                        Long.class,
                        review.id().toString()))
                .isEqualTo(1);
        sql.update(
                "UPDATE cane.notification_outbox SET status='skipped',locked_at=NULL WHERE status IN ('pending','processing') AND payment_id IS DISTINCT FROM ?",
                receipt);
        var repeated = dispatch.claim().stream()
                .filter(l -> l.kind().equals("owner_alert")
                        && receipt.equals(l.paymentId())
                        && !l.eventId().equals(review.eventId()))
                .toList();
        assertThat(repeated).isNotEmpty();
        for (var lease : repeated)
            assertThat(sources.load(lease)
                            .event()
                            .facts()
                            .path("failedOutboxId")
                            .asString())
                    .isEqualTo(review.id().toString());
        for (var lease : repeated) {
            sql.update("UPDATE cane.notification_outbox SET attempts=8 WHERE id=?", lease.id());
            dispatch.failed(
                    new OutboxLease(
                            lease.id(),
                            lease.kind(),
                            lease.orderId(),
                            lease.paymentId(),
                            lease.eventId(),
                            lease.channel(),
                            lease.recipient(),
                            8,
                            lease.lockedAt()),
                    "PROVIDER_FAILED");
        }
        clock.set(clock.instant().plusSeconds(25 * 3600));
        reminders.stale();
        for (var lease : repeated)
            assertThat(sql.queryForObject(
                            "SELECT count(*) FROM cane.business_events WHERE kind='owner_alert' AND data->>'alertType'='notification_exhausted' AND data->>'failedOutboxId'=?",
                            Long.class,
                            lease.id().toString()))
                    .isZero();
    }

    @Test
    void workerDeletesExpiredSessionsOnlyAndExpiryAlreadyRejectsHttpBeforeCleanup() throws Exception {
        var fresh = issue("operator");
        sql.update(
                "UPDATE cane.staff_sessions SET created_at=now()-interval '9 hours',expires_at=now()-interval '1 hour' WHERE staff_id=?",
                staff(operator));
        error(get("admin/me", operator), 401, "AUTH_REQUIRED");
        int before = sql.queryForObject(
                "SELECT count(*) FROM cane.staff_sessions WHERE expires_at<=?", Integer.class, at(clock.instant()));
        var cleanup = new SessionCleanupService(new JdbcExpiredSessions(worker), workerTx, clock);
        assertThat(cleanup.deleteExpired()).isEqualTo(before);
        assertThat(cleanup.deleteExpired()).isZero();
        ok(get("admin/me", fresh));
    }

    private void due(Order order) {
        sql.update(
                "UPDATE cane.orders SET payment_due_at=? WHERE id=?",
                at(clock.instant().minusSeconds(1)),
                order.id());
    }

    private long stock(Order order) {
        return sql.queryForObject("SELECT stock_qty FROM cane.products WHERE sku=?", Long.class, order.sku());
    }

    private long events(Order order, String kind) {
        return sql.queryForObject(
                "SELECT count(*) FROM cane.business_events WHERE order_id=? AND kind=?", Long.class, order.id(), kind);
    }

    private long alerts(Order order, String type) {
        return sql.queryForObject(
                "SELECT count(*) FROM cane.business_events WHERE order_id=? AND kind='owner_alert' AND data->>'alertType'=?",
                Long.class,
                order.id(),
                type);
    }

    private void oldEvent(Order order, String kind, Instant when) {
        sql.update(
                "INSERT INTO cane.business_events(order_id,kind,actor,created_at) VALUES (?,?,'system',?)",
                order.id(),
                kind,
                at(when));
    }

    private void step(Order order, String action, String expected) throws Exception {
        ok(post(route(order, action), command(expected), key(), operator));
    }

    private static String route(Order order, String action) {
        return "admin/orders/" + order.id() + "/" + action;
    }

    private static tools.jackson.databind.node.ObjectNode command(String expected) {
        return JSON.createObjectNode().put("expectedStatus", expected);
    }

    private static OffsetDateTime at(Instant time) {
        return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static void race(Runnable action) throws Exception {
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                go.await();
                action.run();
                return true;
            });
            var b = pool.submit(() -> {
                go.await();
                action.run();
                return true;
            });
            go.countDown();
            a.get(40, TimeUnit.SECONDS);
            b.get(40, TimeUnit.SECONDS);
        }
    }
}
