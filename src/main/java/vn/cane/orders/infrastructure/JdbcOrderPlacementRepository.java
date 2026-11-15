package vn.cane.orders.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.catalog.application.CheckoutCatalog.Product;
import vn.cane.checkout.domain.Quote;
import vn.cane.orders.application.OrderPlacementRepository;
import vn.cane.orders.application.PlaceOrderCommand;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcOrderPlacementRepository implements OrderPlacementRepository {
    private static final String SELECT = """
            SELECT id,order_number,status,payment_status,fulfillment_method,subtotal_vnd,shipping_fee_vnd,total_vnd,
              bank_confirmation_mode,payment_due_at,bank_snapshot::text,link_version,link_expires_at,request_hash
            FROM cane.orders WHERE idempotency_key=:key
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcOrderPlacementRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<PlacedOrder> find(String key) {
        return jdbc.query(SELECT, Map.of("key", key), (r, n) -> row(r)).stream().findFirst();
    }

    public PlacedOrder insert(
            UUID id,
            String number,
            Instant now,
            PlaceOrderCommand command,
            CheckoutSettings settings,
            Quote quote,
            List<Product> products) {
        TransactionAccess.requireWrite();
        boolean bank = command.checkout().paymentMethod().equals("bank_transfer");
        String address = null;
        if (command.checkout().address() != null) {
            var input = command.checkout().address();
            var zone = settings.zones().stream()
                    .filter(z -> z.wardCode().equals(input.wardCode()))
                    .findFirst()
                    .orElseThrow();
            address = json.writeValueAsString(json.createObjectNode()
                    .put("wardCode", zone.wardCode())
                    .put("wardName", zone.name())
                    .put("line1", input.line1()));
        }
        var p = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("number", number)
                .addValue("status", bank ? "awaiting_payment" : "confirmed")
                .addValue("fulfillment", command.checkout().fulfillmentMethod())
                .addValue("method", command.checkout().paymentMethod())
                .addValue("name", command.recipient().name())
                .addValue("phone", command.recipient().phone())
                .addValue("email", command.recipient().email())
                .addValue("address", address)
                .addValue("store", address == null ? json.writeValueAsString(settings.store()) : null)
                .addValue("note", command.note())
                .addValue("subtotal", quote.subtotalVnd())
                .addValue("fee", quote.shippingFeeVnd())
                .addValue("bank", bank ? json.writeValueAsString(settings.bank()) : null)
                .addValue("mode", bank ? settings.bankMode() : null)
                .addValue(
                        "due",
                        bank
                                ? OffsetDateTime.ofInstant(
                                        now.plus(settings.holdMinutes(), ChronoUnit.MINUTES), java.time.ZoneOffset.UTC)
                                : null)
                .addValue(
                        "expires",
                        OffsetDateTime.ofInstant(
                                now.plus(settings.linkDays(), ChronoUnit.DAYS), java.time.ZoneOffset.UTC))
                .addValue("key", command.key())
                .addValue("hash", command.requestHash())
                .addValue("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO cane.orders(id,order_number,status,payment_status,fulfillment_method,payment_method,
                  recipient_name,recipient_phone,recipient_email,address,store_snapshot,note,subtotal_vnd,shipping_fee_vnd,
                  bank_snapshot,bank_confirmation_mode,payment_due_at,link_version,link_expires_at,idempotency_key,request_hash,created_at,updated_at)
                VALUES (:id,:number,:status,'unpaid',:fulfillment,:method,:name,:phone,:email,CAST(:address AS jsonb),CAST(:store AS jsonb),
                  :note,:subtotal,:fee,CAST(:bank AS jsonb),:mode,:due,1,:expires,:key,:hash,:now,:now)
                """, p);
        for (var item : command.checkout().items()) {
            var product = products.stream()
                    .filter(v -> v.sku().equals(item.sku()))
                    .findFirst()
                    .orElseThrow();
            jdbc.update(
                    """
                    INSERT INTO cane.order_items(order_id,sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,delivery_scope_snapshot)
                    VALUES (:order,:sku,:name,:unit,:price,:quantity,:scope)
                    """,
                    new MapSqlParameterSource()
                            .addValue("order", id)
                            .addValue("sku", product.sku())
                            .addValue("name", product.name())
                            .addValue("unit", product.unitLabel())
                            .addValue("price", product.priceVnd())
                            .addValue("quantity", item.quantity())
                            .addValue("scope", product.deliveryScope()));
        }
        var result = find(command.key()).orElseThrow();
        if (result.totalVnd() != quote.totalVnd())
            throw new IllegalStateException("Stored order total does not match quote");
        return result;
    }

    private PlacedOrder row(ResultSet r) throws SQLException {
        var due = r.getObject("payment_due_at", OffsetDateTime.class);
        var bank = r.getString("bank_snapshot");
        return new PlacedOrder(
                r.getObject("id", UUID.class),
                r.getString("order_number"),
                r.getString("status"),
                r.getString("payment_status"),
                r.getString("fulfillment_method"),
                r.getLong("subtotal_vnd"),
                r.getLong("shipping_fee_vnd"),
                r.getLong("total_vnd"),
                r.getString("bank_confirmation_mode"),
                due == null ? null : due.toInstant(),
                bank == null ? null : json.readTree(bank),
                r.getLong("link_version"),
                r.getObject("link_expires_at", OffsetDateTime.class).toInstant(),
                r.getBytes("request_hash"));
    }
}
