package vn.cane.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminCatalogRepository {
    List<AdminCatalogView.Sku> skus();

    Optional<AdminCatalogView.Sku> find(String sku);

    void update(String sku, long price, boolean active, UUID actor);

    List<AdminCatalogView.Movement> movements(AdminCatalogView.Query query);

    long count(AdminCatalogView.Query query);

    Instant serverTime();
}
