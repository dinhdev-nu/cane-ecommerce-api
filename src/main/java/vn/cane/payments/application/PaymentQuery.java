package vn.cane.payments.application;

import java.util.UUID;

public record PaymentQuery(Boolean needsReview, UUID orderId, int page, int pageSize) {
    public PaymentQuery {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid payment page");
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }
}
