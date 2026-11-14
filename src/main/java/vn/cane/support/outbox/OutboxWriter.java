package vn.cane.support.outbox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.outbox.Notification.Audience;
import vn.cane.support.outbox.Notification.Channel;
import vn.cane.support.outbox.Notification.Kind;
import vn.cane.support.transaction.TransactionAccess;
import vn.cane.support.validation.AuditJson;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

/** Queue only. Rendering, claiming, leasing and providers belong to the worker implementation. */
@Repository
public class OutboxWriter {
    private static final Set<String> INTERNAL =
            Set.of("operation_noop", "stock_adjusted", "product_updated", "settings_updated");
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final InputNormalizer normalizer;

    public OutboxWriter(NamedParameterJdbcTemplate jdbc, JsonMapper json, InputNormalizer normalizer) {
        this.jdbc = jdbc;
        this.json = json;
        this.normalizer = normalizer;
    }

    /** Pass all customer/Owner candidates for this event in one call inside its business transaction. */
    public List<UUID> enqueue(UUID eventId, List<Notification> candidates) {
        TransactionAccess.requireWrite();
        var input = List.copyOf(candidates);
        if (input.isEmpty()) return List.of();
        Source source = source(eventId);
        var selected = new LinkedHashMap<RecipientKey, Prepared>();
        // Validate every candidate before any INSERT; customer wins even when Owner was listed first.
        input.stream().sorted(Comparator.comparing(Notification::audience)).forEach(notification -> {
            Prepared prepared = prepare(source, notification);
            selected.putIfAbsent(new RecipientKey(notification.channel(), prepared.recipient()), prepared);
        });
        var inserted = new ArrayList<UUID>();
        for (Prepared prepared : selected.values()) {
            var notification = prepared.notification();
            var parameters = new MapSqlParameterSource()
                    .addValue("id", notification.id())
                    .addValue("event", eventId)
                    .addValue("kind", notification.kind().databaseValue())
                    .addValue("order", notification.orderId())
                    .addValue("payment", notification.paymentId())
                    .addValue("channel", notification.channel().databaseValue())
                    .addValue("recipient", prepared.recipient());
            inserted.addAll(jdbc.query("""
                    INSERT INTO cane.notification_outbox(id,kind,order_id,event_id,payment_id,channel,recipient)
                    VALUES (:id,:kind,:order,:event,:payment,:channel,:recipient)
                    ON CONFLICT (event_id,channel,recipient) DO NOTHING RETURNING id
                    """, parameters, (row, number) -> row.getObject("id", UUID.class)));
        }
        return List.copyOf(inserted);
    }

    private Source source(UUID eventId) {
        var rows = jdbc.query(
                """
                SELECT e.order_id,e.payment_id,e.kind,e.data,o.recipient_email,s.value AS owner_notify
                FROM cane.business_events e LEFT JOIN cane.orders o ON o.id=e.order_id
                CROSS JOIN cane.settings s WHERE e.id=:event AND s.key='owner_notify'
                """,
                Map.of("event", eventId),
                (row, number) -> new Source(
                        row.getObject("order_id", UUID.class),
                        row.getObject("payment_id", UUID.class),
                        row.getString("kind"),
                        AuditJson.facts(json.readTree(row.getString("data"))),
                        row.getString("recipient_email"),
                        json.readTree(row.getString("owner_notify"))));
        if (rows.size() != 1 || INTERNAL.contains(rows.getFirst().kind())) throw invalidSource();
        return rows.getFirst();
    }

    private Prepared prepare(Source source, Notification notification) {
        if (!Objects.equals(source.orderId(), notification.orderId())
                || !Objects.equals(source.paymentId(), notification.paymentId())
                || !accepts(notification.kind(), source.kind())) throw invalidSource();
        String recipient = normalize(notification.channel(), notification.recipient());
        if (notification.audience() == Audience.CUSTOMER) {
            if (notification.kind().ownerOnly()
                    || notification.channel() != Channel.EMAIL
                    || source.orderId() == null
                    || !recipient.equals(normalize(Channel.EMAIL, source.customerEmail()))) throw invalidSource();
            if ((notification.kind() == Kind.PAYMENT_RECEIVED || notification.kind() == Kind.REFUNDED)
                    && !source.facts().path("orderLinkVerified").asBoolean(false)) throw invalidSource();
            if (notification.kind() == Kind.PAYMENT_RECEIVED
                    && !"bank".equals(source.facts().path("method").asString())) throw invalidSource();
        } else {
            if (!notification.kind().ownerOnly() || !source.ownerNotify().isObject()) throw invalidSource();
            JsonNode configured =
                    source.ownerNotify().get(notification.channel() == Channel.EMAIL ? "email" : "telegramChatId");
            if (configured == null
                    || !configured.isString()
                    || !recipient.equals(normalize(notification.channel(), configured.asString())))
                throw invalidSource();
        }
        return new Prepared(notification, recipient);
    }

    private String normalize(Channel channel, String recipient) {
        try {
            return channel == Channel.EMAIL
                    ? normalizer.email(recipient, "recipient")
                    : InputNormalizer.requiredText(recipient, Integer.MAX_VALUE, "recipient");
        } catch (InputValidationException invalidRecipient) {
            throw invalidSource();
        }
    }

    private static boolean accepts(Kind kind, String eventKind) {
        return switch (kind) {
            case ORDER_PLACED, OWNER_NEW_ORDER -> eventKind.equals("order_placed");
            case PAYMENT_RECEIVED ->
                Set.of("payment_received", "payment_applied", "payment_resolved")
                        .contains(eventKind);
            case SHIPPED -> eventKind.equals("order_shipped");
            case READY_PICKUP -> eventKind.equals("order_ready_pickup");
            case COMPLETED -> eventKind.equals("order_completed");
            case CANCELLED -> eventKind.equals("order_cancelled");
            case DELIVERY_FAILED_CLOSED -> eventKind.equals("delivery_failed_closed");
            case REFUNDED -> eventKind.equals("payment_refunded");
            case LINK_RESENT -> eventKind.equals("link_resent") || eventKind.equals("link_rotated");
            case OWNER_PAYMENT_DUE -> eventKind.equals("owner_payment_due");
            // Business caller decides why an Owner alert is needed. Never infer current money/order state here.
            case OWNER_ALERT -> true;
        };
    }

    private static IllegalStateException invalidSource() {
        return new IllegalStateException("Notification source or recipient is inconsistent");
    }

    private record Source(
            UUID orderId, UUID paymentId, String kind, ObjectNode facts, String customerEmail, JsonNode ownerNotify) {
        @Override
        public String toString() {
            return "NotificationSource[redacted]";
        }
    }

    private record RecipientKey(Channel channel, String recipient) {
        @Override
        public String toString() {
            return "NotificationRecipient[redacted]";
        }
    }

    private record Prepared(Notification notification, String recipient) {
        @Override
        public String toString() {
            return "PreparedNotification[redacted]";
        }
    }
}
