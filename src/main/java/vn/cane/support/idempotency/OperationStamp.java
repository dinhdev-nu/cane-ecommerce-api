package vn.cane.support.idempotency;

import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.AuditJson;
import vn.cane.support.validation.InputNormalizer;

/** Attached to exactly one event by the application transaction that completed the operation. */
public record OperationStamp(String key, byte[] requestHash, ObjectNode result) {
    public OperationStamp {
        key = InputNormalizer.idempotencyKey(key);
        if (requestHash == null || requestHash.length != 32)
            throw new IllegalArgumentException("Operation hash must be SHA-256");
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
        return "OperationStamp[redacted]";
    }
}
