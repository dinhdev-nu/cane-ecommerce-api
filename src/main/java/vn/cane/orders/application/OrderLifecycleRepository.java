package vn.cane.orders.application;

import java.util.List;
import java.util.UUID;

/** Caller already owns the order row lock; no payment or catalog writes here. */
public interface OrderLifecycleRepository {
    String paymentStatus(UUID id);

    boolean hasDeliveryIssue(UUID id);

    List<Purchased> items(UUID id);

    void transition(UUID id, String status);

    void issue(UUID id, boolean issue, String reason);

    void cancel(UUID id, String kind, String reason);

    record Purchased(String sku, int quantity) {}
}
