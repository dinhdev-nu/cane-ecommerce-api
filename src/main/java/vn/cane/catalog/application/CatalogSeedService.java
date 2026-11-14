package vn.cane.catalog.application;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import vn.cane.catalog.domain.CatalogSkus;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.NumericInputs;

@Service
public final class CatalogSeedService {
    private final CatalogSeedRepository repository;
    private final Transactions transactions;
    private final AdvisoryLocks locks;

    public CatalogSeedService(CatalogSeedRepository repository, Transactions transactions, AdvisoryLocks locks) {
        this.repository = repository;
        this.transactions = transactions;
        this.locks = locks;
    }

    public Result seed(CatalogSeedManifest manifest) {
        return transactions.write(() -> {
            locks.acquire("catalog-seed", null, "approved-nine-skus-v1");
            Set<String> existing = validateExisting(repository.lockExisting());
            int inserted = 0;
            for (var product : manifest.products().stream()
                    .sorted(Comparator.comparing(CatalogSeedManifest.ProductSeed::sku))
                    .toList()) {
                if (!existing.contains(product.sku()) && repository.insertMissing(product)) inserted++;
            }
            if (!validateExisting(repository.lockExisting()).equals(CatalogSkus.ALL)) {
                throw new IllegalStateException("Catalog seed is incomplete");
            }
            return new Result(inserted, CatalogSkus.ALL.size() - inserted);
        });
    }

    private static Set<String> validateExisting(List<CatalogSeedRepository.ExistingProduct> products) {
        var skus = new HashSet<String>();
        for (var product : products) {
            if (!CatalogSkus.ALL.contains(product.sku())
                    || !skus.add(product.sku())
                    || product.name().isBlank()
                    || product.unitLabel().isBlank()
                    || product.priceVnd() <= 0
                    || product.priceVnd() > NumericInputs.MAX_VND
                    || product.stockQty() < 0
                    || !Set.of("city", "near").contains(product.deliveryScope())) {
                throw new IllegalStateException("Existing catalog data needs operator review");
            }
        }
        return skus;
    }

    public record Result(int inserted, int preserved) {}
}
