package vn.cane.catalog.application;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import vn.cane.catalog.domain.CatalogSkus;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.NumericInputs;

/** Validated creation data; stock and settings are deliberately absent. */
public record CatalogSeedManifest(List<ProductSeed> products) {
    public CatalogSeedManifest {
        products = List.copyOf(products);
        var skus = new HashSet<String>();
        for (var product : products) {
            if (!skus.add(product.sku())) throw new IllegalArgumentException("Duplicate catalog SKU");
        }
        if (!skus.equals(CatalogSkus.ALL))
            throw new IllegalArgumentException("Manifest must contain the approved nine SKUs");
    }

    public record ProductSeed(
            String sku, String name, String unitLabel, long priceVnd, boolean isActive, String deliveryScope) {
        public ProductSeed {
            if (!CatalogSkus.ALL.contains(sku)) throw new IllegalArgumentException("Unknown catalog SKU");
            name = InputNormalizer.requiredText(name, 100, "name");
            unitLabel = InputNormalizer.requiredText(unitLabel, 100, "unitLabel");
            NumericInputs.vnd(BigInteger.valueOf(priceVnd), true, "priceVnd");
            if (!Set.of("city", "near").contains(deliveryScope))
                throw new IllegalArgumentException("Invalid delivery scope");
            if (CatalogSkus.JUICE_CUP.equals(sku) && !deliveryScope.equals("near")) {
                throw new IllegalArgumentException("Juice cup requires near delivery scope");
            }
        }
    }
}
