package vn.cane.orders.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.payments.application.MoneyCommands;
import vn.cane.payments.application.ReceiptService;
import vn.cane.payments.domain.MoneyOrder;
import vn.cane.payments.domain.MoneyPolicy;
import vn.cane.payments.domain.MoneyRejected;
import vn.cane.payments.domain.PaymentReceipt;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.IdempotentOperations.Operation;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.transaction.SqlFailures;
import vn.cane.support.transaction.TransactionAccess;

/** Orders owns the outer operation and order changes; receipt persistence/policy belongs to Payments. */
@Service
@Profile("api & !worker")
public final class OrderMoneyService {
    private final OrderMoneyRepository orders;
    private final ReceiptService receipts;
    private final StaffAuthorization authorization;
    private final IdempotentOperations operations;
    private final PaymentEvents events;
    private final JsonMapper json;

    public OrderMoneyService(
            OrderMoneyRepository orders,
            ReceiptService receipts,
            StaffAuthorization authorization,
            IdempotentOperations operations,
            PaymentEvents events,
            JsonMapper json) {
        this.orders = orders;
        this.receipts = receipts;
        this.authorization = authorization;
        this.operations = operations;
        this.events = events;
        this.json = json;
    }

    public OperationReceipt execute(StaffPrincipal principal, MoneyCommands.Command command) {
        // A moved link or bank UNIQUE race must roll back the entire A-TX before discovering/locking again.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return operations.execute(() -> authorize(principal, command), command.canonical(), operation -> {
                    Scope scope = lock(command);
                    switch (command) {
                        case MoneyCommands.Confirm confirm -> confirm(operation, confirm, scope);
                        case MoneyCommands.RecordReceipt record -> record(operation, record, scope);
                        case MoneyCommands.Resolve resolve -> resolve(operation, resolve, scope);
                        case MoneyCommands.Refund refund -> refund(operation, refund, scope);
                    }
                });
            } catch (RuntimeException failure) {
                if (!(failure instanceof LinkMoved)
                        && !SqlFailures.uniqueConstraint(failure)
                                .filter("payments_provider_ref_unique"::equals)
                                .isPresent()) throw failure;
                if (attempt == 2) throw new ReceiptContention();
            }
        }
        throw new IllegalStateException("Unreachable receipt operation");
    }

    /**
     * WP06 pickup handover joins its caller's A-TX, before any receipt/SKU locks.
     * The caller must transition to completed and append its single completed event/anchor/outbox
     * in that same transaction. This method emits no separate cash payment notification.
     */
    public PaymentReceipt collectAtPickup(StaffPrincipal principal, UUID orderId) {
        TransactionAccess.requireWrite();
        var actor = authorization.requireCurrent(principal);
        var order = orders.locked(java.util.List.of(orderId)).stream()
                .findFirst()
                .orElseThrow(() -> new MoneyRejected(MoneyRejected.Reason.NOT_FOUND));
        if (!order.fulfillmentMethod().equals("pickup") || !order.status().equals("ready_for_pickup"))
            throw new MoneyRejected(MoneyRejected.Reason.STATE, order.id(), order.status());
        var principals = receipts.principals(java.util.List.of(orderId));
        receipts.lock(principals.stream().map(PaymentReceipt::id).toList());
        var existing = principals.isEmpty()
                ? null
                : receipts.find(principals.getFirst().id()).orElseThrow();
        MoneyPolicy.invariant(order, existing);
        if (existing != null) return existing;
        var entry = new MoneyCommands.Entry("cash", order.totalVnd(), null, null);
        MoneyPolicy.apply(order, proposed(orderId, entry), null, false, false);
        var receipt = receipts.insert(orderId, entry, actor.id(), true, false);
        receipt = receipts.apply(receipt, orderId, actor.id(), false);
        orders.apply(order);
        return receipt;
    }

    private UUID authorize(StaffPrincipal principal, MoneyCommands.Command command) {
        if (command instanceof MoneyCommands.Confirm c)
            return (c.entry().method().equals("bank")
                            ? authorization.requireBankConfirmation(principal)
                            : authorization.requireCurrent(principal))
                    .id();
        return authorization.requireOwner(principal).id();
    }

    private Scope lock(MoneyCommands.Command command) {
        PaymentReceipt candidate;
        if (command.paymentId() != null)
            candidate = receipts.find(command.paymentId())
                    .orElseThrow(() -> new MoneyRejected(MoneyRejected.Reason.NOT_FOUND));
        else {
            var entry = command instanceof MoneyCommands.Confirm c
                    ? c.entry()
                    : ((MoneyCommands.RecordReceipt) command).entry();
            candidate = entry.bank() == null
                    ? null
                    : receipts.findBank(entry.bank().key()).orElse(null);
        }
        var orderIds = new HashSet<UUID>();
        if (command.orderId() != null) orderIds.add(command.orderId());
        if (candidate != null && candidate.orderId() != null) orderIds.add(candidate.orderId());
        var locked = orders.locked(orderIds);
        if (locked.size() != orderIds.size()) throw new MoneyRejected(MoneyRejected.Reason.NOT_FOUND);
        var orderMap = new HashMap<UUID, MoneyOrder>();
        locked.forEach(o -> orderMap.put(o.id(), o));
        var principalRows = receipts.principals(orderIds);
        var paymentIds = new HashSet<UUID>();
        principalRows.forEach(p -> paymentIds.add(p.id()));
        if (candidate != null) paymentIds.add(candidate.id());
        receipts.lock(paymentIds);
        if (candidate != null) {
            candidate =
                    receipts.find(candidate.id()).orElseThrow(() -> new IllegalStateException("Receipt disappeared"));
            if (candidate.orderId() != null && !orderIds.contains(candidate.orderId())) throw new LinkMoved();
        }
        var principals = new HashMap<UUID, PaymentReceipt>();
        for (var p : principalRows) {
            var current = receipts.find(p.id()).orElseThrow();
            if (!Objects.equals(current.orderId(), p.orderId()) || !current.appliedToOrder())
                throw new IllegalStateException("Principal changed under order lock");
            if (principals.put(current.orderId(), current) != null)
                throw new IllegalStateException("Duplicate principal");
        }
        for (var order : locked) MoneyPolicy.invariant(order, principals.get(order.id()));
        return new Scope(orderMap, principals, candidate);
    }

    private void confirm(Operation operation, MoneyCommands.Confirm command, Scope scope) {
        var order = scope.orders().get(command.orderId());
        if (!order.status().equals(command.expectedStatus()))
            throw new MoneyRejected(MoneyRejected.Reason.STATE, order.id(), order.status());
        MoneyPolicy.requireMethod(order, command.entry().method());
        var receipt = scope.receipt();
        if (receipt != null) {
            ReceiptService.sameTransaction(receipt, command.entry());
            if (receipt.orderId() != null && !receipt.orderId().equals(order.id())) throw MoneyRejected.conflict();
        }
        // Validate before INSERT, including wrong total/status/account/content, so confirm never records an exception
        // receipt.
        var proposed = receipt == null ? proposed(order.id(), command.entry()) : receipt;
        MoneyPolicy.apply(order, proposed, scope.principal(order), false, true);
        boolean existing = receipt != null;
        if (receipt == null) receipt = receipts.insert(order.id(), command.entry(), operation.actor(), true, false);
        receipt = receipts.apply(receipt, order.id(), operation.actor(), existing);
        orders.apply(order);
        var result = json.createObjectNode()
                .put("orderId", order.id().toString())
                .put("status", order.status().equals("awaiting_payment") ? "confirmed" : order.status())
                .put("paymentStatus", "paid")
                .put("paymentId", receipt.id().toString());
        events.record(
                operation,
                receipt,
                order,
                existing ? "payment_applied" : "payment_received",
                null,
                command.entry().note(),
                result);
    }

    private void record(Operation operation, MoneyCommands.RecordReceipt command, Scope scope) {
        var order = command.orderId() == null ? null : scope.orders().get(command.orderId());
        if (command.entry().method().equals("cash")) MoneyPolicy.requireMethod(order, "cash");
        var receipt = scope.receipt();
        boolean duplicate = receipt != null;
        if (duplicate) ReceiptService.sameTransaction(receipt, command.entry());
        else
            receipt = receipts.insert(
                    command.orderId(),
                    command.entry(),
                    operation.actor(),
                    command.entry().method().equals("cash"),
                    true);
        var result = receiptResult(receipt).put("method", receipt.method()).put("amountVnd", receipt.amountVnd());
        events.record(
                operation,
                receipt,
                order,
                duplicate ? "operation_noop" : "payment_received",
                null,
                duplicate ? null : command.entry().note(),
                result);
    }

    private void resolve(Operation operation, MoneyCommands.Resolve command, Scope scope) {
        var receipt = scope.receipt();
        if (!receipt.status().equals("succeeded") || receipt.appliedToOrder()) throw MoneyRejected.conflict();
        if (receipt.method().equals("cash") && !Objects.equals(receipt.orderId(), command.orderId()))
            throw MoneyRejected.conflict();
        var order = command.orderId() == null ? null : scope.orders().get(command.orderId());
        boolean apply = command.action().equals("apply_to_order");
        if (apply) {
            MoneyPolicy.apply(order, receipt, scope.principal(order), true, false);
            receipt = receipts.apply(receipt, order.id(), operation.actor(), true);
            orders.apply(order);
        } else receipt = receipts.review(receipt, command.orderId(), operation.actor());
        var result = receiptResult(receipt);
        nullable(
                result,
                "paymentStatus",
                order == null
                        ? null
                        : apply
                                ? order.status().equals("cancelled") ? "refund_pending" : "paid"
                                : order.paymentStatus());
        events.record(
                operation,
                receipt,
                order,
                apply ? "payment_applied" : "payment_resolved",
                command.action(),
                command.note(),
                result);
    }

    private void refund(Operation operation, MoneyCommands.Refund command, Scope scope) {
        var receipt = scope.receipt();
        var order = receipt.orderId() == null ? null : scope.orders().get(receipt.orderId());
        boolean duplicate = receipt.status().equals("refunded");
        if (duplicate) {
            if (!command.reference().equals(receipt.refundReference())) throw MoneyRejected.conflict();
        } else {
            MoneyPolicy.refund(order, receipt, order == null ? null : scope.principal(order));
            receipt = receipts.refund(receipt, command.reference());
            if (receipt.appliedToOrder()) orders.refunded(receipt.orderId());
        }
        var result = json.createObjectNode()
                .put("paymentId", receipt.id().toString())
                .put("status", receipt.status())
                .put("refundReference", receipt.refundReference())
                .put("refundedAt", receipt.refundedAt().toString());
        nullable(result, "orderId", receipt.orderId());
        nullable(
                result,
                "paymentStatus",
                order == null ? null : receipt.appliedToOrder() ? "refunded" : order.paymentStatus());
        events.record(operation, receipt, order, duplicate ? "operation_noop" : "payment_refunded", null, null, result);
    }

    private ObjectNode receiptResult(PaymentReceipt r) {
        var result = json.createObjectNode()
                .put("paymentId", r.id().toString())
                .put("status", r.status())
                .put("appliedToOrder", r.appliedToOrder())
                .put("orderLinkVerified", r.orderLinkVerified())
                .put("needsReview", r.needsReview());
        nullable(result, "orderId", r.orderId());
        return result;
    }

    private static PaymentReceipt proposed(UUID orderId, MoneyCommands.Entry entry) {
        return new PaymentReceipt(
                null,
                orderId,
                entry.method(),
                entry.amountVnd(),
                "succeeded",
                false,
                true,
                entry.bank(),
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static void nullable(ObjectNode result, String field, Object value) {
        if (value == null) result.putNull(field);
        else result.put(field, value.toString());
    }

    private record Scope(Map<UUID, MoneyOrder> orders, Map<UUID, PaymentReceipt> principals, PaymentReceipt receipt) {
        PaymentReceipt principal(MoneyOrder order) {
            return principals.get(order.id());
        }

        @Override
        public String toString() {
            return "LockedMoneyScope[redacted]";
        }
    }

    private static final class LinkMoved extends RuntimeException {}

    public static final class ReceiptContention extends RuntimeException {
        public ReceiptContention() {
            super("Receipt contention exceeded the retry limit");
        }
    }
}
