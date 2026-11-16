package vn.cane.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.admin.application.SettingsReader;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.orders.domain.OrderActionRejected;
import vn.cane.orders.domain.OrderLinkState;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.CanonicalOperation;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.Transactions;

@Service
@Profile("api & !worker")
public final class OrderLinkActions {
    private final Transactions tx;
    private final OrderLinkService links;
    private final OrderLinkRepository linkRows;
    private final GuestOrderRepository orders;
    private final SettingsReader settings;
    private final BusinessEventWriter events;
    private final OutboxWriter outbox;
    private final IdempotentOperations operations;
    private final StaffAuthorization authorization;
    private final Clock clock;
    private final JsonMapper json;

    public OrderLinkActions(
            Transactions tx,
            OrderLinkService links,
            OrderLinkRepository linkRows,
            GuestOrderRepository orders,
            SettingsReader settings,
            BusinessEventWriter events,
            OutboxWriter outbox,
            IdempotentOperations operations,
            StaffAuthorization authorization,
            Clock clock,
            JsonMapper json) {
        this.tx = tx;
        this.links = links;
        this.linkRows = linkRows;
        this.orders = orders;
        this.settings = settings;
        this.events = events;
        this.outbox = outbox;
        this.operations = operations;
        this.authorization = authorization;
        this.clock = clock;
        this.json = json;
    }

    public GuestOrderView read(String token) {
        return tx.readSnapshot(() -> {
            var link = links.authorizeRead(token);
            var row = orders.find(link.orderId()).orElseThrow();
            boolean awaiting = row.status().equals("awaiting_payment");
            var bank = awaiting && row.bank() != null
                    ? new GuestOrderView.Bank(
                            text(row.bank(), "bankCode"),
                            text(row.bank(), "bankName"),
                            text(row.bank(), "accountNo"),
                            text(row.bank(), "accountName"),
                            row.total(),
                            row.number())
                    : null;
            var order = new GuestOrderView.Order(
                    row.number(),
                    row.status(),
                    row.paymentStatus(),
                    row.fulfillment(),
                    orders.items(row.id()),
                    new GuestOrderView.Recipient(row.name(), maskPhone(row.phone())),
                    row.address() != null ? text(row.address(), "wardName") : text(row.store(), "address"),
                    new GuestOrderView.Totals(row.subtotal(), row.shippingFee(), row.total()),
                    row.deliveryIssue(),
                    row.cancelRequestedAt() != null,
                    row.cancelKind(),
                    row.bankMode(),
                    awaiting && "manual".equals(row.bankMode()) ? row.dueAt() : null,
                    awaiting && "auto".equals(row.bankMode()) ? row.dueAt() : null);
            var current = settings.read().get("support");
            var support = json.createObjectNode();
            for (String key : java.util.List.of("phone", "zaloUrl")) support.put(key, text(current, key));
            return new GuestOrderView(order, orders.timeline(row.id()), bank, support);
        });
    }

    public CancelResult requestCancel(String token, String reason) {
        return tx.write(() -> {
            var link = links.authorizeWrite(token);
            var row = orders.find(link.orderId()).orElseThrow();
            if (!java.util.Set.of("awaiting_payment", "confirmed", "preparing", "ready_for_pickup")
                    .contains(row.status())) throw new OrderActionRejected(false);
            if (row.cancelRequestedAt() != null) return new CancelResult(row.number(), true, row.cancelRequestedAt());
            Instant at = OrderLinkState.millisecondInstant(clock.instant());
            orders.requestCancel(row.id(), at, reason);
            UUID eventId = UUID.randomUUID();
            var facts = json.createObjectNode().put("orderId", row.id().toString());
            events.append(new BusinessEvent(
                    eventId,
                    row.id(),
                    null,
                    "cancel_requested",
                    null,
                    null,
                    "guest",
                    "Cancellation requested",
                    reason,
                    facts,
                    null));
            outbox.enqueue(eventId, ownerNotifications(row.id()));
            return new CancelResult(row.number(), true, at);
        });
    }

    public OperationReceipt resend(StaffPrincipal principal, UUID id, boolean rotate, String key) {
        var body = json.createObjectNode().put("rotate", rotate);
        var canonical = CanonicalOperation.adminPost("POST", "/api/v1/admin/orders/" + id + "/resend-link", body, key);
        return operations.execute(() -> authorization.requireOwner(principal).id(), canonical, operation -> {
            var old = linkRows.lock(id).orElseThrow(() -> new OrderActionRejected(true));
            var row = orders.find(id).orElseThrow();
            var current = old;
            if (rotate) {
                try {
                    long version = Math.addExact(old.version(), 1);
                    Instant expiry = OrderLinkState.millisecondInstant(clock.instant())
                            .plus(CheckoutSettings.from(settings.read()).linkDays(), ChronoUnit.DAYS);
                    expiry.toEpochMilli();
                    if (!expiry.isAfter(row.createdAt())) throw new ArithmeticException();
                    current = new OrderLinkState(id, version, expiry);
                } catch (ArithmeticException | java.time.DateTimeException invalid) {
                    throw new OrderActionRejected(false);
                }
                orders.rotate(current);
            } else if (!current.validAt(clock.instant())) throw new OrderActionRejected(false);
            UUID eventId = UUID.randomUUID();
            var result = json.createObjectNode()
                    .put("orderId", id.toString())
                    .put("linkVersion", current.version())
                    .put("linkExpiresAt", current.expiresAt().toString())
                    .put("eventId", eventId.toString())
                    .put("queued", true);
            var facts = json.createObjectNode()
                    .put("orderId", id.toString())
                    .put("linkVersion", current.version())
                    .put("linkExpiresAt", current.expiresAt().toString());
            events.append(
                    new BusinessEvent(
                            eventId,
                            id,
                            null,
                            rotate ? "link_rotated" : "link_resent",
                            null,
                            null,
                            operation.actor().toString(),
                            null,
                            null,
                            facts,
                            null),
                    operation.stamp(result));
            outbox.enqueue(
                    eventId,
                    java.util.List.of(new Notification(
                            UUID.randomUUID(),
                            Notification.Kind.LINK_RESENT,
                            id,
                            null,
                            Notification.Channel.EMAIL,
                            row.email(),
                            Notification.Audience.CUSTOMER)));
        });
    }

    private java.util.List<Notification> ownerNotifications(UUID id) {
        var notify = settings.read().get("owner_notify");
        var candidates = new ArrayList<Notification>();
        for (var channel : Notification.Channel.values()) {
            String recipient = text(notify, channel == Notification.Channel.EMAIL ? "email" : "telegramChatId");
            if (recipient != null && !recipient.isBlank())
                candidates.add(new Notification(
                        UUID.randomUUID(),
                        Notification.Kind.OWNER_ALERT,
                        id,
                        null,
                        channel,
                        recipient,
                        Notification.Audience.OWNER));
        }
        return candidates;
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        return node == null ? null : CheckoutSettings.text(node, field);
    }

    static String maskPhone(String phone) {
        return "•••• " + phone.substring(Math.max(0, phone.length() - 4));
    }

    public record CancelResult(String orderNumber, boolean cancelRequested, Instant requestedAt) {}
}
