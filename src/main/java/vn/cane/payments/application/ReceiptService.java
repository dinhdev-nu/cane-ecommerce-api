package vn.cane.payments.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.cane.payments.domain.MoneyRejected;
import vn.cane.payments.domain.PaymentReceipt;
import vn.cane.support.transaction.TransactionAccess;

/** Receipt-owned persistence. The Orders caller owns locks, money policy and the outer transaction. */
@Service
public final class ReceiptService {
    private final ReceiptRepository receipts;
    private final PaymentLocks locks;

    public ReceiptService(ReceiptRepository receipts, PaymentLocks locks) {
        this.receipts = receipts;
        this.locks = locks;
    }

    public Optional<PaymentReceipt> find(UUID id) {
        return receipts.find(id);
    }

    public Optional<PaymentReceipt> findBank(String key) {
        return receipts.findBank(key);
    }

    public List<PaymentReceipt> principals(Collection<UUID> orderIds) {
        return receipts.principals(orderIds);
    }

    public void lock(Collection<UUID> ids) {
        locks.lockIds(ids);
    }

    public PaymentReceipt insert(
            UUID orderId, MoneyCommands.Entry entry, UUID actor, boolean verified, boolean review) {
        TransactionAccess.requireWrite();
        return receipts.insert(orderId, entry, actor, verified, review);
    }

    public PaymentReceipt apply(PaymentReceipt receipt, UUID orderId, UUID actor, boolean reviewed) {
        TransactionAccess.requireWrite();
        return receipts.apply(receipt.id(), orderId, actor, reviewed || receipt.needsReview());
    }

    public PaymentReceipt review(PaymentReceipt receipt, UUID orderId, UUID actor) {
        TransactionAccess.requireWrite();
        return receipts.review(receipt.id(), orderId, actor);
    }

    public PaymentReceipt refund(PaymentReceipt receipt, String reference) {
        TransactionAccess.requireWrite();
        return receipts.refund(receipt.id(), reference);
    }

    public static void sameTransaction(PaymentReceipt stored, MoneyCommands.Entry incoming) {
        if (!stored.method().equals(incoming.method())
                || stored.amountVnd() != incoming.amountVnd()
                || !java.util.Objects.equals(stored.bank(), incoming.bank())) throw MoneyRejected.conflict();
    }
}
