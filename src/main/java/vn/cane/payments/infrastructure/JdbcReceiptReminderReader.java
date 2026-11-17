package vn.cane.payments.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.payments.application.ReceiptReminderReader;
import vn.cane.support.audit.ReminderTarget;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcReceiptReminderReader implements ReceiptReminderReader {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReceiptReminderReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID upperId() {
        return jdbc
                .query(
                        "SELECT id FROM cane.payments ORDER BY id DESC LIMIT 1",
                        Map.of(),
                        (r, n) -> r.getObject(1, UUID.class))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<ReminderTarget> page(UUID after, UUID through) {
        if (through == null) return List.of();
        return read(
                "needs_review AND id<=:through" + (after == null ? "" : " AND id>:after") + " ORDER BY id LIMIT 100",
                new MapSqlParameterSource("through", through).addValue("after", after));
    }

    public ReminderTarget lock(UUID id) {
        TransactionAccess.requireWrite();
        // Order locks must already be held by the caller if this receipt is attached.
        return read("id=:id AND needs_review FOR UPDATE", new MapSqlParameterSource("id", id)).stream()
                .findFirst()
                .orElse(null);
    }

    private List<ReminderTarget> read(String where, MapSqlParameterSource args) {
        return jdbc.query(
                "SELECT id,order_id,confirmed_at FROM cane.payments WHERE " + where,
                args,
                (r, n) -> new ReminderTarget(
                        r.getObject("id", UUID.class),
                        r.getObject("order_id", UUID.class),
                        r.getObject("id", UUID.class),
                        r.getObject("confirmed_at", OffsetDateTime.class).toInstant()));
    }
}
