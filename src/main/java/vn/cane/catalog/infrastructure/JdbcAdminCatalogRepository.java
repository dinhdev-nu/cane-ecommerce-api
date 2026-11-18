package vn.cane.catalog.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.catalog.application.AdminCatalogRepository;
import vn.cane.catalog.application.AdminCatalogView;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcAdminCatalogRepository implements AdminCatalogRepository {
    private static final String SKU_FIELDS =
            "sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope,updated_at,updated_by";
    private static final String FILTER = """
        WHERE (CAST(:sku AS text) IS NULL OR sku=:sku) AND (CAST(:order AS uuid) IS NULL OR order_id=:order)
          AND (CAST(:reason AS text) IS NULL OR reason=:reason)
        """;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAdminCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AdminCatalogView.Sku> skus() {
        return jdbc.query("SELECT " + SKU_FIELDS + " FROM cane.products ORDER BY sku", Map.of(), this::sku);
    }

    public Optional<AdminCatalogView.Sku> find(String sku) {
        return jdbc
                .query("SELECT " + SKU_FIELDS + " FROM cane.products WHERE sku=:sku", Map.of("sku", sku), this::sku)
                .stream()
                .findFirst();
    }

    public void update(String sku, long price, boolean active, UUID actor) {
        TransactionAccess.requireWrite();
        if (jdbc.update(
                        "UPDATE cane.products SET price_vnd=:price,is_active=:active,updated_by=:actor WHERE sku=:sku",
                        Map.of("sku", sku, "price", price, "active", active, "actor", actor))
                != 1) throw new IllegalStateException("Locked product changed unexpectedly");
    }

    public List<AdminCatalogView.Movement> movements(AdminCatalogView.Query q) {
        return jdbc.query(
                "SELECT id,sku,delta,reason,order_id,event_id,actor,note,created_at FROM cane.stock_movements " + FILTER
                        + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset",
                params(q),
                (r, n) -> new AdminCatalogView.Movement(
                        r.getObject("id", UUID.class),
                        r.getString("sku"),
                        r.getInt("delta"),
                        r.getString("reason"),
                        r.getObject("order_id", UUID.class),
                        r.getObject("event_id", UUID.class),
                        r.getString("actor"),
                        r.getString("note"),
                        time(r, "created_at")));
    }

    public long count(AdminCatalogView.Query q) {
        return jdbc.queryForObject("SELECT count(*) FROM cane.stock_movements " + FILTER, params(q), Long.class);
    }

    public Instant serverTime() {
        return jdbc.queryForObject("SELECT transaction_timestamp()", Map.of(), OffsetDateTime.class)
                .toInstant();
    }

    private MapSqlParameterSource params(AdminCatalogView.Query q) {
        return new MapSqlParameterSource("sku", q.sku())
                .addValue("order", q.orderId(), Types.OTHER)
                .addValue("reason", q.reason(), Types.VARCHAR)
                .addValue("limit", q.pageSize())
                .addValue("offset", q.offset());
    }

    private AdminCatalogView.Sku sku(ResultSet r, int n) throws SQLException {
        return new AdminCatalogView.Sku(
                r.getString("sku"),
                r.getString("name"),
                r.getString("unit_label"),
                r.getLong("price_vnd"),
                r.getInt("stock_qty"),
                r.getBoolean("is_active"),
                r.getString("delivery_scope"),
                time(r, "updated_at"),
                r.getObject("updated_by", UUID.class));
    }

    private static Instant time(ResultSet r, String key) throws SQLException {
        var v = r.getObject(key, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
