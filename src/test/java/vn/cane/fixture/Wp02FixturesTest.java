package vn.cane.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.SettingsValidator;
import vn.cane.catalog.domain.CatalogSkus;
import vn.cane.catalog.infrastructure.cli.CatalogManifestReader;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.NumericInputs;

/** Checks preparation data, not an implementation or acceptance test for the future public APIs. */
class Wp02FixturesTest {
    private static final Path RESOURCES = Path.of("src/test/resources");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> SHOP_FIELDS = Set.of(
            "storeName",
            "address",
            "openingHours",
            "pickupGuidance",
            "acceptingOrders",
            "enabledPaymentMethods",
            "bankConfirmationMode",
            "deliveryEnabled",
            "shippingFeeVnd",
            "freeShipThresholdVnd",
            "maxQtyPerSku",
            "deliveryZones",
            "support");

    @Test
    void approvedUnitsAndResearchedPricesKeepUnconfirmedReleaseFlagsExplicit() throws Exception {
        JsonNode release = JSON.readTree(Files.readAllBytes(Path.of("scripts/catalog/catalog-template.json")));
        var fixture = CatalogManifestReader.read(RESOURCES.resolve("catalog/wp02/seed-fixture.json"), "test");
        var products =
                fixture.products().stream().collect(Collectors.toMap(product -> product.sku(), product -> product));
        Map<String, String> units = Map.of(
                "cane-juice-bottle", "Bottle 300 ml",
                "cane-juice-cup", "Cup 500 ml (with ice)",
                "cane-tea", "Pouch 100 g",
                "cane-candy", "Pouch 150 g",
                "cane-syrup", "Bottle 330 ml",
                "cane-molasses", "Jar 450 g",
                "raw-cane-sugar", "Pouch 500 g",
                "whole-cane-sugar", "Pouch 500 g",
                "cane-vinegar", "Bottle 330 ml");
        assertThat(release.get("dataKind").asString()).isEqualTo("release");
        assertThat(release.get("products")
                        .valueStream()
                        .map(row -> row.get("sku").asString())
                        .toList())
                .containsExactlyInAnyOrderElementsOf(CatalogSkus.ALL);
        for (JsonNode row : release.get("products")) {
            String sku = row.get("sku").asString();
            assertThat(row.get("unitLabel").asString()).isEqualTo(units.get(sku));
            assertThat(products.get(sku).unitLabel()).isEqualTo(units.get(sku));
            assertThat(row.get("name").asString())
                    .isNotBlank()
                    .isEqualTo(products.get(sku).name());
            assertThat(row.get("priceVnd").asLong()).isPositive().isLessThanOrEqualTo(NumericInputs.MAX_VND);
            assertThat(row.get("isActive").isNull()).isTrue();
            if (sku.equals(CatalogSkus.JUICE_CUP))
                assertThat(row.get("deliveryScope").asString()).isEqualTo("near");
            else assertThat(row.get("deliveryScope").isNull()).isTrue();
        }
        assertThatThrownBy(() ->
                        CatalogManifestReader.read(Path.of("scripts/catalog/catalog-template.json"), "production"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nineSkuDatasetCoversAvailabilityBoundariesAndPreservesPublicAllowlist() throws Exception {
        var manifest = CatalogManifestReader.read(RESOURCES.resolve("catalog/wp02/seed-fixture.json"), "test");
        var products =
                manifest.products().stream().collect(Collectors.toMap(product -> product.sku(), product -> product));
        JsonNode stock = read("catalog/wp02/stock-fixture.json");
        assertThat(stock.get("dataKind").asString()).isEqualTo("fixture");
        assertThat(stock.get("lowStockThreshold").asInt()).isEqualTo(5);
        assertThat(stock.get("items")).hasSize(9);
        assertThat(stock.get("items")
                        .valueStream()
                        .map(row -> row.get("sku").asString())
                        .toList())
                .containsExactlyInAnyOrderElementsOf(CatalogSkus.ALL);
        assertThat(stock.get("items")
                        .valueStream()
                        .map(row -> row.get("stockQty").asInt())
                        .toList())
                .contains(0, 1, 4, 5, 6);
        Map<String, String> availability = Map.of(
                "cane-juice-bottle",
                "in_stock",
                "cane-juice-cup",
                "low",
                "cane-tea",
                "low",
                "cane-candy",
                "in_stock",
                "cane-syrup",
                "paused",
                "cane-molasses",
                "out",
                "raw-cane-sugar",
                "paused",
                "whole-cane-sugar",
                "in_stock",
                "cane-vinegar",
                "in_stock");
        JsonNode prices = read("public/wp02/prices.json");
        assertThat(prices.propertyNames()).containsExactlyInAnyOrder("serverTime", "items");
        assertThat(prices.get("serverTime").asString()).endsWith("Z");
        assertThat(Instant.parse(prices.get("serverTime").asString())).isEqualTo(Instant.parse("2026-11-14T00:00:00Z"));
        assertThat(prices.get("items")).hasSize(9);
        assertThat(prices.get("items")
                        .valueStream()
                        .map(row -> row.get("sku").asString())
                        .toList())
                .containsExactlyInAnyOrderElementsOf(CatalogSkus.ALL);
        for (JsonNode row : prices.get("items")) {
            assertThat(row.propertyNames())
                    .containsExactlyInAnyOrder("sku", "priceVnd", "availability", "deliveryScope");
            var product = products.get(row.get("sku").asString());
            assertThat(row.get("priceVnd").isIntegralNumber()).isTrue();
            assertThat(row.get("priceVnd").asLong()).isEqualTo(product.priceVnd());
            assertThat(row.get("deliveryScope").asString()).isEqualTo(product.deliveryScope());
            assertThat(row.get("availability").asString()).isEqualTo(availability.get(product.sku()));
        }
        var missing = prices.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) missing.get("items")).remove(8);
        assertThat(read("public/wp02/prices-missing-sku.json")).isEqualTo(missing);
        assertThat(products.get("cane-juice-bottle").priceVnd()).isEqualTo(25_001L);
        assertThat(products.get("whole-cane-sugar").priceVnd()).isEqualTo(NumericInputs.MAX_VND);
        assertThatThrownBy(() -> NumericInputs.multiplyVnd(
                        products.get("whole-cane-sugar").priceVnd(), 2, "total"))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void shopScenariosUseValidSettingsExceptTheExplicitStoredDriftCaseAndExposeOnlyPublicFields() throws Exception {
        JsonNode defaults = read("admin/wp02/settings-default.json");
        assertThat(defaults.propertyNames()).containsExactlyInAnyOrderElementsOf(SettingsValidator.KEYS);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = new SettingsValidator(
                    JSON,
                    new InputNormalizer(factory.getValidator()),
                    Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC));
            assertThat(validator.validateAndMerge(defaults, defaults).toString())
                    .isEqualTo(defaults.toString());
            JsonNode scenarios = read("admin/wp02/settings-scenarios.json");
            assertThat(scenarios.get("dataKind").asString()).isEqualTo("fixture");
            assertThat(scenarios.get("scenarios")).hasSize(8);
            for (JsonNode scenario : scenarios.get("scenarios")) {
                String name = scenario.get("name").asString();
                JsonNode patch = scenario.get("patch").isEmpty() ? defaults : scenario.get("patch");
                if (scenario.get("validSettings").asBoolean()) {
                    validator.validateAndMerge(defaults, patch);
                } else {
                    assertThat(name).isEqualTo("bank-incomplete");
                    assertThatThrownBy(() -> validator.validateAndMerge(defaults, patch))
                            .isInstanceOf(InputValidationException.class);
                }
                JsonNode response = read("public/wp02/shop-" + name + ".json");
                assertThat(response.propertyNames()).containsExactlyInAnyOrderElementsOf(SHOP_FIELDS);
                assertThat(response.get("acceptingOrders").isBoolean()).isTrue();
                assertThat(response.get("deliveryEnabled").isBoolean()).isTrue();
                assertThat(response.get("bankConfirmationMode").asString()).isEqualTo("manual");
                assertThat(response.get("maxQtyPerSku").asInt()).isEqualTo(99);
                for (String key : Set.of("shippingFeeVnd", "freeShipThresholdVnd")) {
                    JsonNode amount = response.get(key);
                    assertThat(amount.isNull() || amount.isIntegralNumber()).isTrue();
                    if (!amount.isNull()) assertThat(amount.asLong()).isBetween(0L, NumericInputs.MAX_VND);
                }
                if (!response.get("support").isNull())
                    assertThat(response.get("support").propertyNames()).containsExactlyInAnyOrder("phone", "zaloUrl");
                for (JsonNode zone : response.get("deliveryZones"))
                    assertThat(zone.propertyNames()).containsExactlyInAnyOrder("wardCode", "name", "allowNear");
            }
        }
        JsonNode preparation = read("public/wp02/shop-default.json");
        for (String field : Set.of(
                "storeName",
                "address",
                "openingHours",
                "pickupGuidance",
                "support",
                "shippingFeeVnd",
                "freeShipThresholdVnd"))
            assertThat(preparation.get(field).isNull()).isTrue();
        assertThat(preparation.get("enabledPaymentMethods")).isEmpty();
        assertThat(preparation.get("deliveryZones")).isEmpty();
        assertThat(preparation.get("acceptingOrders").asBoolean()).isTrue();
        assertThat(read("public/wp02/shop-complete.json")
                        .get("enabledPaymentMethods")
                        .valueStream()
                        .map(JsonNode::asString)
                        .toList())
                .containsExactly("cod", "bank_transfer");
        assertThat(read("public/wp02/shop-closed.json").get("acceptingOrders").asBoolean())
                .isFalse();
        for (String scenario : Set.of("default", "null-fee", "no-zones"))
            assertThat(read("public/wp02/shop-" + scenario + ".json")
                            .get("deliveryEnabled")
                            .asBoolean())
                    .isFalse();
        JsonNode zeroFee = read("public/wp02/shop-zero-fee.json");
        assertThat(zeroFee.get("deliveryEnabled").asBoolean()).isTrue();
        assertThat(zeroFee.get("shippingFeeVnd").asLong()).isZero();
        assertThat(read("public/wp02/shop-bank-incomplete.json")
                        .get("enabledPaymentMethods")
                        .valueStream()
                        .map(JsonNode::asString)
                        .toList())
                .containsExactly("cod");
        assertThat(read("public/wp02/shop-methods-disabled.json").get("enabledPaymentMethods"))
                .isEmpty();
    }

    private static JsonNode read(String relativePath) throws Exception {
        return JSON.readTree(Files.readAllBytes(RESOURCES.resolve(relativePath)));
    }
}
