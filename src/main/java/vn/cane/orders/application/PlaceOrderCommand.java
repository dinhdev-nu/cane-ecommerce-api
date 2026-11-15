package vn.cane.orders.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.checkout.application.CheckoutInputs;
import vn.cane.checkout.domain.CheckoutInput;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.validation.NumericInputs;

public record PlaceOrderCommand(
        CheckoutInput checkout,
        Recipient recipient,
        String note,
        long expectedTotalVnd,
        String key,
        byte[] requestHash) {
    public PlaceOrderCommand {
        requestHash = requestHash.clone();
    }

    @Override
    public byte[] requestHash() {
        return requestHash.clone();
    }

    public static PlaceOrderCommand parse(JsonNode raw, String key, InputNormalizer normalizer, JsonMapper json) {
        InputNormalizer.idempotencyKey(key);
        var checkout = CheckoutInputs.parse(raw, true);
        var body = CheckoutInputs.object(
                raw,
                Set.of(
                        "items",
                        "fulfillmentMethod",
                        "address",
                        "paymentMethod",
                        "recipient",
                        "note",
                        "expectedTotalVnd"),
                "body");
        var r = JsonInputs.object(body.get("recipient"), Set.of("name", "phone", "email"), "recipient");
        var recipient = new Recipient(
                InputNormalizer.requiredText(JsonInputs.text(r.get("name"), "recipient.name"), 100, "recipient.name"),
                InputNormalizer.phone(JsonInputs.text(r.get("phone"), "recipient.phone"), "recipient.phone"),
                normalizer.email(JsonInputs.text(r.get("email"), "recipient.email"), "recipient.email"));
        var n = body.get("note");
        String note = n == null || n.isNull() ? null : InputNormalizer.optionalNote(JsonInputs.text(n, "note"), "note");
        long total = NumericInputs.vnd(
                JsonInputs.integer(body.get("expectedTotalVnd"), "expectedTotalVnd"), true, "expectedTotalVnd");
        var canonical = json.createObjectNode();
        var items = canonical.putArray("items");
        checkout.items().stream()
                .sorted(Comparator.comparing(CheckoutInput.Item::sku))
                .forEach(i -> items.addObject().put("sku", i.sku()).put("quantity", i.quantity()));
        canonical.put("fulfillmentMethod", checkout.fulfillmentMethod());
        canonical
                .putObject("recipient")
                .put("name", recipient.name())
                .put("phone", recipient.phone())
                .put("email", recipient.email());
        if (checkout.address() == null) canonical.putNull("address");
        else
            canonical
                    .putObject("address")
                    .put("wardCode", checkout.address().wardCode())
                    .put("line1", checkout.address().line1());
        canonical.put("paymentMethod", checkout.paymentMethod()).put("note", note);
        try {
            return new PlaceOrderCommand(
                    checkout,
                    recipient,
                    note,
                    total,
                    key,
                    MessageDigest.getInstance("SHA-256")
                            .digest(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public record Recipient(String name, String phone, String email) {
        @Override
        public String toString() {
            return "Recipient[redacted]";
        }
    }

    @Override
    public String toString() {
        return "PlaceOrderCommand[redacted]";
    }
}
