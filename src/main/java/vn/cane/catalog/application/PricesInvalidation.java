package vn.cane.catalog.application;

import vn.cane.support.transaction.Transactions;

/** Writers call inside their write transaction; Redis is touched only after a successful commit. */
public final class PricesInvalidation {
    private final Transactions transactions;
    private final Runnable evict;

    public PricesInvalidation(Transactions transactions, Runnable evict) {
        this.transactions = transactions;
        this.evict = evict;
    }

    public void afterCommit() {
        transactions.afterCommit(evict);
    }
}
