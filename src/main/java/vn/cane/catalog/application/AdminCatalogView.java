package vn.cane.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminCatalogView {
    private AdminCatalogView() {}

    public record Sku(
            String sku,
            String name,
            String unitLabel,
            long priceVnd,
            int stockQty,
            boolean isActive,
            String deliveryScope,
            Instant updatedAt,
            UUID updatedBy) {}

    public record Skus(List<Sku> items, Instant serverTime) {}

    public record Movement(
            UUID id,
            String sku,
            int delta,
            String reason,
            UUID orderId,
            UUID eventId,
            String actor,
            String note,
            Instant createdAt) {}

    public record Query(String sku, UUID orderId, String reason, int page, int pageSize) {
        public Query {
            if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid ledger page");
        }

        public long offset() {
            return (long) (page - 1) * pageSize;
        }
    }

    public record Movements(List<Movement> items, int page, int pageSize, long total, Instant serverTime) {}
}
