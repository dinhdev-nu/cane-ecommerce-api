package vn.cane.orders.domain;

public final class OrderActionRejected extends RuntimeException {
    private final boolean missing;

    public OrderActionRejected(boolean missing) {
        super("Order action rejected");
        this.missing = missing;
    }

    public boolean missing() {
        return missing;
    }
}
