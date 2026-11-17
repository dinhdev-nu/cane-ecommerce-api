package vn.cane.orders.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.catalog.application.OrderStockReturns;
import vn.cane.orders.domain.OrderActionRejected;
import vn.cane.payments.application.ReceiptService;
import vn.cane.payments.domain.MoneyOrder;
import vn.cane.payments.domain.MoneyPolicy;
import vn.cane.payments.domain.MoneyRejected;
import vn.cane.payments.domain.PaymentReceipt;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.idempotency.OperationReceipts;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.validation.InputValidationException;

/** Owns order lifecycle A-TX. Payments and Catalog retain their persistence and policy. */
@Service
@Profile("api & !worker")
public final class OrderLifecycleService {
    private final OrderMoneyRepository moneyOrders;
    private final OrderLifecycleRepository orders;
    private final ReceiptService receipts;
    private final OrderMoneyService money;
    private final OrderStockReturns stock;
    private final StaffAuthorization authorization;
    private final IdempotentOperations operations;
    private final OperationReceipts oldOperations;
    private final BusinessEventWriter events;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public OrderLifecycleService(
            OrderMoneyRepository moneyOrders,
            OrderLifecycleRepository orders,
            ReceiptService receipts,
            OrderMoneyService money,
            OrderStockReturns stock,
            StaffAuthorization authorization,
            IdempotentOperations operations,
            OperationReceipts oldOperations,
            BusinessEventWriter events,
            OutboxWriter outbox,
            JsonMapper json) {
        this.moneyOrders = moneyOrders;
        this.orders = orders;
        this.receipts = receipts;
        this.money = money;
        this.stock = stock;
        this.authorization = authorization;
        this.operations = operations;
        this.oldOperations = oldOperations;
        this.events = events;
        this.outbox = outbox;
        this.json = json;
    }

    public OperationReceipt execute(StaffPrincipal principal, OrderLifecycleCommand command) {
        return operations.execute(() -> authorize(principal, command), command.canonical(), operation -> {
            var rows = moneyOrders.locked(List.of(command.orderId()));
            if (rows.isEmpty()) throw new OrderActionRejected(true);
            var order = rows.getFirst();
            if (!order.status().equals(command.expectedStatus())) throw new OrderActionRejected(false);
            var action = command.action();
            PaymentReceipt receipt;
            if (action == OrderLifecycleCommand.Action.HAND_OVER) {
                requireFulfillment(order, "pickup");
                requireProgressMoney(order);
                boolean collect = order.paymentStatus().equals("unpaid");
                if (collect && (command.cashCollectedVnd() == null || command.cashCollectedVnd() != order.totalVnd()))
                    throw MoneyRejected.conflict();
                if (!collect && command.cashCollectedVnd() != null)
                    throw InputValidationException.invalid(
                            "cashCollectedVnd", "Đơn đã thu tiền, không nhập khoản thu lần nữa.");
                // This helper must run before any principal/SKU lock; it joins this operation's transaction.
                receipt = money.collectAtPickup(principal, order.id());
            } else {
                var principals = receipts.principals(List.of(order.id()));
                if (principals.size() > 1) throw new IllegalStateException("Multiple principal receipts");
                receipts.lock(principals.stream().map(PaymentReceipt::id).toList());
                receipt = principals.isEmpty()
                        ? null
                        : receipts.find(principals.getFirst().id()).orElseThrow();
                MoneyPolicy.invariant(order, receipt);
            }
            UUID eventId = UUID.randomUUID();
            String next = order.status(), paymentStatus = order.paymentStatus(), cancelKind = null, kind;
            String note = command.note();
            Notification.Kind notification = null;
            OrderStockReturns.Plan returns = null;
            List<OrderStockReturns.Item> returned = List.of();
            switch (action) {
                case PREPARE -> {
                    requireProgressMoney(order);
                    next = "preparing";
                    kind = "order_prepared";
                    orders.transition(order.id(), next);
                }
                case SHIP -> {
                    requireProgressMoney(order);
                    requireFulfillment(order, "delivery");
                    next = "shipping";
                    kind = "order_shipped";
                    notification = Notification.Kind.SHIPPED;
                    orders.transition(order.id(), next);
                }
                case READY -> {
                    requireProgressMoney(order);
                    requireFulfillment(order, "pickup");
                    next = "ready_for_pickup";
                    kind = "order_ready_pickup";
                    notification = Notification.Kind.READY_PICKUP;
                    orders.transition(order.id(), next);
                }
                case HAND_OVER -> {
                    next = "completed";
                    paymentStatus = "paid";
                    kind = "order_completed";
                    notification = Notification.Kind.COMPLETED;
                    orders.transition(order.id(), next);
                }
                case DELIVER -> {
                    requireProgressMoney(order);
                    requireFulfillment(order, "delivery");
                    next = "completed";
                    kind = "order_completed";
                    notification = Notification.Kind.COMPLETED;
                    orders.transition(order.id(), next);
                }
                case ISSUE -> {
                    requireFulfillment(order, "delivery");
                    kind = command.clear() ? "delivery_issue_cleared" : "delivery_issue_set";
                    note = command.reason();
                    orders.issue(order.id(), !command.clear(), command.reason());
                }
                case CANCEL, CLOSE_ISSUE -> {
                    boolean failed = action == OrderLifecycleCommand.Action.CLOSE_ISSUE;
                    if (failed) {
                        authorization.requireOwner(principal);
                        requireFulfillment(order, "delivery");
                        if (!orders.hasDeliveryIssue(order.id())) throw new OrderActionRejected(false);
                        returned = checkedReturns(command, orders.items(order.id()));
                    } else {
                        if (!Set.of("unpaid", "paid").contains(order.paymentStatus()))
                            throw new OrderActionRejected(false);
                        if (order.paymentStatus().equals("paid")) authorization.requireOwner(principal);
                        returned = orders.items(order.id()).stream()
                                .map(i -> new OrderStockReturns.Item(i.sku(), i.quantity(), command.reason()))
                                .toList();
                    }
                    returns = stock.prepare(order.id(), returned);
                    next = "cancelled";
                    cancelKind = failed ? "delivery_failed" : "before_handover";
                    paymentStatus = order.paymentStatus().equals("paid") ? "refund_pending" : order.paymentStatus();
                    kind = failed ? "delivery_failed_closed" : "order_cancelled";
                    notification = failed ? Notification.Kind.DELIVERY_FAILED_CLOSED : Notification.Kind.CANCELLED;
                    note = command.reason();
                    orders.cancel(order.id(), cancelKind, command.reason());
                }
                default -> throw new IllegalStateException("Unsupported lifecycle action");
            }
            var result = json.createObjectNode()
                    .put("orderId", order.id().toString())
                    .put("status", next)
                    .put("paymentStatus", paymentStatus)
                    .put("eventId", eventId.toString());
            var facts = json.createObjectNode()
                    .put("orderId", order.id().toString())
                    .put("status", next)
                    .put("paymentStatus", paymentStatus);
            boolean attachReceipt = action == OrderLifecycleCommand.Action.HAND_OVER || cancelKind != null;
            UUID paymentId = attachReceipt && receipt != null ? receipt.id() : null;
            if (action == OrderLifecycleCommand.Action.HAND_OVER) result.put("paymentId", paymentId.toString());
            if (paymentId != null) {
                facts.put("paymentId", paymentId.toString())
                        .put("amountVnd", receipt.amountVnd())
                        .put("method", receipt.method())
                        .put("isPrincipal", true)
                        .put("orderLinkVerified", true);
            }
            if (action == OrderLifecycleCommand.Action.ISSUE) {
                result.put("deliveryIssue", !command.clear());
                facts.put("deliveryIssue", !command.clear());
            }
            if (cancelKind != null) {
                result.put("cancelKind", cancelKind);
                facts.put("cancelKind", cancelKind);
            }
            if (action == OrderLifecycleCommand.Action.CLOSE_ISSUE) {
                var restock = json.createArrayNode();
                for (var item : command.restockItems())
                    restock.addObject()
                            .put("sku", item.sku())
                            .put("quantity", item.quantity())
                            .put("note", item.note());
                result.set("restockItems", restock);
                facts.set("restockItems", restock.deepCopy());
                facts.put("goodsResolved", true);
            }
            events.append(
                    new BusinessEvent(
                            eventId,
                            order.id(),
                            paymentId,
                            kind,
                            order.status(),
                            next,
                            operation.actor().toString(),
                            action == OrderLifecycleCommand.Action.ISSUE ? null : kind,
                            note,
                            facts,
                            null),
                    operation.stamp(result));
            if (returns != null)
                stock.apply(
                        returns,
                        eventId,
                        operation.actor(),
                        cancelKind.equals("delivery_failed") ? "delivery_return" : "cancel");
            if (notification != null)
                outbox.enqueue(
                        eventId,
                        List.of(new Notification(
                                UUID.randomUUID(),
                                notification,
                                order.id(),
                                paymentId,
                                Notification.Channel.EMAIL,
                                moneyOrders.customerEmail(order.id()),
                                Notification.Audience.CUSTOMER)));
        });
    }

    private UUID authorize(StaffPrincipal principal, OrderLifecycleCommand command) {
        var actor = authorization.requireCurrent(principal);
        if (command.action() == OrderLifecycleCommand.Action.CLOSE_ISSUE)
            return authorization.requireOwner(principal).id();
        if (command.action() == OrderLifecycleCommand.Action.CANCEL) {
            var prior = oldOperations.find(actor.id(), command.canonical().key());
            String status = orders.paymentStatus(command.orderId());
            boolean paid = status != null && !status.equals("unpaid");
            boolean wasPaid = prior.isPresent()
                    && prior.get()
                            .result()
                            .path("orderId")
                            .asString("")
                            .equals(command.orderId().toString())
                    && prior.get().result().path("cancelKind").asString("").equals("before_handover")
                    && !prior.get()
                            .result()
                            .path("paymentStatus")
                            .asString("unpaid")
                            .equals("unpaid");
            if (paid || wasPaid) return authorization.requireOwner(principal).id();
        }
        return actor.id();
    }

    private static void requireFulfillment(MoneyOrder order, String method) {
        if (!order.fulfillmentMethod().equals(method)) throw new OrderActionRejected(false);
    }

    private static void requireProgressMoney(MoneyOrder order) {
        if (!(order.paymentMethod().equals("cod") || order.paymentStatus().equals("paid")))
            throw new OrderActionRejected(false);
    }

    private static List<OrderStockReturns.Item> checkedReturns(
            OrderLifecycleCommand command, List<OrderLifecycleRepository.Purchased> items) {
        if (items.size() != command.restockItems().size())
            throw InputValidationException.invalid("restockItems", "Cần đúng một dòng cho từng SKU đã mua.");
        for (int i = 0; i < command.restockItems().size(); i++) {
            var item = command.restockItems().get(i);
            var purchased =
                    items.stream().filter(p -> p.sku().equals(item.sku())).findFirst();
            if (purchased.isEmpty() || item.quantity() > purchased.get().quantity())
                throw InputValidationException.invalid(
                        "restockItems[" + i + "].quantity", "Số lượng không khớp dòng đã mua.");
            if (item.quantity() < purchased.get().quantity() && item.note() == null)
                throw InputValidationException.invalid(
                        "restockItems[" + i + "].note", "Cần giải thích phần hàng không nhập lại.");
        }
        return command.restockItems().stream()
                .map(i -> new OrderStockReturns.Item(i.sku(), i.quantity(), i.note()))
                .toList();
    }
}
