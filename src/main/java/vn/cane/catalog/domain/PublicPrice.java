package vn.cane.catalog.domain;

import java.util.Set;
import vn.cane.support.validation.NumericInputs;

/** The public allowlist deliberately excludes names, units and exact stock. */
public record PublicPrice(String sku, long priceVnd, String availability, String deliveryScope) {
    public static PublicPrice fromStock(
            String sku, long priceVnd, boolean active, int stockQty, int threshold, String deliveryScope) {
        if (stockQty < 0 || threshold < 0) throw new IllegalArgumentException("Invalid stock configuration");
        String availability = !active ? "paused" : stockQty == 0 ? "out" : stockQty < threshold ? "low" : "in_stock";
        return new PublicPrice(sku, priceVnd, availability, deliveryScope);
    }

    public PublicPrice {
        if (!CatalogSkus.ALL.contains(sku)
                || priceVnd <= 0
                || priceVnd > NumericInputs.MAX_VND
                || !Set.of("paused", "out", "low", "in_stock").contains(availability)
                || !Set.of("city", "near").contains(deliveryScope)) {
            throw new IllegalArgumentException("Invalid public price");
        }
    }
}
