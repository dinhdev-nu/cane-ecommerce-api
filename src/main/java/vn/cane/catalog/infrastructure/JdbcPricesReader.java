package vn.cane.catalog.infrastructure;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.catalog.application.PricesReader;
import vn.cane.catalog.domain.PublicPrice;

@Repository
public class JdbcPricesReader implements PricesReader {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPricesReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PublicPrice> read() {
        return jdbc.query(
                """
                SELECT p.sku, p.price_vnd, p.delivery_scope, p.is_active, p.stock_qty,
                       (SELECT value::text::integer FROM cane.settings WHERE key = 'low_stock_threshold') AS threshold
                FROM cane.products p ORDER BY p.sku
                """,
                Map.of(),
                (row, index) -> PublicPrice.fromStock(
                        row.getString("sku"),
                        row.getLong("price_vnd"),
                        row.getBoolean("is_active"),
                        row.getInt("stock_qty"),
                        row.getObject("threshold", Integer.class),
                        row.getString("delivery_scope")));
    }
}
