package vn.cane.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PublicShopServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void defaultsAndCompleteFixturesUseOneReadAndExactlyThePublicShape() throws IOException {
        for (String kind : new String[] {"default", "complete"}) {
            var values = values(kind);
            var reads = new AtomicInteger();
            var service = new PublicShopService(() -> {
                reads.incrementAndGet();
                return values;
            });
            var expected = json.readTree(getClass().getResourceAsStream("/public/wp02/shop-" + kind + ".json"));
            JsonNode actual = json.readTree(json.writeValueAsString(service.read()));
            assertThat(actual).isEqualTo(expected);
            assertThat(reads.get()).isEqualTo(1);
        }
    }

    @Test
    void configuredBankNeedsAllFieldsAndFreeDeliveryNeedsAZone() throws IOException {
        var values = values("complete");
        values.put("bank_details", json.nullNode());
        values.put("shipping_fee_vnd", json.valueToTree(0));
        var service = new PublicShopService(() -> values);
        assertThat(service.read().enabledPaymentMethods()).containsExactly("cod");
        assertThat(service.read().deliveryEnabled()).isTrue();
        values.put("delivery_zones", json.createArrayNode());
        assertThat(service.read().deliveryEnabled()).isFalse();
        values.remove("store");
        assertThatThrownBy(service::read).isInstanceOf(IllegalStateException.class);
    }

    private Map<String, JsonNode> values(String name) throws IOException {
        var root = json.readTree(getClass().getResourceAsStream("/admin/wp02/settings-default.json"));
        var result = new LinkedHashMap<String, JsonNode>();
        root.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        var scenarios = json.readTree(getClass().getResourceAsStream("/admin/wp02/settings-scenarios.json"));
        for (var scenario : scenarios.get("scenarios")) {
            if (scenario.get("name").asString().equals(name)) {
                scenario.get("patch").properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }
}
