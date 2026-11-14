package vn.cane.support.idempotency;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.AuditJson;
import vn.cane.support.validation.InputNormalizer;

/** Read only; authorization and actor/key locking belong to the calling admin operation. */
@Repository
public class OperationReceipts {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public OperationReceipts(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Stored> find(UUID actor, String key) {
        return jdbc
                .query(
                        """
                SELECT id,request_hash,operation_result FROM cane.business_events
                WHERE actor=:actor AND operation_key=:key
                """,
                        Map.of("actor", actor.toString(), "key", InputNormalizer.idempotencyKey(key)),
                        (row, number) -> new Stored(
                                row.getObject("id", UUID.class),
                                row.getBytes("request_hash"),
                                AuditJson.result(json.readTree(row.getString("operation_result")))))
                .stream()
                .findFirst();
    }

    public record Stored(UUID operationId, byte[] requestHash, ObjectNode result) {
        public Stored {
            requestHash = requestHash.clone();
            result = AuditJson.result(result);
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }

        @Override
        public ObjectNode result() {
            return result.deepCopy();
        }

        @Override
        public String toString() {
            return "StoredOperation[redacted]";
        }
    }
}
