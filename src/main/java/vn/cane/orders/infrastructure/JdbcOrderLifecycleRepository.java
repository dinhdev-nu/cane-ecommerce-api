package vn.cane.orders.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.orders.application.OrderLifecycleRepository;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcOrderLifecycleRepository implements OrderLifecycleRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderLifecycleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String paymentStatus(UUID id) {
        return jdbc
                .query(
                        "SELECT payment_status FROM cane.orders WHERE id=:id",
                        Map.of("id", id),
                        (r, n) -> r.getString(1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public boolean hasDeliveryIssue(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT delivery_issue FROM cane.orders WHERE id=:id", Map.of("id", id), Boolean.class));
    }

    public List<Purchased> items(UUID id) {
        return jdbc.query(
                "SELECT sku,quantity FROM cane.order_items WHERE order_id=:id ORDER BY sku",
                Map.of("id", id),
                (r, n) -> new Purchased(r.getString(1), r.getInt(2)));
    }

    public void transition(UUID id, String status) {
        TransactionAccess.requireWrite();
        check(jdbc.update("""
            UPDATE cane.orders SET status=:status,
              delivery_issue=CASE WHEN :status='completed' THEN false ELSE delivery_issue END,
              delivery_issue_reason=CASE WHEN :status='completed' THEN NULL ELSE delivery_issue_reason END,
              cancel_requested_at=CASE WHEN :status='completed' THEN NULL ELSE cancel_requested_at END,
              cancel_request_note=CASE WHEN :status='completed' THEN NULL ELSE cancel_request_note END
            WHERE id=:id
            """, Map.of("id", id, "status", status)));
    }

    public void issue(UUID id, boolean issue, String reason) {
        TransactionAccess.requireWrite();
        check(jdbc.update(
                "UPDATE cane.orders SET delivery_issue=:issue,delivery_issue_reason=:reason WHERE id=:id",
                new MapSqlParameterSource("id", id).addValue("issue", issue).addValue("reason", reason)));
    }

    public void cancel(UUID id, String kind, String reason) {
        TransactionAccess.requireWrite();
        check(jdbc.update("""
            UPDATE cane.orders SET status='cancelled',cancel_kind=:kind,cancel_reason=:reason,cancelled_at=statement_timestamp(),
              payment_status=CASE WHEN payment_status='paid' THEN 'refund_pending' ELSE payment_status END,
              payment_due_at=NULL,delivery_issue=false,delivery_issue_reason=NULL,cancel_requested_at=NULL,cancel_request_note=NULL
            WHERE id=:id
            """, Map.of("id", id, "kind", kind, "reason", reason)));
    }

    private static void check(int count) {
        if (count != 1) throw new IllegalStateException("Locked order changed unexpectedly");
    }
}
