package vn.cane.admin.application;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** One operation's settings snapshot, including private bank data. Never return this as an API DTO. */
public record CheckoutSettings(
        boolean accepting,
        List<String> paymentMethods,
        List<Zone> zones,
        Long feeVnd,
        Long freeThresholdVnd,
        int maxQty,
        JsonNode store,
        JsonNode bank,
        String bankMode,
        int holdMinutes,
        int linkDays,
        JsonNode ownerNotify) {
    public static CheckoutSettings from(Map<String, JsonNode> values) {
        return new CheckoutSettings(
                get(values, "accepting_orders").asBoolean(),
                get(values, "enabled_payment_methods")
                        .valueStream()
                        .map(JsonNode::asString)
                        .toList(),
                get(values, "delivery_zones")
                        .valueStream()
                        .map(z -> new Zone(
                                text(z, "wardCode"),
                                text(z, "name"),
                                z.path("allowNear").asBoolean()))
                        .toList(),
                amount(get(values, "shipping_fee_vnd")),
                amount(get(values, "free_ship_threshold_vnd")),
                get(values, "max_qty_per_sku").asInt(),
                get(values, "store"),
                get(values, "bank_details"),
                get(values, "bank_auto_confirm").asBoolean() ? "auto" : "manual",
                get(values, "bank_hold_min").asInt(),
                get(values, "link_ttl_days").asInt(),
                get(values, "owner_notify"));
    }

    private static JsonNode get(Map<String, JsonNode> values, String key) {
        var node = values.get(key);
        if (node == null) throw new IllegalStateException("Required checkout setting is missing");
        return node;
    }

    public boolean pickupEnabled() {
        return complete(store, List.of("name", "address", "openingHours", "pickupGuidance"));
    }

    public boolean bankEnabled() {
        return complete(bank, List.of("bankCode", "bankName", "accountNo", "accountName"));
    }

    private static boolean complete(JsonNode node, List<String> keys) {
        return keys.stream()
                .allMatch(k -> text(node, k) != null && !text(node, k).isBlank());
    }

    public static String text(JsonNode node, String key) {
        var value = node.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Long amount(JsonNode node) {
        return node.isNull() ? null : node.asLong();
    }

    public record Zone(String wardCode, String name, boolean allowNear) {}

    @Override
    public String toString() {
        return "CheckoutSettings[redacted]";
    }
}
