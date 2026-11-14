package vn.cane.support.audit;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.idempotency.OperationStamp;
import vn.cane.support.transaction.TransactionAccess;

/** Append only, using the caller connection/transaction. No retries, independent commits or external IO. */
@Repository
public class BusinessEventWriter {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public BusinessEventWriter(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Recorded append(BusinessEvent event) {
        return append(event, null);
    }

    public Recorded append(BusinessEvent event, OperationStamp operation) {
        TransactionAccess.requireWrite();
        if (operation != null
                && (event.actor().equals("system") || event.actor().equals("guest"))) throw invalidSource();
        var facts = event.data();
        checkFact(facts, "orderId", event.orderId());
        checkFact(facts, "paymentId", event.paymentId());
        if (operation != null) {
            checkFact(operation.result(), "orderId", event.orderId());
            checkFact(operation.result(), "paymentId", event.paymentId());
            checkFact(operation.result(), "eventId", event.id());
        }
        if (event.paymentId() != null) {
            var sources = jdbc.query(
                    "SELECT order_id,amount_vnd,method,provider_ref,refund_reference,applied_to_order,order_link_verified FROM cane.payments WHERE id=:id",
                    Map.of("id", event.paymentId()),
                    (row, number) -> {
                        checkFact(facts, "amountVnd", row.getLong("amount_vnd"));
                        checkFact(facts, "method", row.getString("method"));
                        checkFact(
                                facts,
                                "reference",
                                row.getString(
                                        event.kind().equals("payment_refunded") ? "refund_reference" : "provider_ref"));
                        checkFact(facts, "isPrincipal", row.getBoolean("applied_to_order"));
                        checkFact(facts, "orderLinkVerified", row.getBoolean("order_link_verified"));
                        return new PaymentSource(row.getObject("order_id", UUID.class));
                    });
            if (sources.size() != 1 || !Objects.equals(sources.getFirst().orderId(), event.orderId()))
                throw invalidSource();
        }
        var parameters = new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("order", event.orderId())
                .addValue("payment", event.paymentId())
                .addValue("kind", event.kind())
                .addValue("from", event.fromStatus())
                .addValue("to", event.toStatus())
                .addValue("actor", event.actor())
                .addValue("label", event.publicLabel())
                .addValue("note", event.note())
                .addValue("data", json.writeValueAsString(event.data()))
                .addValue("source", event.sourceKey())
                .addValue("key", operation == null ? null : operation.key(), Types.VARCHAR)
                .addValue("hash", operation == null ? null : operation.requestHash(), Types.BINARY)
                .addValue(
                        "result",
                        operation == null ? null : json.writeValueAsString(operation.result()),
                        Types.VARCHAR);
        return jdbc.query(
                        """
                INSERT INTO cane.business_events(id,order_id,payment_id,kind,from_status,to_status,actor,
                    public_label,note,data,source_key,operation_key,request_hash,operation_result)
                VALUES (:id,:order,:payment,:kind,:from,:to,:actor,:label,:note,CAST(:data AS jsonb),:source,
                    :key,:hash,CAST(:result AS jsonb)) RETURNING created_at
                """,
                        parameters,
                        (row, number) -> new Recorded(
                                event.id(),
                                row.getObject("created_at", OffsetDateTime.class)
                                        .toInstant()))
                .getFirst();
    }

    private static IllegalStateException invalidSource() {
        return new IllegalStateException("Business event source is inconsistent");
    }

    private void checkFact(ObjectNode facts, String name, Object expected) {
        JsonNode actual = facts.get(name);
        if (actual == null) return; // Completeness per event/template belongs to its business caller.
        JsonNode wanted = json.valueToTree(expected);
        boolean matches = actual.isIntegralNumber() && wanted.isIntegralNumber()
                ? actual.bigIntegerValue().equals(wanted.bigIntegerValue())
                : actual.equals(wanted);
        if (!matches) throw invalidSource();
    }

    private record PaymentSource(UUID orderId) {}

    public record Recorded(UUID id, Instant createdAt) {}
}
