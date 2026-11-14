package vn.cane.support.validation;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Persistence allowlists, not request DTOs. Business services select facts explicitly. */
public final class AuditJson {
    private static final Set<String> FACTS = Set.of(
            "orderId",
            "orderNumber",
            "status",
            "paymentStatus",
            "paymentId",
            "amountVnd",
            "method",
            "reference",
            "isPrincipal",
            "orderLinkVerified",
            "action",
            "reviewedBy",
            "reviewedAt",
            "refundedAt",
            "cancelKind",
            "goodsResolved",
            "restockItems",
            "sku",
            "kind",
            "stockBefore",
            "stockAfter",
            "delta",
            "movementId",
            "changedKeys",
            "linkVersion",
            "linkExpiresAt",
            "alertType",
            "objectId",
            "date",
            "failedOutboxId");
    private static final Set<String> RESULTS = Set.of(
            "orderId",
            "status",
            "paymentStatus",
            "eventId",
            "paymentId",
            "cancelKind",
            "restockItems",
            "sku",
            "kind",
            "stockBefore",
            "stockAfter",
            "delta",
            "movementId",
            "linkVersion",
            "linkExpiresAt",
            "queued");
    private static final Set<String> RESTOCK_ITEM = Set.of("sku", "quantity", "note");

    private AuditJson() {}

    public static ObjectNode facts(JsonNode input) {
        return copy(input, FACTS);
    }

    public static ObjectNode result(JsonNode input) {
        return copy(input, RESULTS);
    }

    private static ObjectNode copy(JsonNode input, Set<String> allowed) {
        if (!(input instanceof ObjectNode object) || !allowed.containsAll(object.propertyNames())) throw rejected();
        for (String field : object.propertyNames()) {
            JsonNode value = object.get(field);
            if (field.equals("restockItems")) {
                if (!value.isArray() || value.size() > 9) throw rejected();
                for (JsonNode item : value) {
                    if (!(item instanceof ObjectNode row)
                            || !RESTOCK_ITEM.containsAll(row.propertyNames())
                            || !row.has("sku")
                            || !row.has("quantity")) throw rejected();
                    for (JsonNode cell : row) if (!scalar(cell)) throw rejected();
                }
            } else if (field.equals("changedKeys")) {
                if (!value.isArray()) throw rejected();
                for (JsonNode key : value) if (!key.isString()) throw rejected();
            } else if (!scalar(value)) throw rejected();
        }
        return object.deepCopy();
    }

    private static boolean scalar(JsonNode value) {
        return value.isNull() || value.isString() || value.isBoolean() || value.isIntegralNumber();
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("Audit JSON does not match its persistence allowlist");
    }
}
