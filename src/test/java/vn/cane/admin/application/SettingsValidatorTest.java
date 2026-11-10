package vn.cane.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

class SettingsValidatorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SettingsValidator validator = new SettingsValidator(
            mapper,
            new InputNormalizer(Validation.buildDefaultValidatorFactory().getValidator()),
            Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void preparationValuesAreValidAndPartialUpdatesPreserveOmittedValuesAndInputs() {
        ObjectNode current = defaults();
        JsonNode before = current.deepCopy();
        JsonNode patch = json("{\"operator_can_confirm_bank\":true}");
        ObjectNode result = validator.validateAndMerge(current, patch);
        assertThat(result.size()).isEqualTo(16);
        assertThat(result.get("operator_can_confirm_bank").asBoolean()).isTrue();
        result.put("operator_can_confirm_bank", false);
        assertThat(result).isEqualTo(before);
        assertThat(current).isEqualTo(before);
        assertThat(patch).isEqualTo(json("{\"operator_can_confirm_bank\":true}"));
        assertThat(validator.normalizePatch(current).toString()).isEqualTo(current.toString());
    }

    @Test
    void normalizesNestedValuesWithoutLosingLeadingZeroOrCaseSensitiveBankCode() {
        var patch = json("""
                {"bank_details":{"bankCode":" bank-code ","bankName":" Bank ",
                  "accountNo":" 00 12\t34 ","accountName":" Owner "},
                 "enabled_payment_methods":["cod","bank_transfer"],
                 "store":{"name":" Shop ","address":" Address ","openingHours":" 08-17 ","pickupGuidance":" Call "},
                 "support":{"phone":" +84 (901) 234-567 ","zaloUrl":" https://zalo.me/example "},
                 "owner_notify":{"email":" OWNER@EXAMPLE.COM ","telegramChatId":" -100123 "},
                 "delivery_zones":[{"wardCode":" TEST-01 ","name":" Zone ","allowNear":false}]}
                """.replace("\t", "\\t"));
        var result = validator.validateAndMerge(defaults(), patch);
        assertThat(result.at("/bank_details/accountNo").asString()).isEqualTo("001234");
        assertThat(result.at("/bank_details/bankCode").asString()).isEqualTo("bank-code");
        assertThat(result.at("/store/name").asString()).isEqualTo("Shop");
        assertThat(result.at("/support/phone").asString()).isEqualTo("+84901234567");
        assertThat(result.at("/support/zaloUrl").asString()).isEqualTo("https://zalo.me/example");
        assertThat(result.at("/owner_notify/email").asString()).isEqualTo("owner@example.com");
        assertThat(result.at("/owner_notify/telegramChatId").asString()).isEqualTo("-100123");
        assertThat(result.at("/delivery_zones/0/wardCode").asString()).isEqualTo("TEST-01");
    }

    @Test
    void rejectsMalformedTypesUnknownKeysAndIncompleteNestedObjects() {
        for (String patch : new String[] {
            "[]",
            "null",
            "{\"other\":true}",
            "{\"accepting_orders\":1}",
            "{\"shipping_fee_vnd\":1.1}",
            "{\"max_qty_per_sku\":\"3\"}",
            "{\"store\":{\"name\":\"Only one\"}}",
            "{\"delivery_zones\":[{\"wardCode\":\"A\",\"name\":\"A\",\"allowNear\":true,\"extra\":1}]}"
        }) {
            assertThatThrownBy(() -> validator.normalizePatch(json(patch)))
                    .isInstanceOfSatisfying(
                            InputValidationException.class,
                            error -> assertThat(error.isMalformed()).isTrue());
        }
    }

    @Test
    void rejectsInvalidDomainsDuplicatesAndManualModeAutoConfirmation() {
        for (String patch : new String[] {
            "{}",
            "{\"shipping_fee_vnd\":-1}",
            "{\"free_ship_threshold_vnd\":9007199254740992}",
            "{\"low_stock_threshold\":-1}",
            "{\"max_qty_per_sku\":0}",
            "{\"bank_hold_min\":2147483648}",
            "{\"link_ttl_days\":2147483647}",
            "{\"stale_alert_hours\":0}",
            "{\"bank_auto_confirm\":true}",
            "{\"enabled_payment_methods\":[\"cod\",\"cod\"]}",
            "{\"enabled_payment_methods\":[\"cash\"]}",
            "{\"delivery_zones\":[{\"wardCode\":\"A\",\"name\":\"A\",\"allowNear\":true},{\"wardCode\":\" A \",\"name\":\"B\",\"allowNear\":false}]}",
            "{\"owner_notify\":{\"email\":null,\"telegramChatId\":null}}",
            "{\"owner_notify\":{\"email\":\"invalid\",\"telegramChatId\":null}}",
            "{\"support\":{\"phone\":\"0901234567\",\"zaloUrl\":\"http://example.com\"}}",
            "{\"support\":{\"phone\":\"0901234567\",\"zaloUrl\":\"https:missing-host\"}}",
            "{\"store\":{\"name\":\" \",\"address\":\"A\",\"openingHours\":\"B\",\"pickupGuidance\":\"C\"}}"
        }) {
            assertThatThrownBy(() -> validator.normalizePatch(json(patch)))
                    .as(patch)
                    .isInstanceOfSatisfying(
                            InputValidationException.class,
                            error -> assertThat(error.isMalformed()).isFalse());
        }
    }

    @Test
    void checksBankRequirementsAgainstMergedStateInBothDirections() {
        assertThatThrownBy(() -> validator.validateAndMerge(
                        defaults(), json("{\"enabled_payment_methods\":[\"bank_transfer\"]}")))
                .isInstanceOfSatisfying(
                        InputValidationException.class,
                        error -> assertThat(error.fieldErrors())
                                .containsOnlyKeys("values.enabled_payment_methods", "values.bank_details"));
        var current = validator.validateAndMerge(defaults(), json("""
                {"enabled_payment_methods":["bank_transfer"],"bank_details":
                 {"bankCode":"B","bankName":"Bank","accountNo":"001","accountName":"Owner"}}
                """));
        assertThatThrownBy(() -> validator.validateAndMerge(current, json("{\"bank_details\":null}")))
                .isInstanceOf(InputValidationException.class);
        var cleared =
                validator.validateAndMerge(current, json("{\"bank_details\":null,\"enabled_payment_methods\":[]}"));
        assertThat(cleared.get("bank_details").isNull()).isTrue();
    }

    @Test
    void acceptsBoundaryValuesAndRejectsOverlongNestedTextWithoutTruncation() {
        var result = validator.normalizePatch(json("""
                {"shipping_fee_vnd":0,"free_ship_threshold_vnd":9007199254740991,
                 "low_stock_threshold":2147483647,"max_qty_per_sku":2147483647,
                 "bank_hold_min":2147483647,"stale_alert_hours":2147483647,"link_ttl_days":1}
                """));
        assertThat(result.get("free_ship_threshold_vnd").asLong()).isEqualTo(9_007_199_254_740_991L);
        ObjectNode store = mapper.createObjectNode()
                .put("name", "n".repeat(100))
                .put("address", "a".repeat(500))
                .put("openingHours", "h".repeat(1000))
                .put("pickupGuidance", "p".repeat(1000));
        ObjectNode patch = mapper.createObjectNode().set("store", store);
        assertThat(validator.normalizePatch(patch)).isEqualTo(patch);
        store.put("pickupGuidance", "p".repeat(1001));
        assertThatThrownBy(() -> validator.normalizePatch(patch))
                .isInstanceOfSatisfying(
                        InputValidationException.class,
                        error -> assertThat(error.fieldErrors()).containsOnlyKeys("values.store.pickupGuidance"));
    }

    @Test
    void incompleteOrInvalidStoredStateIsAnInvariantFailureWithoutEchoingValues() {
        var current = defaults();
        current.remove("store");
        assertThatThrownBy(() -> validator.validateAndMerge(current, json("{\"accepting_orders\":true}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored settings are incomplete");
        var invalid = defaults().put("bank_hold_min", "PRIVATE-STORED-VALUE");
        assertThatThrownBy(() -> validator.validateAndMerge(invalid, json("{\"accepting_orders\":true}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored settings are invalid")
                .hasNoCause();
    }

    private ObjectNode defaults() {
        return (ObjectNode) json("""
                {"store":null,"accepting_orders":true,"enabled_payment_methods":[],
                 "shipping_fee_vnd":null,"free_ship_threshold_vnd":null,"delivery_zones":[],
                 "bank_details":null,"bank_auto_confirm":false,"operator_can_confirm_bank":false,
                 "support":null,"owner_notify":null,"low_stock_threshold":5,"max_qty_per_sku":99,
                 "bank_hold_min":60,"link_ttl_days":90,"stale_alert_hours":24}
                """);
    }

    private JsonNode json(String input) {
        return mapper.readTree(input);
    }
}
