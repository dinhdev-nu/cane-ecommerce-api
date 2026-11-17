package vn.cane.catalog.application;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.support.idempotency.CanonicalOperation;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.validation.NumericInputs;

public record StockAdjustment(
        String sku,
        String kind,
        String mode,
        int value,
        Integer expectedStockQty,
        String reason,
        CanonicalOperation canonical) {
    public static StockAdjustment parse(JsonNode input, String key, JsonMapper json) {
        var body =
                JsonInputs.object(input, Set.of("sku", "kind", "mode", "value", "expectedStockQty", "reason"), "body");
        String sku = InputNormalizer.requiredText(JsonInputs.text(body.get("sku"), "sku"), 100, "sku");
        String kind = JsonInputs.text(body.get("kind"), "kind"), mode = JsonInputs.text(body.get("mode"), "mode");
        if (!Set.of("restock", "adjust").contains(kind))
            throw InputValidationException.invalid("kind", "Cần restock hoặc adjust.");
        if (!Set.of("delta", "set").contains(mode) || kind.equals("restock") && !mode.equals("delta"))
            throw InputValidationException.invalid("mode", "Cách điều chỉnh không hợp lệ.");
        int minimum = kind.equals("restock") ? 1 : mode.equals("set") ? 0 : Integer.MIN_VALUE;
        int value = (int) NumericInputs.integer(
                JsonInputs.integer(body.get("value"), "value"), minimum, Integer.MAX_VALUE, "value");
        Integer expected = null;
        var old = body.get("expectedStockQty");
        if (mode.equals("set"))
            expected = (int) NumericInputs.integer(
                    JsonInputs.integer(old, "expectedStockQty"), 0, Integer.MAX_VALUE, "expectedStockQty");
        else if (old != null && !old.isNull())
            throw InputValidationException.invalid("expectedStockQty", "Delta không nhận tồn kỳ vọng.");
        String reason = InputNormalizer.requiredText(JsonInputs.text(body.get("reason"), "reason"), 1000, "reason");
        var normalized = json.createObjectNode()
                .put("sku", sku)
                .put("kind", kind)
                .put("mode", mode)
                .put("value", value)
                .put("reason", reason);
        if (expected == null) normalized.putNull("expectedStockQty");
        else normalized.put("expectedStockQty", expected);
        return new StockAdjustment(
                sku,
                kind,
                mode,
                value,
                expected,
                reason,
                CanonicalOperation.adminPost("POST", "/api/v1/admin/stock-adjustments", normalized, key));
    }
}
