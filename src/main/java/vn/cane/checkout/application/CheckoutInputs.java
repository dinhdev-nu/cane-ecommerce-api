package vn.cane.checkout.application;

import java.util.ArrayList;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.checkout.domain.CheckoutInput;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.validation.NumericInputs;

/** Shared structural normalization for quote and place. Client-supplied prices are discarded before hashing. */
public final class CheckoutInputs {
    private static final Set<String> MONEY = Set.of(
            "priceVnd",
            "unitPriceVnd",
            "lineTotalVnd",
            "subtotalVnd",
            "shippingFeeVnd",
            "totalVnd",
            "freeShipRemainingVnd");

    private CheckoutInputs() {}

    public static ObjectNode object(JsonNode value, Set<String> allowed, String field) {
        if (!(value instanceof ObjectNode object)) throw InputValidationException.malformed(field);
        var clean = object.deepCopy();
        MONEY.forEach(clean::remove);
        return JsonInputs.object(clean, allowed, field);
    }

    public static CheckoutInput parse(JsonNode value, boolean placing) {
        var body = object(
                value,
                placing
                        ? Set.of(
                                "items",
                                "fulfillmentMethod",
                                "address",
                                "paymentMethod",
                                "recipient",
                                "note",
                                "expectedTotalVnd")
                        : Set.of("items", "fulfillmentMethod", "address", "paymentMethod"),
                "body");
        var lines = JsonInputs.array(body.get("items"), "items");
        if (lines.isEmpty() || lines.size() > 9)
            throw InputValidationException.invalid("items", "Use between one and nine unique items.");
        var items = new ArrayList<CheckoutInput.Item>();
        for (int i = 0; i < lines.size(); i++) {
            var item = object(lines.get(i), Set.of("sku", "quantity"), "items[" + i + "]");
            items.add(new CheckoutInput.Item(
                    InputNormalizer.requiredText(
                            JsonInputs.text(item.get("sku"), "items[" + i + "].sku"), 100, "items[" + i + "].sku"),
                    (int) NumericInputs.integer(
                            JsonInputs.integer(item.get("quantity"), "items[" + i + "].quantity"),
                            1,
                            Integer.MAX_VALUE,
                            "items[" + i + "].quantity")));
        }
        InputNormalizer.uniqueSkus(items.stream().map(CheckoutInput.Item::sku).toList());
        String fulfillment = choice(body.get("fulfillmentMethod"), Set.of("delivery", "pickup"), "fulfillmentMethod");
        String method = choice(body.get("paymentMethod"), Set.of("cod", "bank_transfer"), "paymentMethod");
        CheckoutInput.Address address = null;
        var raw = body.get("address");
        if (fulfillment.equals("delivery")) {
            var object = JsonInputs.object(raw, Set.of("wardCode", "line1"), "address");
            address = new CheckoutInput.Address(
                    InputNormalizer.requiredText(
                            JsonInputs.text(object.get("wardCode"), "address.wardCode"), 100, "address.wardCode"),
                    InputNormalizer.requiredText(
                            JsonInputs.text(object.get("line1"), "address.line1"), 500, "address.line1"));
        } else if (raw == null || !raw.isNull())
            throw InputValidationException.invalid("address", "Pickup requires a null address.");
        return new CheckoutInput(items, fulfillment, address, method);
    }

    private static String choice(JsonNode node, Set<String> allowed, String field) {
        String text = InputNormalizer.requiredText(JsonInputs.text(node, field), 100, field);
        if (!allowed.contains(text)) throw InputValidationException.invalid(field, "Unsupported value.");
        return text;
    }
}
