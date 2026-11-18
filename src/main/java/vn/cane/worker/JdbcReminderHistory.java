package vn.cane.worker;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.support.audit.ReminderTarget;
import vn.cane.support.transaction.TransactionAccess;

@Repository
@Profile("worker & !api")
public class JdbcReminderHistory {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReminderHistory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String source) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM cane.business_events WHERE source_key=:source)",
                Map.of("source", source),
                Boolean.class));
    }

    public boolean queuedRecently(String type, UUID object, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
            SELECT EXISTS(SELECT 1 FROM cane.business_events e WHERE e.created_at>:cutoff
              AND EXISTS(SELECT 1 FROM cane.notification_outbox n WHERE n.event_id=e.id)
              AND ((e.data->>'alertType'=:type AND e.data->>'objectId'=:object)
                OR (:type='payment_due' AND e.source_key='order:'||:object||':payment_due')
                OR (:type='notification_exhausted' AND e.kind='notification_exhausted' AND e.data->>'failedOutboxId'=:object)
                OR (:type='payment_review' AND e.payment_id=CAST(:object AS uuid) AND e.kind LIKE 'payment_%'
                  AND EXISTS(SELECT 1 FROM cane.notification_outbox n WHERE n.event_id=e.id AND n.kind='owner_alert'))))
            """,
                new MapSqlParameterSource("type", type)
                        .addValue("object", object.toString())
                        .addValue("cutoff", OffsetDateTime.ofInstant(now.minusSeconds(86400), ZoneOffset.UTC)),
                Boolean.class));
    }

    public UUID upperOutboxId() {
        return jdbc
                .query(
                        "SELECT id FROM cane.notification_outbox ORDER BY id DESC LIMIT 1",
                        Map.of(),
                        (r, n) -> r.getObject(1, UUID.class))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<ReminderTarget> exhaustedPage(UUID after, UUID through) {
        if (through == null) return List.of();
        return exhausted(
                " AND n.id<=:through" + (after == null ? "" : " AND n.id>:after") + " ORDER BY n.id LIMIT 100",
                new MapSqlParameterSource("through", through).addValue("after", after));
    }

    public ReminderTarget lockExhausted(UUID id) {
        TransactionAccess.requireWrite();
        return exhausted(" AND n.id=:id FOR UPDATE OF n", new MapSqlParameterSource("id", id)).stream()
                .findFirst()
                .orElse(null);
    }

    private List<ReminderTarget> exhausted(String suffix, MapSqlParameterSource args) {
        return jdbc.query(
                """
            SELECT n.id,n.order_id,n.payment_id,x.created_at AS since
            FROM cane.notification_outbox n JOIN cane.business_events e ON e.id=n.event_id
            JOIN cane.business_events x ON x.source_key='outbox:'||n.id||':exhausted'
            WHERE n.status='exhausted' AND e.kind<>'notification_exhausted'
              AND COALESCE(e.data->>'alertType','')<>'notification_exhausted'
            """ + suffix,
                args,
                (r, n) -> new ReminderTarget(
                        r.getObject("id", UUID.class),
                        r.getObject("order_id", UUID.class),
                        r.getObject("payment_id", UUID.class),
                        r.getObject("since", OffsetDateTime.class).toInstant()));
    }
}
