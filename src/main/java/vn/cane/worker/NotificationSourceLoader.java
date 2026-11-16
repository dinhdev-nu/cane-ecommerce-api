package vn.cane.worker;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vn.cane.admin.application.SettingsReader;
import vn.cane.orders.application.NotificationOrderReader;
import vn.cane.payments.application.ReceiptNotificationReader;
import vn.cane.support.transaction.Transactions;

@Component
@Profile("worker & !api")
public final class NotificationSourceLoader {
    private static final Map<String, Set<String>> KINDS = Map.ofEntries(
            Map.entry("order_placed", Set.of("order_placed")),
            Map.entry("owner_new_order", Set.of("order_placed")),
            Map.entry("payment_received", Set.of("payment_received", "payment_applied", "payment_resolved")),
            Map.entry("refunded", Set.of("payment_refunded")),
            Map.entry("shipped", Set.of("order_shipped")),
            Map.entry("ready_pickup", Set.of("order_ready_pickup")),
            Map.entry("completed", Set.of("order_completed")),
            Map.entry("cancelled", Set.of("order_cancelled")),
            Map.entry("delivery_failed_closed", Set.of("delivery_failed_closed")),
            Map.entry("link_resent", Set.of("link_resent", "link_rotated")),
            Map.entry("owner_payment_due", Set.of("owner_payment_due")),
            Map.entry(
                    "owner_alert",
                    Set.of(
                            "owner_alert",
                            "notification_exhausted",
                            "cancel_requested",
                            "payment_received",
                            "payment_applied",
                            "payment_resolved",
                            "payment_refunded")));
    private final Transactions tx;
    private final JdbcOutboxQueue queue;
    private final NotificationOrderReader orders;
    private final ReceiptNotificationReader receipts;
    private final SettingsReader settings;
    private final Clock clock;

    public NotificationSourceLoader(
            Transactions tx,
            JdbcOutboxQueue queue,
            NotificationOrderReader orders,
            ReceiptNotificationReader receipts,
            SettingsReader settings,
            Clock clock) {
        this.tx = tx;
        this.queue = queue;
        this.orders = orders;
        this.receipts = receipts;
        this.settings = settings;
        this.clock = clock;
    }

    public Source load(OutboxLease lease) {
        return tx.readSnapshot(() -> snapshot(lease));
    }

    private Source snapshot(OutboxLease lease) {
        var event = queue.event(lease.eventId());
        require(KINDS.getOrDefault(lease.kind(), Set.of()).contains(event.kind()));
        require(Objects.equals(lease.orderId(), event.orderId())
                && Objects.equals(lease.paymentId(), event.paymentId()));
        boolean owner = lease.kind().startsWith("owner_");
        require(Set.of("email", "telegram").contains(lease.channel())
                && (owner || lease.channel().equals("email")));
        var order = lease.orderId() == null ? null : orders.read(lease.orderId());
        var receipt = lease.paymentId() == null ? null : receipts.read(lease.paymentId());
        var facts = event.facts();
        if (facts.has("paymentStatus"))
            require(Set.of("unpaid", "paid", "refund_pending", "refunded")
                    .contains(facts.path("paymentStatus").asString("")));
        String status = event.toStatus() == null ? facts.path("status").asString("") : event.toStatus();
        if (!status.isEmpty())
            require(Set.of(
                            "awaiting_payment",
                            "confirmed",
                            "preparing",
                            "ready_for_pickup",
                            "shipping",
                            "completed",
                            "cancelled")
                    .contains(status));
        checkId(facts, "orderId", lease.orderId());
        checkId(facts, "paymentId", lease.paymentId());
        if (Set.of("payment_received", "refunded").contains(lease.kind())) {
            require(receipt != null
                    && facts.path("amountVnd").isIntegralNumber()
                    && facts.path("amountVnd")
                            .bigIntegerValue()
                            .equals(java.math.BigInteger.valueOf(receipt.amountVnd()))
                    && receipt.method().equals(facts.path("method").asString())
                    && facts.path("isPrincipal").isBoolean()
                    && facts.path("orderLinkVerified").isBoolean()
                    && facts.path("orderLinkVerified").asBoolean(false));
            require(facts.has("orderId") && facts.has("paymentId") && order != null);
            require(facts.has("reference"));
            if (receipt.method().equals("bank") || lease.kind().equals("refunded"))
                require(facts.path("reference").isString()
                        && !facts.path("reference").asString().isBlank());
            require(!lease.kind().equals("payment_received") || receipt.method().equals("bank"));
            if (lease.kind().equals("refunded"))
                require(facts.path("refundedAt").isString());
            if (event.kind().equals("payment_resolved"))
                require(facts.path("action").isString());
        }
        if (!owner) require(order != null);
        if (Set.of(
                        "order_placed",
                        "owner_new_order",
                        "shipped",
                        "ready_pickup",
                        "completed",
                        "cancelled",
                        "delivery_failed_closed")
                .contains(lease.kind())) {
            require(facts.path("paymentStatus").isString());
            require(event.toStatus() != null || facts.path("status").isString());
        }
        if (Set.of("cancelled", "delivery_failed_closed").contains(lease.kind()))
            require(Set.of("before_handover", "delivery_failed")
                    .contains(facts.path("cancelKind").asString("")));
        boolean skip = false;
        if (lease.kind().equals("owner_payment_due")) skip = !manualDue(order);
        if (lease.kind().equals("owner_alert")) skip = !alertNeeded(event, order, receipt);
        return new Source(event, order, receipt == null ? null : receipt.confirmedAt(), settings.read(), skip);
    }

    private boolean alertNeeded(
            JdbcOutboxQueue.Event event, NotificationOrderReader.Data order, ReceiptNotificationReader.Facts receipt) {
        if (event.kind().startsWith("payment_"))
            return receipt != null && (receipt.needsReview() || !receipt.verified());
        if (event.kind().equals("cancel_requested"))
            return order != null && order.order().cancelRequestedAt() != null;
        String type = event.kind().equals("notification_exhausted")
                ? "notification_exhausted"
                : event.facts().path("alertType").asString("");
        if (type.equals("notification_exhausted"))
            return queue.exhausted(uuid(event.facts().path("failedOutboxId").asString("")));
        if (type.equals("link_expired"))
            return order != null
                    && order.order().linkVersion()
                            == event.facts().path("linkVersion").asLong()
                    && !clock.instant().isBefore(order.order().linkExpiresAt());
        if (type.equals("payment_due")) return manualDue(order);
        if (type.equals("payment_review")) return receipt != null && receipt.needsReview();
        require(order != null);
        var row = order.order();
        return switch (type) {
            case "confirmed", "preparing", "ready_for_pickup" -> row.status().equals(type);
            case "cod_unpaid" ->
                row.fulfillment().equals("delivery")
                        && row.status().equals("completed")
                        && row.paymentStatus().equals("unpaid");
            case "delivery_issue" -> row.status().equals("shipping") && row.deliveryIssue();
            case "refund_pending" ->
                row.status().equals("cancelled") && row.paymentStatus().equals("refund_pending");
            default -> throw invalid();
        };
    }

    private boolean manualDue(NotificationOrderReader.Data order) {
        return order != null
                && order.order().status().equals("awaiting_payment")
                && "manual".equals(order.order().bankMode())
                && order.order().dueAt() != null
                && !clock.instant().isBefore(order.order().dueAt());
    }

    private static void checkId(JsonNode facts, String key, UUID expected) {
        if (facts.has(key))
            require(
                    expected == null
                            ? facts.get(key).isNull()
                            : expected.toString().equals(facts.get(key).asString()));
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw invalid();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Notification source invalid");
    }

    public record Source(
            JdbcOutboxQueue.Event event,
            NotificationOrderReader.Data order,
            Instant receiptConfirmedAt,
            Map<String, JsonNode> settings,
            boolean skip) {
        @Override
        public String toString() {
            return "NotificationSource[redacted]";
        }
    }
}
