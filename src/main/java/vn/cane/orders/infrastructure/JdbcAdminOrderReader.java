package vn.cane.orders.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.orders.application.AdminOrderQuery;
import vn.cane.orders.application.AdminOrderReader;
import vn.cane.orders.application.AdminOrderView;
import vn.cane.orders.application.GuestOrderView;

@Repository
public class JdbcAdminOrderReader implements AdminOrderReader {
    private static final String FILTER = """
        WHERE (CAST(:status AS text) IS NULL OR status=:status)
          AND (CAST(:paymentStatus AS text) IS NULL OR payment_status=:paymentStatus)
          AND (CAST(:fulfillment AS text) IS NULL OR fulfillment_method=:fulfillment)
          AND (CAST(:paymentMethod AS text) IS NULL OR payment_method=:paymentMethod)
          AND (CAST(:issue AS boolean) IS NULL OR delivery_issue=:issue)
          AND (CAST(:cancel AS boolean) IS NULL OR (cancel_requested_at IS NOT NULL)=:cancel)
          AND (CAST(:since AS timestamptz) IS NULL OR updated_at>=:since)
          AND (CAST(:search AS text) IS NULL OR strpos(lower(order_number),lower(:search))>0
            OR strpos(lower(recipient_name),lower(:search))>0 OR strpos(recipient_phone,:search)>0)
        """;
    private static final Set<String> SAFE_ERRORS =
            Set.of("PROVIDER_TIMEOUT", "PROVIDER_FAILED", "SOURCE_INVALID", "LEASE_EXPIRED");
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcAdminOrderReader(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Instant serverTime() {
        return jdbc.queryForObject("SELECT transaction_timestamp()", Map.of(), OffsetDateTime.class)
                .toInstant();
    }

    public List<AdminOrderView.Summary> page(AdminOrderQuery q) {
        return jdbc.query(
                """
            SELECT id,order_number,status,payment_status,payment_method,fulfillment_method,recipient_name,
              total_vnd,delivery_issue,cancel_requested_at,cancel_kind,created_at,updated_at FROM cane.orders
            """ + FILTER + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset",
                params(q),
                (r, n) -> new AdminOrderView.Summary(
                        uuid(r, "id"),
                        r.getString("order_number"),
                        r.getString("status"),
                        r.getString("payment_status"),
                        r.getString("payment_method"),
                        r.getString("fulfillment_method"),
                        new AdminOrderView.Name(r.getString("recipient_name")),
                        r.getLong("total_vnd"),
                        r.getBoolean("delivery_issue"),
                        time(r, "cancel_requested_at") != null,
                        r.getString("cancel_kind"),
                        time(r, "created_at"),
                        time(r, "updated_at")));
    }

    public long count(AdminOrderQuery q) {
        return jdbc.queryForObject("SELECT count(*) FROM cane.orders " + FILTER, params(q), Long.class);
    }

    public Optional<AdminOrderView.Order> find(UUID id) {
        return jdbc
                .query("""
            SELECT id,order_number,status,payment_status,fulfillment_method,payment_method,
              recipient_name,recipient_phone,recipient_email,address::text,store_snapshot::text,note,
              subtotal_vnd,shipping_fee_vnd,total_vnd,bank_confirmation_mode,payment_due_at,bank_snapshot::text,
              delivery_issue,delivery_issue_reason,cancel_requested_at,cancel_request_note,cancel_kind,
              cancel_reason,cancelled_at,link_version,link_expires_at,created_at,updated_at
            FROM cane.orders WHERE id=:id
            """, Map.of("id", id), (r, n) -> {
                    var address = node(r, "address");
                    var store = node(r, "store_snapshot");
                    var bank = node(r, "bank_snapshot");
                    boolean awaiting = r.getString("status").equals("awaiting_payment");
                    String mode = r.getString("bank_confirmation_mode");
                    return new AdminOrderView.Order(
                            id,
                            r.getString("order_number"),
                            r.getString("status"),
                            r.getString("payment_status"),
                            r.getString("fulfillment_method"),
                            r.getString("payment_method"),
                            new AdminOrderView.Recipient(
                                    r.getString("recipient_name"),
                                    r.getString("recipient_phone"),
                                    r.getString("recipient_email")),
                            address == null
                                    ? null
                                    : new AdminOrderView.Address(
                                            text(address, "wardCode"),
                                            text(address, "wardName"),
                                            text(address, "line1")),
                            store == null
                                    ? null
                                    : new AdminOrderView.Store(
                                            text(store, "name"),
                                            text(store, "address"),
                                            text(store, "openingHours"),
                                            text(store, "pickupGuidance")),
                            r.getString("note"),
                            r.getLong("subtotal_vnd"),
                            r.getLong("shipping_fee_vnd"),
                            r.getLong("total_vnd"),
                            mode,
                            awaiting && "manual".equals(mode) ? time(r, "payment_due_at") : null,
                            awaiting && "auto".equals(mode) ? time(r, "payment_due_at") : null,
                            awaiting && bank != null
                                    ? new GuestOrderView.Bank(
                                            text(bank, "bankCode"),
                                            text(bank, "bankName"),
                                            text(bank, "accountNo"),
                                            text(bank, "accountName"),
                                            r.getLong("total_vnd"),
                                            r.getString("order_number"))
                                    : null,
                            r.getBoolean("delivery_issue"),
                            r.getString("delivery_issue_reason"),
                            time(r, "cancel_requested_at"),
                            r.getString("cancel_request_note"),
                            r.getString("cancel_kind"),
                            r.getString("cancel_reason"),
                            time(r, "cancelled_at"),
                            r.getLong("link_version"),
                            time(r, "link_expires_at"),
                            time(r, "created_at"),
                            time(r, "updated_at"));
                })
                .stream()
                .findFirst();
    }

    public List<AdminOrderView.Item> items(UUID id) {
        return jdbc.query(
                """
            SELECT sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,line_total_vnd,delivery_scope_snapshot
            FROM cane.order_items WHERE order_id=:id ORDER BY sku
            """,
                Map.of("id", id),
                (r, n) -> new AdminOrderView.Item(
                        r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getLong(4),
                        r.getInt(5),
                        r.getLong(6),
                        r.getString(7)));
    }

    public List<AdminOrderView.Moment> timeline(UUID id) {
        return jdbc.query(
                """
            SELECT id,kind,from_status,to_status,actor,public_label,note,created_at
            FROM cane.business_events WHERE order_id=:id AND kind<>'operation_noop'
            ORDER BY created_at,id
            """,
                Map.of("id", id),
                (r, n) -> new AdminOrderView.Moment(
                        uuid(r, "id"),
                        r.getString("kind"),
                        r.getString("from_status"),
                        r.getString("to_status"),
                        r.getString("actor"),
                        label(r.getString("kind")),
                        r.getString("note"),
                        time(r, "created_at")));
    }

    public AdminOrderView.Failures failures() {
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM cane.notification_outbox WHERE status='exhausted'", Map.of(), Long.class);
        var items = jdbc.query(
                """
            SELECT o.id,o.order_id,o.event_id,o.payment_id,o.kind,o.channel,o.attempts,o.last_error,e.created_at AS exhausted_at
            FROM cane.notification_outbox o JOIN cane.business_events e
              ON e.kind='notification_exhausted' AND e.data->>'failedOutboxId'=o.id::text
            WHERE o.status='exhausted' ORDER BY e.created_at DESC,o.id DESC LIMIT 20
            """,
                Map.of(),
                (r, n) -> new AdminOrderView.Failure(
                        uuid(r, "id"),
                        uuid(r, "order_id"),
                        uuid(r, "event_id"),
                        uuid(r, "payment_id"),
                        r.getString("kind"),
                        r.getString("channel"),
                        r.getInt("attempts"),
                        time(r, "exhausted_at"),
                        safeError(r.getString("last_error"))));
        return new AdminOrderView.Failures(total, items);
    }

    private MapSqlParameterSource params(AdminOrderQuery q) {
        return new MapSqlParameterSource()
                .addValue("status", q.status(), Types.VARCHAR)
                .addValue("paymentStatus", q.paymentStatus(), Types.VARCHAR)
                .addValue("fulfillment", q.fulfillmentMethod(), Types.VARCHAR)
                .addValue("paymentMethod", q.paymentMethod(), Types.VARCHAR)
                .addValue("issue", q.deliveryIssue(), Types.BOOLEAN)
                .addValue("cancel", q.cancelRequested(), Types.BOOLEAN)
                .addValue("search", q.search(), Types.VARCHAR)
                .addValue(
                        "since",
                        q.updatedSince() == null ? null : OffsetDateTime.ofInstant(q.updatedSince(), ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("limit", q.pageSize())
                .addValue("offset", q.offset());
    }

    private JsonNode node(ResultSet r, String key) throws SQLException {
        var value = r.getString(key);
        return value == null ? null : json.readTree(value);
    }

    private static String text(JsonNode value, String key) {
        return value.path(key).asString(null);
    }

    private static UUID uuid(ResultSet r, String key) throws SQLException {
        return r.getObject(key, UUID.class);
    }

    private static Instant time(ResultSet r, String key) throws SQLException {
        var value = r.getObject(key, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String safeError(String error) {
        return error == null ? null : SAFE_ERRORS.contains(error) ? error : "DISPATCH_FAILED";
    }

    private static String label(String kind) {
        return switch (kind) {
            case "order_placed" -> "Đặt đơn";
            case "order_prepared" -> "Bắt đầu chuẩn bị";
            case "order_shipped" -> "Đã bàn giao giao hàng";
            case "order_ready_pickup" -> "Sẵn sàng lấy";
            case "order_completed" -> "Hoàn tất đơn";
            case "order_cancelled" -> "Hủy trước bàn giao";
            case "delivery_failed_closed" -> "Đóng giao thất bại";
            case "cancel_requested" -> "Khách yêu cầu hủy";
            case "payment_received", "payment_applied", "payment_resolved" -> "Ghi nhận khoản thu";
            case "payment_refunded" -> "Xác nhận hoàn tiền";
            default -> "Cập nhật đơn hàng";
        };
    }
}
