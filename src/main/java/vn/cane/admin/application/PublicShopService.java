package vn.cane.admin.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public final class PublicShopService {
    private final SettingsReader settings;

    public PublicShopService(SettingsReader settings) {
        this.settings = settings;
    }

    public Shop read() {
        var values = settings.read();
        var store = required(values, "store");
        var support = required(values, "support");
        var bank = required(values, "bank_details");
        var methods = new ArrayList<String>();
        for (var method : required(values, "enabled_payment_methods")) {
            String name = method.asString();
            if (name.equals("cod") || name.equals("bank_transfer") && completeBank(bank)) methods.add(name);
        }
        var zones = deliveryZones(required(values, "delivery_zones"));
        Long fee = nullableLong(required(values, "shipping_fee_vnd"));
        return new Shop(
                text(store, "name"),
                text(store, "address"),
                text(store, "openingHours"),
                text(store, "pickupGuidance"),
                required(values, "accepting_orders").asBoolean(),
                List.copyOf(methods),
                required(values, "bank_auto_confirm").asBoolean() ? "auto" : "manual",
                fee != null && !zones.isEmpty(),
                fee,
                nullableLong(required(values, "free_ship_threshold_vnd")),
                required(values, "max_qty_per_sku").asInt(),
                List.copyOf(zones),
                support.isNull() ? null : new Support(text(support, "phone"), text(support, "zaloUrl")));
    }

    private static JsonNode required(Map<String, JsonNode> values, String key) {
        var value = values.get(key);
        if (value == null) throw new IllegalStateException("Required setting is missing");
        return value;
    }

    private static String text(JsonNode object, String key) {
        var value = object.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Long nullableLong(JsonNode value) {
        return value.isNull() ? null : value.asLong();
    }

    private static boolean completeBank(JsonNode bank) {
        return List.of("bankCode", "bankName", "accountNo", "accountName").stream()
                .allMatch(key -> text(bank, key) != null && !text(bank, key).isBlank());
    }

    private static List<DeliveryZone> deliveryZones(JsonNode value) {
        var result = new ArrayList<DeliveryZone>();
        var codes = new java.util.HashSet<String>();
        if (!value.isArray()) return List.of();
        for (var zone : value) {
            String code = text(zone, "wardCode");
            String name = text(zone, "name");
            var allowNear = zone.get("allowNear");
            if (code == null
                    || code.isBlank()
                    || name == null
                    || name.isBlank()
                    || allowNear == null
                    || !allowNear.isBoolean()
                    || !codes.add(code)) return List.of();
            result.add(new DeliveryZone(code, name, allowNear.asBoolean()));
        }
        return List.copyOf(result);
    }

    public record Shop(
            String storeName,
            String address,
            String openingHours,
            String pickupGuidance,
            boolean acceptingOrders,
            List<String> enabledPaymentMethods,
            String bankConfirmationMode,
            boolean deliveryEnabled,
            Long shippingFeeVnd,
            Long freeShipThresholdVnd,
            int maxQtyPerSku,
            List<DeliveryZone> deliveryZones,
            Support support) {}

    public record DeliveryZone(String wardCode, String name, boolean allowNear) {}

    public record Support(String phone, String zaloUrl) {}
}
