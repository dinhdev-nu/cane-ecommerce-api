package vn.cane.catalog.application;

import java.util.UUID;

public interface InitialStockRepository {
    int lockStock(String sku);

    void setStock(String sku, int quantity, UUID ownerId);

    void appendRestock(UUID movementId, String sku, int quantity, UUID eventId, UUID ownerId, String reason);
}
