package vn.cane.catalog.application;

import java.util.List;
import vn.cane.catalog.application.CatalogSeedManifest.ProductSeed;

public interface CatalogSeedRepository {
    List<ExistingProduct> lockExisting();

    boolean insertMissing(ProductSeed product);

    record ExistingProduct(
            String sku,
            String name,
            String unitLabel,
            long priceVnd,
            int stockQty,
            boolean isActive,
            String deliveryScope) {}
}
