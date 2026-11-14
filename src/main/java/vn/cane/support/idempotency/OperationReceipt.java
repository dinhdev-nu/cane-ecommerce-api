package vn.cane.support.idempotency;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.AuditJson;

/** HTTP 200 allowlist for both first execution and replay. No request or current entity snapshot. */
public record OperationReceipt(UUID operationId, boolean replayed, ObjectNode result) {
    public OperationReceipt {
        Objects.requireNonNull(operationId);
        result = AuditJson.result(result);
    }

    @Override
    public ObjectNode result() {
        return result.deepCopy();
    }

    @Override
    public String toString() {
        return "OperationReceipt[redacted]";
    }
}
