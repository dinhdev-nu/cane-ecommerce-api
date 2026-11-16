package vn.cane.payments.infrastructure;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.payments.application.ReceiptNotificationReader;

@Repository
public class JdbcReceiptNotificationReader implements ReceiptNotificationReader {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReceiptNotificationReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Facts read(UUID id) {
        return jdbc
                .query(
                        "SELECT amount_vnd,method,needs_review,order_link_verified FROM cane.payments WHERE id=:id",
                        Map.of("id", id),
                        (r, n) -> new Facts(r.getLong(1), r.getString(2), r.getBoolean(3), r.getBoolean(4)))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Notification receipt missing"));
    }
}
