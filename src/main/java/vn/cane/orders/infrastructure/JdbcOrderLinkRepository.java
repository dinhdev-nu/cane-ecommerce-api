package vn.cane.orders.infrastructure;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.orders.application.OrderLinkRepository;
import vn.cane.orders.domain.OrderLinkState;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcOrderLinkRepository implements OrderLinkRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderLinkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrderLinkState> find(UUID id) {
        return read(id, false);
    }

    @Override
    public Optional<OrderLinkState> lock(UUID id) {
        TransactionAccess.requireWrite();
        return read(id, true);
    }

    private Optional<OrderLinkState> read(UUID id, boolean locked) {
        return jdbc
                .query(
                        "SELECT id,link_version,link_expires_at FROM cane.orders WHERE id=:id"
                                + (locked ? " FOR UPDATE" : ""),
                        Map.of("id", id),
                        (row, n) -> new OrderLinkState(
                                row.getObject("id", UUID.class),
                                row.getLong("link_version"),
                                row.getObject("link_expires_at", OffsetDateTime.class)
                                        .toInstant()))
                .stream()
                .findFirst();
    }
}
