package vn.cane.catalog.application;

import java.util.Objects;
import java.util.UUID;
import vn.cane.catalog.domain.CatalogSkus;
import vn.cane.support.validation.InputNormalizer;

/** Validated, immutable input shared by hashing and the write operation. */
public record InitialStockCommand(UUID ownerId, String sku, int quantity, String reason, String key) {
    public InitialStockCommand {
        Objects.requireNonNull(ownerId);
        if (!CatalogSkus.ALL.contains(sku) || quantity < 0)
            throw new IllegalArgumentException("Approved SKU and nonnegative initial quantity are required");
        reason = InputNormalizer.requiredText(reason, 1000, "reason");
        key = InputNormalizer.idempotencyKey(key);
    }

    @Override
    public String toString() {
        return "InitialStockCommand[redacted]";
    }
}
