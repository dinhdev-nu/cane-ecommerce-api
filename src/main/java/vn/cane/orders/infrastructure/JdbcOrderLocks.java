package vn.cane.orders.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.orders.application.OrderLocks;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcOrderLocks implements OrderLocks {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderLocks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> lockIds(Collection<UUID> orderIds) {
        TransactionAccess.requireWrite();
        var ids = List.copyOf(orderIds);
        if (ids.isEmpty()) return List.of();
        return List.copyOf(jdbc.query(
                "SELECT id FROM cane.orders WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                Map.of("ids", ids),
                (row, number) -> row.getObject("id", UUID.class)));
    }
}
