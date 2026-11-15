package vn.cane.payments.domain;

import java.util.UUID;

public final class MoneyRejected extends RuntimeException {
    public enum Reason {
        STATE,
        METHOD,
        PAYMENT,
        NOT_FOUND
    }

    private final Reason reason;
    private final UUID orderId;
    private final String status;

    public MoneyRejected(Reason reason) {
        this(reason, null, null);
    }

    public MoneyRejected(Reason reason, UUID orderId, String status) {
        super("Money operation rejected");
        this.reason = reason;
        this.orderId = orderId;
        this.status = status;
    }

    public Reason reason() {
        return reason;
    }

    public UUID orderId() {
        return orderId;
    }

    public String status() {
        return status;
    }

    public static MoneyRejected conflict() {
        return new MoneyRejected(Reason.PAYMENT);
    }
}
