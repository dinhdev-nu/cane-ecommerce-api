package vn.cane.orders.application;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.admin.application.SettingsReader;
import vn.cane.catalog.application.CheckoutCatalog;
import vn.cane.catalog.application.PricesInvalidation;
import vn.cane.checkout.application.CheckoutPolicy;
import vn.cane.checkout.application.CheckoutRejected;
import vn.cane.orders.application.OrderPlacementRepository.PlacedOrder;
import vn.cane.orders.domain.OrderLinkState;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.IdempotencyMismatchException;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.Notification.Audience;
import vn.cane.support.outbox.Notification.Channel;
import vn.cane.support.outbox.Notification.Kind;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.SqlFailures;
import vn.cane.support.transaction.Transactions;

@Service
public final class PlaceOrderService {
    private final OrderPlacementRepository orders;
    private final CheckoutCatalog catalog;
    private final SettingsReader settings;
    private final CheckoutPolicy policy;
    private final Transactions transactions;
    private final AdvisoryLocks locks;
    private final BusinessEventWriter events;
    private final OutboxWriter outbox;
    private final PricesInvalidation prices;
    private final JsonMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PlaceOrderService(
            OrderPlacementRepository orders,
            CheckoutCatalog catalog,
            SettingsReader settings,
            CheckoutPolicy policy,
            Transactions transactions,
            AdvisoryLocks locks,
            BusinessEventWriter events,
            OutboxWriter outbox,
            PricesInvalidation prices,
            JsonMapper json,
            Clock clock) {
        this.orders = orders;
        this.catalog = catalog;
        this.settings = settings;
        this.policy = policy;
        this.transactions = transactions;
        this.locks = locks;
        this.events = events;
        this.outbox = outbox;
        this.prices = prices;
        this.json = json;
        this.clock = clock;
    }

    public Result place(PlaceOrderCommand command) {
        var existing = orders.find(command.key());
        if (existing.isPresent()) return replay(command, existing.get());
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactions.write(() -> create(command));
            } catch (RuntimeException failure) {
                String constraint = SqlFailures.uniqueConstraint(failure).orElse("");
                if (constraint.equals("orders_idempotency_key_key"))
                    return replay(command, orders.find(command.key()).orElseThrow(() -> failure));
                if (!constraint.equals("orders_order_number_key") || attempt == 2) throw failure;
            }
        }
        throw new IllegalStateException("Order number allocation failed");
    }

    private Result create(PlaceOrderCommand command) {
        locks.acquire("place-order", null, command.key());
        var existing = orders.find(command.key());
        if (existing.isPresent()) return replay(command, existing.get());
        var config = CheckoutSettings.from(settings.read());
        var products = catalog.lock(command.checkout().skus());
        var quote = policy.evaluate(command.checkout(), config, products);
        if (!quote.canSubmit()) {
            String code = quote.fieldErrors().containsKey("shop")
                    ? "SHOP_NOT_ACCEPTING"
                    : quote.fieldErrors().containsKey("address.wardCode")
                            ? "DELIVERY_NOT_SUPPORTED"
                            : quote.itemErrors().stream()
                                    .map(i -> i.code())
                                    .filter(c -> c.equals("ITEM_UNAVAILABLE")
                                            || c.equals("INSUFFICIENT_STOCK")
                                            || c.equals("OUTSIDE_NEAR_AREA"))
                                    .findFirst()
                                    .orElse("INVALID_INPUT");
            var available = products.stream()
                    .filter(p -> command.checkout().items().stream()
                            .anyMatch(i -> i.sku().equals(p.sku()) && i.quantity() > p.stockQty()))
                    .map(p -> new CheckoutRejected.Availability(p.sku(), p.stockQty()))
                    .toList();
            throw new CheckoutRejected(code, quote, available);
        }
        if (quote.totalVnd() != command.expectedTotalVnd())
            throw new CheckoutRejected("QUOTE_CHANGED", quote, List.of());
        UUID id = UUID.randomUUID(), eventId = UUID.randomUUID();
        var order = orders.insert(
                id, number(), OrderLinkState.millisecondInstant(clock.instant()), command, config, quote, products);
        var facts = json.createObjectNode()
                .put("orderId", id.toString())
                .put("orderNumber", order.orderNumber())
                .put("amountVnd", order.totalVnd())
                .put("status", order.status())
                .put("paymentStatus", order.paymentStatus());
        events.append(new BusinessEvent(
                eventId,
                id,
                null,
                "order_placed",
                null,
                order.status(),
                "guest",
                "Order placed",
                null,
                facts,
                "order:" + id + ":placed"));
        catalog.purchase(
                id,
                eventId,
                command.checkout().items().stream()
                        .map(i -> new CheckoutCatalog.Purchase(i.sku(), i.quantity()))
                        .toList());
        var candidates = new ArrayList<Notification>();
        candidates.add(new Notification(
                UUID.randomUUID(),
                Kind.ORDER_PLACED,
                id,
                null,
                Channel.EMAIL,
                command.recipient().email(),
                Audience.CUSTOMER));
        for (var channel : Channel.values()) {
            String recipient =
                    CheckoutSettings.text(config.ownerNotify(), channel == Channel.EMAIL ? "email" : "telegramChatId");
            if (recipient != null && !recipient.isBlank())
                candidates.add(new Notification(
                        UUID.randomUUID(), Kind.OWNER_NEW_ORDER, id, null, channel, recipient, Audience.OWNER));
        }
        outbox.enqueue(eventId, candidates);
        prices.afterCommit();
        return new Result(order, false);
    }

    private Result replay(PlaceOrderCommand command, PlacedOrder order) {
        if (!MessageDigest.isEqual(command.requestHash(), order.requestHash()))
            throw new IdempotencyMismatchException();
        return new Result(order, true);
    }

    private String number() {
        var value = new StringBuilder("CANE");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 6; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.toString();
    }

    public record Result(PlacedOrder order, boolean replayed) {}
}
