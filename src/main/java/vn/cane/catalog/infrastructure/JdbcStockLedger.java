package vn.cane.catalog.infrastructure;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.catalog.application.StockLedger;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcStockLedger implements StockLedger {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStockLedger(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasOrderReturn(UUID id) {
        TransactionAccess.requireWrite();
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM cane.stock_movements WHERE order_id=:id AND reason IN ('cancel','delivery_return'))",
                Map.of("id", id),
                Boolean.class));
    }

    public void setStock(String sku, int quantity, UUID actor) {
        TransactionAccess.requireWrite();
        if (jdbc.update(
                        "UPDATE cane.products SET stock_qty=:qty,updated_by=:actor WHERE sku=:sku",
                        Map.of("sku", sku, "qty", quantity, "actor", actor))
                != 1) throw new IllegalStateException("Locked stock changed unexpectedly");
    }

    public void append(
            UUID movement, String sku, int delta, String reason, UUID order, UUID event, UUID actor, String note) {
        TransactionAccess.requireWrite();
        jdbc.update(
                "INSERT INTO cane.stock_movements(id,sku,delta,reason,order_id,event_id,actor,note) VALUES (:id,:sku,:delta,:reason,:order,:event,:actor,:note)",
                new MapSqlParameterSource("id", movement)
                        .addValue("sku", sku)
                        .addValue("delta", delta)
                        .addValue("reason", reason)
                        .addValue("order", order)
                        .addValue("event", event)
                        .addValue("actor", actor.toString())
                        .addValue("note", note));
    }
}
