package vn.cane.orders.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.orders.application.GuestOrderRepository;
import vn.cane.orders.application.GuestOrderView;
import vn.cane.orders.domain.OrderLinkState;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcGuestOrderRepository implements GuestOrderRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcGuestOrderRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Snapshot> find(UUID id) {
        return jdbc
                .query(
                        """
            SELECT id,order_number,status,payment_status,fulfillment_method,recipient_name,recipient_phone,
              recipient_email,address::text,store_snapshot::text,subtotal_vnd,shipping_fee_vnd,total_vnd,
              bank_snapshot::text,bank_confirmation_mode,payment_due_at,delivery_issue,cancel_requested_at,
              cancel_kind,link_version,link_expires_at,created_at FROM cane.orders WHERE id=:id
            """,
                        Map.of("id", id),
                        (r, n) -> new Snapshot(
                                id,
                                r.getString("order_number"),
                                r.getString("status"),
                                r.getString("payment_status"),
                                r.getString("fulfillment_method"),
                                r.getString("recipient_name"),
                                r.getString("recipient_phone"),
                                r.getString("recipient_email"),
                                node(r, "address"),
                                node(r, "store_snapshot"),
                                r.getLong("subtotal_vnd"),
                                r.getLong("shipping_fee_vnd"),
                                r.getLong("total_vnd"),
                                node(r, "bank_snapshot"),
                                r.getString("bank_confirmation_mode"),
                                at(r, "payment_due_at"),
                                r.getBoolean("delivery_issue"),
                                at(r, "cancel_requested_at"),
                                r.getString("cancel_kind"),
                                r.getLong("link_version"),
                                at(r, "link_expires_at"),
                                at(r, "created_at")))
                .stream()
                .findFirst();
    }

    public List<GuestOrderView.Item> items(UUID id) {
        return jdbc.query(
                """
            SELECT name_snapshot,unit_label_snapshot,quantity,unit_price_vnd,line_total_vnd
            FROM cane.order_items WHERE order_id=:id ORDER BY sku
            """,
                Map.of("id", id),
                (r, n) -> new GuestOrderView.Item(
                        r.getString(1), r.getString(2), r.getInt(3), r.getLong(4), r.getLong(5)));
    }

    public List<GuestOrderView.Moment> timeline(UUID id) {
        // A stored public_label is not arbitrary public copy. Only known event kinds with an
        // explicit public marker and verified historical money facts can reach this projection.
        return jdbc.query(
                """
            SELECT created_at,kind FROM cane.business_events
            WHERE order_id=:id AND public_label IS NOT NULL AND
              (kind IN ('order_placed','order_prepared','order_shipped','order_ready_pickup','order_completed',
                'order_cancelled','delivery_failed_closed','cancel_requested') OR
               (kind IN ('payment_received','payment_applied','payment_resolved','payment_refunded')
                AND data->>'orderLinkVerified'='true'))
            ORDER BY created_at,id
            """,
                Map.of("id", id),
                (r, n) -> new GuestOrderView.Moment(at(r, "created_at"), label(r.getString("kind"))));
    }

    private static String label(String kind) {
        return switch (kind) {
            case "order_placed" -> "Order placed";
            case "order_prepared" -> "Preparing order";
            case "order_shipped" -> "Order shipped";
            case "order_ready_pickup" -> "Ready for pickup";
            case "order_completed" -> "Order completed";
            case "order_cancelled" -> "Order cancelled";
            case "delivery_failed_closed" -> "Delivery closed";
            case "cancel_requested" -> "Cancellation requested";
            case "payment_refunded" -> "Refund recorded";
            default -> "Payment recorded";
        };
    }

    public void requestCancel(UUID id, Instant at, String reason) {
        TransactionAccess.requireWrite();
        jdbc.update(
                "UPDATE cane.orders SET cancel_requested_at=:at,cancel_request_note=:reason,updated_at=:at WHERE id=:id",
                new MapSqlParameterSource("id", id)
                        .addValue("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                        .addValue("reason", reason));
    }

    public void rotate(OrderLinkState link) {
        TransactionAccess.requireWrite();
        jdbc.update(
                "UPDATE cane.orders SET link_version=:version,link_expires_at=:expiry,updated_at=now() WHERE id=:id",
                Map.of(
                        "id",
                        link.orderId(),
                        "version",
                        link.version(),
                        "expiry",
                        OffsetDateTime.ofInstant(link.expiresAt(), ZoneOffset.UTC)));
    }

    private JsonNode node(ResultSet r, String field) throws SQLException {
        String text = r.getString(field);
        return text == null ? null : json.readTree(text);
    }

    private static Instant at(ResultSet r, String field) throws SQLException {
        var value = r.getObject(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
