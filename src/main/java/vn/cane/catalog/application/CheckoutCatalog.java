package vn.cane.catalog.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Internal catalog contract. Exact stock never crosses a public read DTO. */
public interface CheckoutCatalog {
    List<Product> read(Collection<String> skus);

    List<Product> lock(Collection<String> skus);

    void purchase(UUID orderId, UUID eventId, List<Purchase> items);

    record Product(
            String sku,
            String name,
            String unitLabel,
            long priceVnd,
            int stockQty,
            boolean active,
            String deliveryScope) {}

    record Purchase(String sku, int quantity) {}
}
