package vn.cane.worker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.admin.application.SettingsReader;
import vn.cane.orders.application.OrderLocks;
import vn.cane.orders.application.OrderReminderReader;
import vn.cane.payments.application.ReceiptReminderReader;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.audit.ReminderTarget;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.Transactions;

/** Produces reminders only. Every object has its own transaction; no provider IO or business updates. */
@Service
@Profile("worker & !api")
public final class ReminderService {
    private static final List<String> ORDER_TYPES = List.of(
            "payment_due",
            "confirmed",
            "preparing",
            "ready_for_pickup",
            "cod_unpaid",
            "delivery_issue",
            "refund_pending");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final Transactions tx;
    private final OrderLocks orderLocks;
    private final OrderReminderReader orders;
    private final ReceiptReminderReader receipts;
    private final JdbcReminderHistory history;
    private final BusinessEventWriter events;
    private final OutboxWriter outbox;
    private final SettingsReader settings;
    private final JsonMapper json;
    private final Clock clock;

    public ReminderService(
            Transactions tx,
            OrderLocks orderLocks,
            OrderReminderReader orders,
            ReceiptReminderReader receipts,
            JdbcReminderHistory history,
            BusinessEventWriter events,
            OutboxWriter outbox,
            SettingsReader settings,
            JsonMapper json,
            Clock clock) {
        this.tx = tx;
        this.orderLocks = orderLocks;
        this.orders = orders;
        this.receipts = receipts;
        this.history = history;
        this.events = events;
        this.outbox = outbox;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    public void manualDue() {
        // Already-reminded rows are excluded before LIMIT so they cannot starve later due orders.
        for (UUID id : orders.manualDue(clock.instant()))
            isolated(() -> tx.write(() -> {
                orderLocks.lockIds(List.of(id));
                var target = orders.current("payment_due", id);
                String source = "order:" + id + ":payment_due";
                Instant now = clock.instant();
                if (target != null
                        && target.since() != null
                        && !now.isBefore(target.since())
                        && !history.exists(source)) emit("payment_due", target, source, now, true);
                return null;
            }));
    }

    public void stale() {
        // Fixed upper bounds prevent continuous inserts from extending this run indefinitely.
        UUID orderUpper = orders.upperId();
        for (String type : ORDER_TYPES)
            scan(
                    orderUpper,
                    (after, through) -> orders.page(type, after, through),
                    candidate -> tx.write(() -> {
                        orderLocks.lockIds(List.of(candidate.id()));
                        var current = orders.current(type, candidate.id());
                        if (!type.equals("payment_due") || history.exists("order:" + candidate.id() + ":payment_due"))
                            repeat(type, current);
                        return null;
                    }));
        scan(
                receipts.upperId(),
                receipts::page,
                candidate -> tx.write(() -> {
                    if (candidate.orderId() != null) orderLocks.lockIds(List.of(candidate.orderId()));
                    var current = receipts.lock(candidate.id());
                    // A concurrent relink is retried on the next run; never acquire an order lock after a receipt.
                    if (current != null && Objects.equals(candidate.orderId(), current.orderId()))
                        repeat("payment_review", current);
                    return null;
                }));
        scan(
                history.upperOutboxId(),
                history::exhaustedPage,
                candidate -> tx.write(() -> {
                    // Only this outbox row is locked. Do not lock back up to its historical order/payment.
                    repeat("notification_exhausted", history.lockExhausted(candidate.id()));
                    return null;
                }));
    }

    private void repeat(String type, ReminderTarget target) {
        if (target == null || target.since() == null) return;
        Instant now = clock.instant();
        var hours = settings.read().get("stale_alert_hours");
        if (hours == null || !hours.isIntegralNumber() || hours.asLong() < 1)
            throw new IllegalStateException("Invalid stale threshold");
        if (target.since().plusSeconds(Math.multiplyExact(hours.asLong(), 3600)).isAfter(now)) return;
        String source = "alert:" + type + ":" + target.id() + ":"
                + now.atZone(BUSINESS_ZONE).toLocalDate();
        if (!history.exists(source) && !history.queuedRecently(type, target.id(), now))
            emit(type, target, source, now, false);
    }

    private void emit(String type, ReminderTarget target, String source, Instant now, boolean initial) {
        UUID event = UUID.randomUUID();
        var facts = json.createObjectNode()
                .put("alertType", type)
                .put("objectId", target.id().toString())
                .put("date", now.atZone(BUSINESS_ZONE).toLocalDate().toString());
        if (type.equals("notification_exhausted"))
            facts.put("failedOutboxId", target.id().toString());
        events.append(new BusinessEvent(
                event,
                target.orderId(),
                target.paymentId(),
                initial ? "owner_payment_due" : "owner_alert",
                null,
                null,
                "system",
                null,
                null,
                facts,
                source));
        var notify = settings.read().get("owner_notify");
        var candidates = new ArrayList<Notification>();
        for (var channel : Notification.Channel.values()) {
            String recipient =
                    CheckoutSettings.text(notify, channel == Notification.Channel.EMAIL ? "email" : "telegramChatId");
            if (recipient != null && !recipient.isBlank())
                candidates.add(new Notification(
                        UUID.randomUUID(),
                        initial ? Notification.Kind.OWNER_PAYMENT_DUE : Notification.Kind.OWNER_ALERT,
                        target.orderId(),
                        target.paymentId(),
                        channel,
                        recipient,
                        Notification.Audience.OWNER));
        }
        outbox.enqueue(event, candidates);
    }

    private static void scan(
            UUID through, BiFunction<UUID, UUID, List<ReminderTarget>> page, Consumer<ReminderTarget> action) {
        if (through == null) return;
        UUID after = null;
        while (true) {
            var rows = page.apply(after, through);
            if (rows.isEmpty()) return;
            for (var row : rows) isolated(() -> action.accept(row));
            after = rows.getLast().id();
            if (rows.size() < 100) return;
        }
    }

    private static void isolated(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String location = java.util.Arrays.stream(failure.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("vn.cane."))
                    .findFirst()
                    .map(StackTraceElement::toString)
                    .orElse("unknown");
            // JDBC exception messages can contain row data; log only safe diagnostic identifiers.
            org.slf4j.LoggerFactory.getLogger(ReminderService.class)
                    .atWarn()
                    .addKeyValue("failureType", cause.getClass().getSimpleName())
                    .addKeyValue("location", location)
                    .addKeyValue("sqlState", cause instanceof java.sql.SQLException sql ? sql.getSQLState() : "none")
                    .log("Reminder object unavailable");
        }
    }
}
