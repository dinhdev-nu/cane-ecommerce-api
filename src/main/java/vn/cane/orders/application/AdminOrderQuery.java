package vn.cane.orders.application;

import java.time.Instant;

public record AdminOrderQuery(
        String status,
        String paymentStatus,
        String fulfillmentMethod,
        String paymentMethod,
        Boolean deliveryIssue,
        Boolean cancelRequested,
        String search,
        Instant updatedSince,
        int page,
        int pageSize) {
    public AdminOrderQuery {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid order page");
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }
}
