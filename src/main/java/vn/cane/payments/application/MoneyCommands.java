package vn.cane.payments.application;

import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.payments.domain.BankEvidence;
import vn.cane.support.idempotency.CanonicalOperation;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.validation.NumericInputs;
import vn.cane.support.validation.TimeInputs;

/** Parse once; the same normalized values drive both the operation hash and the write. */
public final class MoneyCommands {
    private static final Set<String> ENTRY = Set.of("method", "amountVnd", "reference", "bankReceipt", "note");
    private static final Set<String> STATUSES = Set.of(
            "awaiting_payment", "confirmed", "preparing", "ready_for_pickup", "shipping", "completed", "cancelled");

    private MoneyCommands() {}

    public sealed interface Command permits Confirm, RecordReceipt, Resolve, Refund {
        CanonicalOperation canonical();

        UUID orderId();

        default UUID paymentId() {
            return null;
        }
    }

    public record Entry(String method, long amountVnd, BankEvidence bank, String note) {
        @Override
        public String toString() {
            return "MoneyEntry[redacted]";
        }
    }

    public record Confirm(UUID orderId, String expectedStatus, Entry entry, CanonicalOperation canonical)
            implements Command {
        @Override
        public String toString() {
            return "ConfirmPayment[redacted]";
        }
    }

    public record RecordReceipt(UUID orderId, Entry entry, CanonicalOperation canonical) implements Command {
        @Override
        public String toString() {
            return "RecordReceipt[redacted]";
        }
    }

    public record Resolve(UUID paymentId, String action, UUID orderId, String note, CanonicalOperation canonical)
            implements Command {
        @Override
        public String toString() {
            return "ResolveReceipt[redacted]";
        }
    }

    public record Refund(UUID paymentId, String reference, CanonicalOperation canonical) implements Command {
        @Override
        public UUID orderId() {
            return null;
        }

        @Override
        public String toString() {
            return "RefundReceipt[redacted]";
        }
    }

    public static Confirm confirm(UUID orderId, JsonNode input, String key, JsonMapper json) {
        var fields = new java.util.HashSet<>(ENTRY);
        fields.add("expectedStatus");
        var body = JsonInputs.object(input, fields, "body");
        String expected = choice(body, "expectedStatus", STATUSES);
        Entry entry = entry(body);
        var normalized = entryBody(entry, json).put("expectedStatus", expected);
        return new Confirm(
                orderId, expected, entry, canonical("/admin/orders/" + orderId + "/confirm-payment", normalized, key));
    }

    public static RecordReceipt record(JsonNode input, String key, JsonMapper json) {
        var fields = new java.util.HashSet<>(ENTRY);
        fields.add("orderId");
        var body = JsonInputs.object(input, fields, "body");
        UUID orderId = optionalId(body.get("orderId"));
        Entry entry = entry(body);
        if (entry.method().equals("cash")) {
            if (orderId == null) throw InputValidationException.invalid("orderId", "Cash requires a COD order.");
            if (entry.note() == null)
                throw InputValidationException.invalid("note", "Cash requires evidence of receipt.");
        }
        var normalized = entryBody(entry, json);
        nullable(normalized, "orderId", orderId);
        return new RecordReceipt(orderId, entry, canonical("/admin/payments", normalized, key));
    }

    public static Resolve resolve(UUID paymentId, JsonNode input, String key, JsonMapper json) {
        var body = JsonInputs.object(input, Set.of("action", "orderId", "expectedPaymentStatus", "note"), "body");
        String action = choice(body, "action", Set.of("apply_to_order", "close_review"));
        choice(body, "expectedPaymentStatus", Set.of("succeeded"));
        UUID orderId = optionalId(body.get("orderId"));
        if (action.equals("apply_to_order") && orderId == null)
            throw InputValidationException.invalid("orderId", "An order is required.");
        String note = InputNormalizer.requiredText(JsonInputs.text(body.get("note"), "note"), 1000, "note");
        var normalized = json.createObjectNode()
                .put("action", action)
                .put("expectedPaymentStatus", "succeeded")
                .put("note", note);
        nullable(normalized, "orderId", orderId);
        return new Resolve(
                paymentId,
                action,
                orderId,
                note,
                canonical("/admin/payments/" + paymentId + "/resolve", normalized, key));
    }

    public static Refund refund(UUID paymentId, JsonNode input, String key, JsonMapper json) {
        var body = JsonInputs.object(input, Set.of("expectedPaymentStatus", "reference"), "body");
        choice(body, "expectedPaymentStatus", Set.of("succeeded"));
        String reference =
                InputNormalizer.requiredText(JsonInputs.text(body.get("reference"), "reference"), 200, "reference");
        var normalized = json.createObjectNode()
                .put("expectedPaymentStatus", "succeeded")
                .put("reference", reference);
        return new Refund(
                paymentId, reference, canonical("/admin/payments/" + paymentId + "/confirm-refund", normalized, key));
    }

    private static Entry entry(ObjectNode body) {
        String method = choice(body, "method", Set.of("cash", "bank"));
        long amount = NumericInputs.vnd(JsonInputs.integer(body.get("amountVnd"), "amountVnd"), true, "amountVnd");
        String note = InputNormalizer.optionalNote(optionalText(body.get("note"), "note"), "note");
        BankEvidence bank = null;
        if (method.equals("cash")) {
            if (present(body.get("reference")) || present(body.get("bankReceipt")))
                throw InputValidationException.invalid("bankReceipt", "Cash cannot have bank evidence.");
        } else {
            String reference =
                    InputNormalizer.requiredText(JsonInputs.text(body.get("reference"), "reference"), 200, "reference");
            var b = JsonInputs.completeObject(
                    body.get("bankReceipt"),
                    Set.of("bankCode", "accountNo", "transferContent", "transactionAt"),
                    "bankReceipt");
            String code = InputNormalizer.requiredText(
                    JsonInputs.text(b.get("bankCode"), "bankReceipt.bankCode"), 100, "bankReceipt.bankCode");
            String account = InputNormalizer.requiredText(
                            JsonInputs.text(b.get("accountNo"), "bankReceipt.accountNo"), 100, "bankReceipt.accountNo")
                    .replaceAll("\\s", "");
            if (account.isBlank())
                throw InputValidationException.invalid("bankReceipt.accountNo", "An account is required.");
            String content = InputNormalizer.requiredText(
                            JsonInputs.text(b.get("transferContent"), "bankReceipt.transferContent"),
                            500,
                            "bankReceipt.transferContent")
                    .replace("\r\n", "\n");
            bank = new BankEvidence(
                    code,
                    account,
                    reference,
                    content,
                    TimeInputs.transactionAt(
                            JsonInputs.text(b.get("transactionAt"), "bankReceipt.transactionAt"),
                            "bankReceipt.transactionAt"));
        }
        return new Entry(method, amount, bank, note);
    }

    private static ObjectNode entryBody(Entry entry, JsonMapper json) {
        var body = json.createObjectNode().put("method", entry.method()).put("amountVnd", entry.amountVnd());
        nullable(body, "note", entry.note());
        if (entry.bank() == null) {
            body.putNull("reference");
            body.putNull("bankReceipt");
        } else {
            var b = entry.bank();
            body.put("reference", b.reference());
            body.set(
                    "bankReceipt",
                    json.createObjectNode()
                            .put("bankCode", b.bankCode())
                            .put("accountNo", b.accountNo())
                            .put("transferContent", b.transferContent())
                            .put("transactionAt", b.transactionAt().toString()));
        }
        return body;
    }

    private static CanonicalOperation canonical(String route, ObjectNode body, String key) {
        return CanonicalOperation.adminPost("POST", "/api/v1" + route, body, key);
    }

    private static UUID optionalId(JsonNode node) {
        return present(node) ? InputNormalizer.uuid(JsonInputs.text(node, "orderId"), "orderId") : null;
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static String optionalText(JsonNode node, String field) {
        return present(node) ? JsonInputs.text(node, field) : null;
    }

    private static String choice(ObjectNode body, String field, Set<String> choices) {
        String value = InputNormalizer.requiredText(JsonInputs.text(body.get(field), field), 100, field);
        if (!choices.contains(value)) throw InputValidationException.invalid(field, "Invalid value.");
        return value;
    }

    private static void nullable(ObjectNode body, String field, Object value) {
        if (value == null) body.putNull(field);
        else body.put(field, value.toString());
    }
}
