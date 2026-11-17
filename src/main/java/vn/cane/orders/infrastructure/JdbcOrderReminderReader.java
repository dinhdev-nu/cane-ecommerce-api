package vn.cane.orders.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.orders.application.OrderReminderReader;
import vn.cane.support.audit.ReminderTarget;

@Repository
public class JdbcOrderReminderReader implements OrderReminderReader {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderReminderReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> manualDue(Instant now) {
        return jdbc.query(
                """
            SELECT o.id FROM cane.orders o WHERE o.status='awaiting_payment' AND o.bank_confirmation_mode='manual'
              AND o.payment_due_at<=:now AND NOT EXISTS(SELECT 1 FROM cane.business_events e
                WHERE e.source_key='order:'||o.id||':payment_due')
            ORDER BY o.payment_due_at,o.id LIMIT 50
            """,
                Map.of("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)),
                (r, n) -> r.getObject(1, UUID.class));
    }

    public UUID upperId() {
        return jdbc
                .query(
                        "SELECT id FROM cane.orders ORDER BY id DESC LIMIT 1",
                        Map.of(),
                        (r, n) -> r.getObject(1, UUID.class))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<ReminderTarget> page(String type, UUID after, UUID through) {
        if (through == null) return List.of();
        return select(
                type,
                " AND o.id<=:through" + (after == null ? "" : " AND o.id>:after") + " ORDER BY o.id LIMIT 100",
                new MapSqlParameterSource("through", through).addValue("after", after));
    }

    public ReminderTarget current(String type, UUID id) {
        return select(type, " AND o.id=:id", new MapSqlParameterSource("id", id)).stream()
                .findFirst()
                .orElse(null);
    }

    private List<ReminderTarget> select(String type, String suffix, MapSqlParameterSource args) {
        String condition = switch (type) {
            case "payment_due" -> "o.status='awaiting_payment' AND o.bank_confirmation_mode='manual'";
            case "confirmed", "preparing", "ready_for_pickup" -> "o.status=:type";
            case "cod_unpaid" ->
                "o.status='completed' AND o.fulfillment_method='delivery' AND o.payment_method='cod' AND o.payment_status='unpaid'";
            case "delivery_issue" -> "o.status='shipping' AND o.fulfillment_method='delivery' AND o.delivery_issue";
            case "refund_pending" -> "o.status='cancelled' AND o.payment_status='refund_pending'";
            default -> throw new IllegalArgumentException("Unknown order reminder");
        };
        String age = switch (type) {
            case "payment_due" -> "o.payment_due_at";
            case "delivery_issue" -> """
                (SELECT min(e.created_at) FROM cane.business_events e WHERE e.order_id=o.id
                  AND e.kind='delivery_issue_set' AND e.created_at>COALESCE((SELECT max(c.created_at)
                    FROM cane.business_events c WHERE c.order_id=o.id AND c.kind='delivery_issue_cleared'),'-infinity'::timestamptz))
                """;
            case "refund_pending" -> """
                (SELECT min(e.created_at) FROM cane.business_events e WHERE e.order_id=o.id
                  AND e.data->>'paymentStatus'='refund_pending'
                  AND e.kind IN ('order_cancelled','delivery_failed_closed','payment_applied','payment_resolved','payment_received'))
                """;
            default -> """
                (SELECT max(e.created_at) FROM cane.business_events e WHERE e.order_id=o.id
                  AND e.to_status=o.status AND e.from_status IS DISTINCT FROM e.to_status)
                """;
        };
        // Types and suffixes are server constants. Only data values are bound.
        return jdbc.query(
                "SELECT o.id," + age + " AS since FROM cane.orders o WHERE " + condition + suffix,
                args.addValue("type", type),
                (r, n) -> new ReminderTarget(
                        r.getObject("id", UUID.class),
                        r.getObject("id", UUID.class),
                        null,
                        r.getObject("since", OffsetDateTime.class) == null
                                ? null
                                : r.getObject("since", OffsetDateTime.class).toInstant()));
    }
}
