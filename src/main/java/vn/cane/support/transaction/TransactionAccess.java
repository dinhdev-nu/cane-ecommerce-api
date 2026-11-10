package vn.cane.support.transaction;

import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class TransactionAccess {
    private TransactionAccess() {}

    public static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("A write transaction is required");
        }
    }
}
