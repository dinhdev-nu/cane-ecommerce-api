package vn.cane.catalog.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.catalog.application.ProductLocks;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcProductLocks implements ProductLocks {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProductLocks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> lockSkus(Collection<String> skus) {
        TransactionAccess.requireWrite();
        var requested = List.copyOf(skus);
        if (requested.isEmpty()) return List.of();
        return List.copyOf(jdbc.queryForList(
                "SELECT sku FROM cane.products WHERE sku IN (:skus) ORDER BY sku FOR UPDATE",
                Map.of("skus", requested),
                String.class));
    }
}
