package vn.cane.orders.application;

import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.admin.application.SettingsReader;
import vn.cane.payments.domain.MoneyOrder;
import vn.cane.payments.domain.PaymentReceipt;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.IdempotentOperations.Operation;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.OutboxWriter;

/** Select immutable facts and recipients together; never render or call a provider here. */
@Component
public final class PaymentEvents {
    private final BusinessEventWriter events;
    private final OutboxWriter outbox;
    private final SettingsReader settings;
    private final OrderMoneyRepository orders;
    private final JsonMapper json;

    public PaymentEvents(
            BusinessEventWriter events,
            OutboxWriter outbox,
            SettingsReader settings,
            OrderMoneyRepository orders,
            JsonMapper json) {
        this.events = events;
        this.outbox = outbox;
        this.settings = settings;
        this.orders = orders;
        this.json = json;
    }

    public void record(
            Operation operation,
            PaymentReceipt payment,
            MoneyOrder before,
            String kind,
            String action,
            String note,
            ObjectNode result) {
        UUID eventId = UUID.randomUUID();
        result.put("eventId", eventId.toString());
        var facts = json.createObjectNode();
        String label = null, source = null, from = null, to = null;
        if (!kind.equals("operation_noop")) {
            facts.put("paymentId", payment.id().toString())
                    .put("amountVnd", payment.amountVnd())
                    .put("method", payment.method())
                    .put("isPrincipal", payment.appliedToOrder())
                    .put("orderLinkVerified", payment.orderLinkVerified());
            if (payment.orderId() == null) facts.putNull("orderId");
            else facts.put("orderId", payment.orderId().toString());
            String reference = kind.equals("payment_refunded")
                    ? payment.refundReference()
                    : payment.bank() == null ? null : payment.bank().reference();
            if (reference == null) facts.putNull("reference");
            else facts.put("reference", reference);
            if (action != null) facts.put("action", action);
            if (payment.reviewedBy() != null)
                facts.put("reviewedBy", payment.reviewedBy())
                        .put("reviewedAt", payment.reviewedAt().toString());
            if (payment.refundedAt() != null)
                facts.put("refundedAt", payment.refundedAt().toString());
            if (before != null) {
                String status = result.has("paymentStatus")
                                && !result.get("paymentStatus").isNull()
                        ? result.get("paymentStatus").asString()
                        : before.paymentStatus();
                facts.put("paymentStatus", status);
                if (before.status().equals("awaiting_payment")
                        && payment.appliedToOrder()
                        && !kind.equals("payment_refunded")) {
                    from = before.status();
                    to = "confirmed";
                }
            }
            if (payment.orderLinkVerified())
                label = kind.equals("payment_refunded") ? "Refund recorded" : "Payment recorded";
            if (kind.equals("payment_refunded")) source = "payment:" + payment.id() + ":refunded";
            else if (kind.equals("payment_received")) source = "payment:" + payment.id() + ":received";
            else if (kind.equals("payment_applied")) source = "payment:" + payment.id() + ":applied";
        }
        events.append(
                new BusinessEvent(
                        eventId,
                        payment.orderId(),
                        payment.id(),
                        kind,
                        from,
                        to,
                        operation.actor().toString(),
                        label,
                        note,
                        facts,
                        source),
                operation.stamp(result));
        if (kind.equals("operation_noop")) return;
        var notifications = new ArrayList<Notification>();
        if (payment.orderLinkVerified()
                && (kind.equals("payment_refunded") || payment.method().equals("bank"))) {
            String customerEmail = orders.customerEmail(payment.orderId());
            if (customerEmail != null) {
                notifications.add(new Notification(
                        UUID.randomUUID(),
                        kind.equals("payment_refunded")
                                ? Notification.Kind.REFUNDED
                                : Notification.Kind.PAYMENT_RECEIVED,
                        payment.orderId(),
                        payment.id(),
                        Notification.Channel.EMAIL,
                        customerEmail,
                        Notification.Audience.CUSTOMER));
            }
        }
        if (payment.needsReview() || !payment.orderLinkVerified()) {
            var notify = settings.read().get("owner_notify");
            if (notify == null || !notify.isObject())
                throw new IllegalStateException("Owner notification configuration is invalid");
            for (var channel : Notification.Channel.values()) {
                var recipient = notify.get(channel == Notification.Channel.EMAIL ? "email" : "telegramChatId");
                if (recipient != null
                        && recipient.isString()
                        && !recipient.asString().isBlank())
                    notifications.add(new Notification(
                            UUID.randomUUID(),
                            Notification.Kind.OWNER_ALERT,
                            payment.orderId(),
                            payment.id(),
                            channel,
                            recipient.asString(),
                            Notification.Audience.OWNER));
            }
        }
        outbox.enqueue(eventId, notifications);
    }
}
