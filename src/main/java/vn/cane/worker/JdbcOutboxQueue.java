package vn.cane.worker;

import java.sql.ResultSet;
import java.sql.SQLException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.support.transaction.TransactionAccess;

@Repository
@Profile("worker & !api")
public class JdbcOutboxQueue {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcOutboxQueue(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<OutboxLease> lockDue(Instant now) {
        TransactionAccess.requireWrite();
        return jdbc.query(
                """
            SELECT id,kind,order_id,payment_id,event_id,channel,recipient,attempts,locked_at
            FROM cane.notification_outbox
            WHERE (status='pending' AND next_attempt_at<=:now) OR
                  (status='processing' AND locked_at<=:stale)
            ORDER BY next_attempt_at,id LIMIT 20 FOR UPDATE SKIP LOCKED
            """,
                new MapSqlParameterSource("now", at(now)).addValue("stale", at(now.minusSeconds(300))),
                (r, n) -> lease(r));
    }

    public OutboxLease claim(OutboxLease row, Instant now) {
        TransactionAccess.requireWrite();
        jdbc.update(
                "UPDATE cane.notification_outbox SET status='processing',attempts=attempts+1,locked_at=:now WHERE id=:id",
                Map.of("id", row.id(), "now", at(now)));
        return new OutboxLease(
                row.id(),
                row.kind(),
                row.orderId(),
                row.paymentId(),
                row.eventId(),
                row.channel(),
                row.recipient(),
                row.attempts() + 1,
                now);
    }

    public boolean finish(OutboxLease lease, String status, String error, Instant next, Instant now) {
        TransactionAccess.requireWrite();
        return jdbc.update(
                        """
            UPDATE cane.notification_outbox SET status=:status,locked_at=NULL,last_error=:error,
              next_attempt_at=:next,sent_at=:sent
            WHERE id=:id AND status='processing' AND attempts=:attempts AND locked_at=:locked
            """,
                        new MapSqlParameterSource("id", lease.id())
                                .addValue("status", status)
                                .addValue("error", error)
                                .addValue("next", at(next))
                                .addValue("sent", status.equals("sent") ? at(now) : null)
                                .addValue("attempts", lease.attempts())
                                .addValue("locked", at(lease.lockedAt())))
                == 1;
    }

    public Event event(UUID id) {
        return jdbc
                .query(
                        "SELECT order_id,payment_id,kind,to_status,data::text,created_at FROM cane.business_events WHERE id=:id",
                        Map.of("id", id),
                        (r, n) -> new Event(
                                r.getObject(1, UUID.class),
                                r.getObject(2, UUID.class),
                                r.getString(3),
                                r.getString(4),
                                json.readTree(r.getString(5)),
                                r.getObject(6, OffsetDateTime.class).toInstant()))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Notification event missing"));
    }

    public UUID eventBySource(String source) {
        return jdbc
                .query(
                        "SELECT id FROM cane.business_events WHERE source_key=:source",
                        Map.of("source", source),
                        (r, n) -> r.getObject(1, UUID.class))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public boolean exhausted(UUID id) {
        return !jdbc.query(
                        "SELECT id FROM cane.notification_outbox WHERE id=:id AND status='exhausted'",
                        Map.of("id", id),
                        (r, n) -> r.getObject(1, UUID.class))
                .isEmpty();
    }

    private static OutboxLease lease(ResultSet r) throws SQLException {
        var locked = r.getObject("locked_at", OffsetDateTime.class);
        return new OutboxLease(
                r.getObject("id", UUID.class),
                r.getString("kind"),
                r.getObject("order_id", UUID.class),
                r.getObject("payment_id", UUID.class),
                r.getObject("event_id", UUID.class),
                r.getString("channel"),
                r.getString("recipient"),
                r.getInt("attempts"),
                locked == null ? null : locked.toInstant());
    }

    private static OffsetDateTime at(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record Event(UUID orderId, UUID paymentId, String kind, String toStatus, JsonNode facts, Instant at) {
        @Override
        public String toString() {
            return "NotificationEvent[redacted]";
        }
    }
}
