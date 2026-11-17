package vn.cane.orders.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.support.idempotency.CanonicalOperation;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.validation.NumericInputs;

public record OrderLifecycleCommand(
        UUID orderId,
        Action action,
        String expectedStatus,
        String note,
        String reason,
        Boolean clear,
        Long cashCollectedVnd,
        List<Restock> restockItems,
        CanonicalOperation canonical) {
    public enum Action {
        PREPARE("prepare"),
        SHIP("ship"),
        READY("ready-for-pickup"),
        HAND_OVER("hand-over"),
        DELIVER("deliver"),
        ISSUE("delivery-issue"),
        CLOSE_ISSUE("close-delivery-issue"),
        CANCEL("cancel");
        private final String route;

        Action(String route) {
            this.route = route;
        }

        public String route() {
            return route;
        }
    }

    public record Restock(String sku, int quantity, String note) {}

    public OrderLifecycleCommand {
        restockItems = List.copyOf(restockItems);
    }

    public static OrderLifecycleCommand parse(UUID id, Action action, JsonNode input, String key, JsonMapper json) {
        var allowed = new HashSet<>(Set.of("expectedStatus"));
        switch (action) {
            case CANCEL -> allowed.add("reason");
            case ISSUE -> allowed.addAll(Set.of("clear", "reason"));
            case CLOSE_ISSUE -> allowed.addAll(Set.of("goodsResolved", "reason", "restockItems"));
            case HAND_OVER -> allowed.addAll(Set.of("note", "cashCollectedVnd"));
            default -> allowed.add("note");
        }
        var body = JsonInputs.object(input, allowed, "body");
        String expected = JsonInputs.text(body.get("expectedStatus"), "expectedStatus");
        Set<String> states = switch (action) {
            case PREPARE -> Set.of("confirmed");
            case SHIP, READY -> Set.of("preparing");
            case HAND_OVER -> Set.of("ready_for_pickup");
            case DELIVER, ISSUE, CLOSE_ISSUE -> Set.of("shipping");
            case CANCEL -> Set.of("awaiting_payment", "confirmed", "preparing", "ready_for_pickup");
        };
        if (!states.contains(expected))
            throw InputValidationException.invalid("expectedStatus", "Invalid expected status.");
        var normalized = json.createObjectNode().put("expectedStatus", expected);
        String note = null, reason = null;
        Boolean clear = null;
        Long cash = null;
        var items = new ArrayList<Restock>();
        if (allowed.contains("note")) {
            note = note(body.get("note"), "note");
            normalized.put("note", note);
        }
        if (allowed.contains("reason")) {
            reason = note(body.get("reason"), "reason");
            if (action != Action.ISSUE && reason == null)
                throw InputValidationException.invalid("reason", "Cần lý do.");
            normalized.put("reason", reason);
        }
        if (action == Action.ISSUE) {
            clear = JsonInputs.bool(body.get("clear"), "clear");
            if (clear ? reason != null : reason == null)
                throw InputValidationException.invalid("reason", "Lý do không khớp thao tác cờ giao lỗi.");
            normalized.put("clear", clear);
        }
        if (action == Action.HAND_OVER) {
            var value = body.get("cashCollectedVnd");
            if (value != null && !value.isNull())
                cash = NumericInputs.vnd(JsonInputs.integer(value, "cashCollectedVnd"), false, "cashCollectedVnd");
            if (cash == null) normalized.putNull("cashCollectedVnd");
            else normalized.put("cashCollectedVnd", cash);
        }
        if (action == Action.CLOSE_ISSUE) {
            if (!JsonInputs.bool(body.get("goodsResolved"), "goodsResolved"))
                throw InputValidationException.invalid("goodsResolved", "Cần xác nhận đã kiểm hàng.");
            normalized.put("goodsResolved", true);
            var rows = JsonInputs.array(body.get("restockItems"), "restockItems");
            if (rows.isEmpty() || rows.size() > 9)
                throw InputValidationException.invalid("restockItems", "Cần đủ từng SKU của đơn.");
            var seen = new HashSet<String>();
            var output = normalized.putArray("restockItems");
            for (int i = 0; i < rows.size(); i++) {
                String path = "restockItems[" + i + "]";
                var row = JsonInputs.object(rows.get(i), Set.of("sku", "quantity", "note"), path);
                String sku = InputNormalizer.requiredText(
                        JsonInputs.text(row.get("sku"), path + ".sku"), 100, path + ".sku");
                if (!seen.add(sku)) throw InputValidationException.invalid(path + ".sku", "SKU bị lặp.");
                int qty = (int) NumericInputs.integer(
                        JsonInputs.integer(row.get("quantity"), path + ".quantity"),
                        0,
                        Integer.MAX_VALUE,
                        path + ".quantity");
                String itemNote = note(row.get("note"), path + ".note");
                items.add(new Restock(sku, qty, itemNote));
                output.addObject().put("sku", sku).put("quantity", qty).put("note", itemNote);
            }
        }
        return new OrderLifecycleCommand(
                id,
                action,
                expected,
                note,
                reason,
                clear,
                cash,
                items,
                CanonicalOperation.adminPost(
                        "POST", "/api/v1/admin/orders/" + id + "/" + action.route(), normalized, key));
    }

    private static String note(JsonNode value, String field) {
        return InputNormalizer.optionalNote(
                value == null || value.isNull() ? null : JsonInputs.text(value, field), field);
    }
}
