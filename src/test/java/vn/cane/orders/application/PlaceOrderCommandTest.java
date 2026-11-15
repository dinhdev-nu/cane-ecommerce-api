package vn.cane.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

class PlaceOrderCommandTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private ObjectNode body() {
        return (ObjectNode) json.readTree("""
                {"items":[{"sku":"b","quantity":1},{"sku":"a","quantity":2}],"fulfillmentMethod":"pickup","address":null,
                "recipient":{"name":"  Test  ","phone":"0338 825 982","email":"  TEST@EXAMPLE.INVALID  "},
                "paymentMethod":"cod","note":"  ","expectedTotalVnd":30000}
                """);
    }

    @Test
    void canonicalBusinessHashNormalizesSortsAndExcludesExpectedTotalAndForgedPrices() {
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var normalizer = new InputNormalizer(validators.getValidator());
            var original = body();
            var first = PlaceOrderCommand.parse(original, "0123456789abcdef", normalizer, json);
            var changed = body();
            changed.putArray("items")
                    .addObject()
                    .put("sku", "a")
                    .put("quantity", 2)
                    .put("unitPriceVnd", 1);
            ((tools.jackson.databind.node.ArrayNode) changed.get("items"))
                    .addObject()
                    .put("sku", "b")
                    .put("quantity", 1);
            changed.put("expectedTotalVnd", 40000).put("totalVnd", 1);
            var second = PlaceOrderCommand.parse(changed, "fedcba9876543210", normalizer, json);
            assertThat(second.requestHash()).isEqualTo(first.requestHash());
            assertThat(first.recipient().email()).isEqualTo("test@example.invalid");
            assertThat(first.note()).isNull();
            assertThat(first.toString()).doesNotContain("TEST", "0338");
            changed.put("note", "A new request");
            assertThat(PlaceOrderCommand.parse(changed, "0123456789abcdef", normalizer, json)
                            .requestHash())
                    .isNotEqualTo(first.requestHash());
            changed.put("unknown", true);
            assertThatThrownBy(() -> PlaceOrderCommand.parse(changed, "0123456789abcdef", normalizer, json))
                    .isInstanceOf(InputValidationException.class);
        }
    }
}
