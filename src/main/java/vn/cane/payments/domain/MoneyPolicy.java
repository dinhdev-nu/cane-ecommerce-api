package vn.cane.payments.domain;

import java.util.Objects;
import java.util.Set;

public final class MoneyPolicy {
    private static final Set<String> CASH_STATES =
            Set.of("confirmed", "preparing", "ready_for_pickup", "shipping", "completed");

    private MoneyPolicy() {}

    public static void requireMethod(MoneyOrder order, String method) {
        if (!(method.equals("cash") ? "cod" : "bank_transfer").equals(order.paymentMethod()))
            throw new MoneyRejected(MoneyRejected.Reason.METHOD);
    }

    /** Corruption is an infrastructure failure, not permission to guess a new receipt or refund. */
    public static void invariant(MoneyOrder order, PaymentReceipt principal) {
        boolean unpaid = order.paymentStatus().equals("unpaid");
        if (unpaid != (principal == null)) throw inconsistent();
        if (principal != null) {
            if (!principal.appliedToOrder()
                    || !principal.orderLinkVerified()
                    || !order.id().equals(principal.orderId())
                    || order.totalVnd() != principal.amountVnd()
                    || !(principal.method().equals("cash") ? "cod" : "bank_transfer").equals(order.paymentMethod())
                    || !principal.status().equals(order.paymentStatus().equals("refunded") ? "refunded" : "succeeded"))
                throw inconsistent();
            if (principal.method().equals("bank") && !bankMatches(order, principal.bank())) throw inconsistent();
        }
        if (Set.of("refund_pending", "refunded").contains(order.paymentStatus())
                && !order.status().equals("cancelled")) throw inconsistent();
        if (order.status().equals("cancelled") && order.paymentStatus().equals("paid")) throw inconsistent();
        if (order.paymentMethod().equals("bank_transfer")
                && !Set.of("awaiting_payment", "cancelled").contains(order.status())
                && !order.paymentStatus().equals("paid")) throw inconsistent();
        if (order.fulfillmentMethod().equals("pickup")
                && order.status().equals("completed")
                && !order.paymentStatus().equals("paid")) throw inconsistent();
    }

    public static void apply(
            MoneyOrder order,
            PaymentReceipt receipt,
            PaymentReceipt principal,
            boolean allowCancelled,
            boolean checkContent) {
        invariant(order, principal);
        requireMethod(order, receipt.method());
        if (principal != null
                || !order.paymentStatus().equals("unpaid")
                || receipt.appliedToOrder()
                || !receipt.status().equals("succeeded")
                || receipt.amountVnd() != order.totalVnd()) throw MoneyRejected.conflict();
        boolean cancelled = allowCancelled && order.status().equals("cancelled");
        if (!cancelled
                && !(receipt.method().equals("cash")
                        ? CASH_STATES.contains(order.status())
                        : order.status().equals("awaiting_payment"))) throw MoneyRejected.conflict();
        if (receipt.method().equals("bank")
                && (!bankMatches(order, receipt.bank())
                        || (checkContent && !receipt.bank().identifies(order.orderNumber()))))
            throw MoneyRejected.conflict();
    }

    public static void refund(MoneyOrder order, PaymentReceipt receipt, PaymentReceipt principal) {
        if (order != null) invariant(order, principal);
        if (!receipt.status().equals("succeeded")) throw MoneyRejected.conflict();
        if (receipt.appliedToOrder()
                && (order == null
                        || !order.status().equals("cancelled")
                        || !order.paymentStatus().equals("refund_pending")
                        || principal == null
                        || !principal.id().equals(receipt.id()))) throw MoneyRejected.conflict();
    }

    private static boolean bankMatches(MoneyOrder order, BankEvidence bank) {
        return bank != null
                && Objects.equals(order.bankCode(), bank.bankCode())
                && Objects.equals(order.accountNo(), bank.accountNo());
    }

    private static IllegalStateException inconsistent() {
        return new IllegalStateException("Order money state is inconsistent");
    }
}
