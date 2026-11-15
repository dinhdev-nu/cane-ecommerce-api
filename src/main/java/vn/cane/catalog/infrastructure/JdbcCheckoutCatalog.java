package vn.cane.catalog.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.catalog.application.CheckoutCatalog;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcCheckoutCatalog implements CheckoutCatalog {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCheckoutCatalog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> read(Collection<String> skus) {
        return select(skus, false);
    }

    public List<Product> lock(Collection<String> skus) {
        TransactionAccess.requireWrite();
        return select(skus, true);
    }

    private List<Product> select(Collection<String> skus, boolean locked) {
        return jdbc.query(
                """
                SELECT sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope
                FROM cane.products WHERE sku IN (:skus) ORDER BY sku
                """ + (locked ? " FOR UPDATE" : ""),
                Map.of("skus", skus),
                (r, n) -> new Product(
                        r.getString("sku"),
                        r.getString("name"),
                        r.getString("unit_label"),
                        r.getLong("price_vnd"),
                        r.getInt("stock_qty"),
                        r.getBoolean("is_active"),
                        r.getString("delivery_scope")));
    }

    public void purchase(UUID orderId, UUID eventId, List<Purchase> items) {
        TransactionAccess.requireWrite();
        for (var item : items) {
            var parameters = Map.of("sku", item.sku(), "quantity", item.quantity(), "order", orderId, "event", eventId);
            if (jdbc.update("""
                    UPDATE cane.products SET stock_qty=stock_qty-:quantity,updated_at=now()
                    WHERE sku=:sku AND stock_qty>=:quantity
                    """, parameters) != 1) throw new IllegalStateException("Locked stock changed unexpectedly");
            jdbc.update("""
                    INSERT INTO cane.stock_movements(sku,delta,reason,order_id,event_id,actor)
                    VALUES (:sku,-:quantity,'order',:order,:event,'system')
                    """, parameters);
        }
    }
}
