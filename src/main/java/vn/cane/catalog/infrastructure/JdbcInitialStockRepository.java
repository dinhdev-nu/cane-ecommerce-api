package vn.cane.catalog.infrastructure;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.catalog.application.InitialStockRepository;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcInitialStockRepository implements InitialStockRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcInitialStockRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int lockStock(String sku) {
        TransactionAccess.requireWrite();
        var rows = jdbc.query(
                "SELECT stock_qty FROM cane.products WHERE sku=:sku FOR UPDATE",
                Map.of("sku", sku),
                (row, number) -> row.getInt("stock_qty"));
        if (rows.size() != 1) throw new IllegalStateException("Seed the approved catalog before initial stock");
        return rows.getFirst();
    }

    @Override
    public void setStock(String sku, int quantity, UUID ownerId) {
        TransactionAccess.requireWrite();
        if (jdbc.update(
                        "UPDATE cane.products SET stock_qty=:quantity,updated_by=:owner,updated_at=now() WHERE sku=:sku",
                        Map.of("sku", sku, "quantity", quantity, "owner", ownerId))
                != 1) throw new IllegalStateException("Initial stock update needs exactly one product");
    }

    @Override
    public void appendRestock(UUID movementId, String sku, int quantity, UUID eventId, UUID ownerId, String reason) {
        TransactionAccess.requireWrite();
        if (quantity <= 0) throw new IllegalArgumentException("Restock delta must be positive");
        jdbc.update(
                """
                INSERT INTO cane.stock_movements(id,sku,delta,reason,event_id,actor,note)
                VALUES (:id,:sku,:quantity,'restock',:event,:actor,:note)
                """,
                Map.of(
                        "id",
                        movementId,
                        "sku",
                        sku,
                        "quantity",
                        quantity,
                        "event",
                        eventId,
                        "actor",
                        ownerId.toString(),
                        "note",
                        reason));
    }
}
