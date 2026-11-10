package vn.cane.admin.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.validation.NumericInputs;
import vn.cane.support.validation.TimeInputs;

/** Validates values only; the settings use case must lock/read all 16 rows before merging. */
public final class SettingsValidator {
    public static final Set<String> KEYS = Set.of(
            "store",
            "accepting_orders",
            "enabled_payment_methods",
            "shipping_fee_vnd",
            "free_ship_threshold_vnd",
            "delivery_zones",
            "bank_details",
            "bank_auto_confirm",
            "operator_can_confirm_bank",
            "support",
            "owner_notify",
            "low_stock_threshold",
            "max_qty_per_sku",
            "bank_hold_min",
            "link_ttl_days",
            "stale_alert_hours");
    private final JsonMapper mapper;
    private final InputNormalizer normalizer;
    private final Clock clock;

    public SettingsValidator(JsonMapper mapper, InputNormalizer normalizer, Clock clock) {
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.clock = clock;
    }

    public ObjectNode normalizePatch(JsonNode values) {
        ObjectNode input = JsonInputs.object(values, KEYS, "values");
        if (input.isEmpty()) {
            throw InputValidationException.invalid("values", "Cần ít nhất một khóa cấu hình.");
        }
        ObjectNode result = mapper.createObjectNode();
        input.properties().forEach(entry -> result.set(entry.getKey(), normalize(entry.getKey(), entry.getValue())));
        return result;
    }

    public ObjectNode validateAndMerge(JsonNode currentValues, JsonNode values) {
        ObjectNode patch = normalizePatch(values);
        if (!(currentValues instanceof ObjectNode current)
                || !Set.copyOf(current.propertyNames()).equals(KEYS)) {
            throw new IllegalStateException("Stored settings are incomplete");
        }
        ObjectNode merged = current.deepCopy();
        patch.properties()
                .forEach(entry -> merged.set(entry.getKey(), entry.getValue().deepCopy()));
        for (String key : KEYS) {
            try {
                normalize(key, merged.get(key));
            } catch (InputValidationException invalidStoredValue) {
                // No values/parser details in an infrastructure/invariant error.
                throw new IllegalStateException("Stored settings are invalid");
            }
        }
        if (contains(merged.get("enabled_payment_methods"), "bank_transfer")
                && merged.get("bank_details").isNull()) {
            throw InputValidationException.invalid(Map.of(
                    "values.enabled_payment_methods", "Bật chuyển khoản cần thông tin tài khoản ngân hàng.",
                    "values.bank_details", "Cần thông tin tài khoản khi bật chuyển khoản."));
        }
        return merged;
    }

    private JsonNode normalize(String key, JsonNode value) {
        String field = "values." + key;
        return switch (key) {
            case "accepting_orders", "operator_can_confirm_bank" -> mapper.valueToTree(JsonInputs.bool(value, field));
            case "bank_auto_confirm" -> {
                if (JsonInputs.bool(value, field)) {
                    throw InputValidationException.invalid(
                            field, "Nhánh ngân hàng tự động chưa được triển khai/nghiệm thu.");
                }
                yield mapper.valueToTree(false);
            }
            case "shipping_fee_vnd", "free_ship_threshold_vnd" ->
                value.isNull()
                        ? value.deepCopy()
                        : mapper.valueToTree(NumericInputs.vnd(JsonInputs.integer(value, field), false, field));
            case "low_stock_threshold", "max_qty_per_sku", "bank_hold_min", "link_ttl_days", "stale_alert_hours" ->
                integerSetting(key, value, field);
            case "enabled_payment_methods" -> paymentMethods(value, field);
            case "delivery_zones" -> deliveryZones(value, field);
            case "store" -> store(value, field);
            case "bank_details" -> bank(value, field);
            case "support" -> support(value, field);
            case "owner_notify" -> ownerNotify(value, field);
            default -> throw InputValidationException.malformed("values");
        };
    }

    private JsonNode integerSetting(String key, JsonNode value, String field) {
        long number = NumericInputs.integer(
                JsonInputs.integer(value, field), key.equals("low_stock_threshold") ? 0 : 1, Integer.MAX_VALUE, field);
        ChronoUnit unit = switch (key) {
            case "bank_hold_min" -> ChronoUnit.MINUTES;
            case "link_ttl_days" -> ChronoUnit.DAYS;
            case "stale_alert_hours" -> ChronoUnit.HOURS;
            default -> null;
        };
        if (unit != null) {
            TimeInputs.expiry(clock.instant(), number, unit, field);
        }
        return mapper.valueToTree(number);
    }

    private ArrayNode paymentMethods(JsonNode value, String field) {
        ArrayNode input = JsonInputs.array(value, field);
        var unique = new HashSet<String>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : input) {
            String method = JsonInputs.text(item, field);
            if (!Set.of("cod", "bank_transfer").contains(method) || !unique.add(method)) {
                throw InputValidationException.invalid(field, "Phương thức thanh toán lạ hoặc bị trùng.");
            }
            result.add(method);
        }
        return result;
    }

    private ArrayNode deliveryZones(JsonNode value, String field) {
        ArrayNode input = JsonInputs.array(value, field);
        ArrayNode result = mapper.createArrayNode();
        var codes = new HashSet<String>();
        for (int index = 0; index < input.size(); index++) {
            String path = field + "[" + index + "]";
            ObjectNode zone =
                    JsonInputs.completeObject(input.get(index), Set.of("wardCode", "name", "allowNear"), path);
            String code = text(zone, "wardCode", 100, path);
            if (!codes.add(code)) {
                throw InputValidationException.invalid(path + ".wardCode", "Mã vùng không được trùng.");
            }
            result.add(mapper.createObjectNode()
                    .put("wardCode", code)
                    .put("name", text(zone, "name", 100, path))
                    .put("allowNear", JsonInputs.bool(zone.get("allowNear"), path + ".allowNear")));
        }
        return result;
    }

    private JsonNode store(JsonNode value, String field) {
        if (value.isNull()) {
            return value.deepCopy();
        }
        ObjectNode store =
                JsonInputs.completeObject(value, Set.of("name", "address", "openingHours", "pickupGuidance"), field);
        return mapper.createObjectNode()
                .put("name", text(store, "name", 100, field))
                .put("address", text(store, "address", 500, field))
                .put("openingHours", text(store, "openingHours", 1000, field))
                .put("pickupGuidance", text(store, "pickupGuidance", 1000, field));
    }

    private JsonNode bank(JsonNode value, String field) {
        if (value.isNull()) {
            return value.deepCopy();
        }
        ObjectNode bank =
                JsonInputs.completeObject(value, Set.of("bankCode", "bankName", "accountNo", "accountName"), field);
        String account =
                JsonInputs.text(bank.get("accountNo"), field + ".accountNo").replaceAll("\\s", "");
        return mapper.createObjectNode()
                .put("bankCode", text(bank, "bankCode", 100, field))
                .put("bankName", text(bank, "bankName", 100, field))
                .put("accountNo", InputNormalizer.requiredText(account, 100, field + ".accountNo"))
                .put("accountName", text(bank, "accountName", 100, field));
    }

    private JsonNode support(JsonNode value, String field) {
        if (value.isNull()) {
            return value.deepCopy();
        }
        ObjectNode support = JsonInputs.completeObject(value, Set.of("phone", "zaloUrl"), field);
        ObjectNode result = mapper.createObjectNode()
                .put(
                        "phone",
                        InputNormalizer.phone(
                                JsonInputs.text(support.get("phone"), field + ".phone"), field + ".phone"));
        JsonNode url = support.get("zaloUrl");
        if (url.isNull()) {
            result.putNull("zaloUrl");
        } else {
            String text = JsonInputs.text(url, field + ".zaloUrl").strip();
            try {
                URI uri = new URI(text);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    throw new URISyntaxException("", "HTTPS URL required");
                }
            } catch (URISyntaxException ignored) {
                throw InputValidationException.invalid(field + ".zaloUrl", "Cần URL HTTPS hợp lệ.");
            }
            result.put("zaloUrl", text);
        }
        return result;
    }

    private JsonNode ownerNotify(JsonNode value, String field) {
        if (value.isNull()) {
            return value.deepCopy();
        }
        ObjectNode notify = JsonInputs.completeObject(value, Set.of("email", "telegramChatId"), field);
        if (notify.get("email").isNull() && notify.get("telegramChatId").isNull()) {
            throw InputValidationException.invalid(field, "Cần ít nhất một kênh nhận thông báo.");
        }
        ObjectNode result = mapper.createObjectNode();
        result.set(
                "email",
                notify.get("email").isNull()
                        ? mapper.nullNode()
                        : mapper.valueToTree(normalizer.email(
                                JsonInputs.text(notify.get("email"), field + ".email"), field + ".email")));
        result.set(
                "telegramChatId",
                notify.get("telegramChatId").isNull()
                        ? mapper.nullNode()
                        : mapper.valueToTree(InputNormalizer.requiredText(
                                JsonInputs.text(notify.get("telegramChatId"), field + ".telegramChatId"),
                                Integer.MAX_VALUE,
                                field + ".telegramChatId")));
        return result;
    }

    private static String text(ObjectNode object, String key, int maximum, String parent) {
        return InputNormalizer.requiredText(
                JsonInputs.text(object.get(key), parent + "." + key), maximum, parent + "." + key);
    }

    private static boolean contains(JsonNode array, String expected) {
        for (JsonNode value : array) {
            if (expected.equals(value.asString())) {
                return true;
            }
        }
        return false;
    }
}
