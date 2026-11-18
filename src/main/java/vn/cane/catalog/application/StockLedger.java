package vn.cane.catalog.application;

import java.util.UUID;

public interface StockLedger {
    boolean hasOrderReturn(UUID orderId);

    void setStock(String sku, int quantity, UUID actor);

    void append(
            UUID movementId, String sku, int delta, String reason, UUID orderId, UUID eventId, UUID actor, String note);
}
