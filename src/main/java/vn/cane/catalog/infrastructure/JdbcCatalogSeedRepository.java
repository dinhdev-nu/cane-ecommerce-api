package vn.cane.catalog.infrastructure;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.catalog.application.CatalogSeedManifest.ProductSeed;
import vn.cane.catalog.application.CatalogSeedRepository;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcCatalogSeedRepository implements CatalogSeedRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCatalogSeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExistingProduct> lockExisting() {
        TransactionAccess.requireWrite();
        return List.copyOf(jdbc.query(
                "SELECT sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope FROM cane.products ORDER BY sku FOR UPDATE",
                Map.of(),
                (row, number) -> new ExistingProduct(
                        row.getString("sku"),
                        row.getString("name"),
                        row.getString("unit_label"),
                        row.getLong("price_vnd"),
                        row.getInt("stock_qty"),
                        row.getBoolean("is_active"),
                        row.getString("delivery_scope"))));
    }

    @Override
    public boolean insertMissing(ProductSeed product) {
        TransactionAccess.requireWrite();
        return jdbc.update(
                        """
                INSERT INTO cane.products(sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope)
                VALUES (:sku,:name,:unitLabel,:priceVnd,0,:isActive,:deliveryScope)
                ON CONFLICT (sku) DO NOTHING
                """,
                        Map.of(
                                "sku",
                                product.sku(),
                                "name",
                                product.name(),
                                "unitLabel",
                                product.unitLabel(),
                                "priceVnd",
                                product.priceVnd(),
                                "isActive",
                                product.isActive(),
                                "deliveryScope",
                                product.deliveryScope()))
                == 1;
    }
}
