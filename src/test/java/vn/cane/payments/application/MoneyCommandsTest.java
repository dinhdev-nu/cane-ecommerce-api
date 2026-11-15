package vn.cane.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.BankReceiptKey;
import vn.cane.support.validation.InputValidationException;

class MoneyCommandsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ORDER = UUID.randomUUID();
    private static final String KEY = "fixture-payment-key";

    @Test
    void normalizationDrivesHashAndEvidenceWithoutLosingZeroOrReferenceCase() {
        var input = bank();
        input.put("reference", " Ref:a|b\" ");
        input.withObject("bankReceipt")
                .put("bankCode", " CTG ")
                .put("accountNo", " 00 123\t456 ")
                .put("transferContent", " CANEABC123\r\nfixture ")
                .put("transactionAt", "2026-11-15T01:30:20.999Z");
        var first = MoneyCommands.confirm(ORDER, input, KEY, JSON);
        var normalized = bank().put("reference", "Ref:a|b\"");
        normalized.putNull("note");
        normalized
                .withObject("bankReceipt")
                .put("accountNo", "00123456")
                .put("transferContent", "CANEABC123\nfixture")
                .put("transactionAt", "2026-11-15T01:30:20Z");
        var second = MoneyCommands.confirm(ORDER, normalized, KEY, JSON);
        assertThat(first.canonical().hash()).containsExactly(second.canonical().hash());
        assertThat(first.entry().bank().accountNo()).isEqualTo("00123456");
        assertThat(first.entry().bank().reference()).isEqualTo("Ref:a|b\"");
        assertThat(BankReceiptKey.reference(first.entry().bank().key())).isEqualTo("Ref:a|b\"");
        assertThat(MoneyCommands.confirm(ORDER, normalized.put("reference", "ref:a|b\""), KEY, JSON)
                        .canonical()
                        .hash())
                .isNotEqualTo(first.canonical().hash());
    }

    @Test
    void canonicalKeyFramesComponentsAndEscapesRatherThanJoiningDelimiters() {
        assertThat(BankReceiptKey.encode("A|B", "C", "D")).isNotEqualTo(BankReceiptKey.encode("A", "B|C", "D"));
        assertThat(BankReceiptKey.reference(BankReceiptKey.encode("A", "001", "r\"\\\n")))
                .isEqualTo("r\"\\\n");
        assertThatThrownBy(() -> BankReceiptKey.reference("raw-reference")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsFractionalMoneyUnknownFieldsAndForgedActor() {
        assertThatThrownBy(() -> MoneyCommands.confirm(ORDER, bank().put("amountVnd", 1.5), KEY, JSON))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> MoneyCommands.confirm(ORDER, bank().put("amountVnd", 0), KEY, JSON))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(
                        () -> MoneyCommands.confirm(ORDER, bank().put("amountVnd", 9_007_199_254_740_992L), KEY, JSON))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> MoneyCommands.confirm(ORDER, bank().put("actor", "owner"), KEY, JSON))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> MoneyCommands.confirm(ORDER, bank().put("reference", " "), KEY, JSON))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void cashExceptionsRequireAnOrderAndEvidenceAndNeverAcceptBankFields() {
        var cash = JSON.createObjectNode().put("method", "cash").put("amountVnd", 40000);
        assertThatThrownBy(() -> MoneyCommands.record(cash, KEY, JSON)).isInstanceOf(InputValidationException.class);
        cash.put("orderId", ORDER.toString());
        assertThatThrownBy(() -> MoneyCommands.record(cash, KEY, JSON)).isInstanceOf(InputValidationException.class);
        cash.put("note", "Physical cash verified").putNull("reference").putNull("bankReceipt");
        assertThat(MoneyCommands.record(cash, KEY, JSON).entry().bank()).isNull();
        cash.put("reference", "not-allowed");
        assertThatThrownBy(() -> MoneyCommands.record(cash, KEY, JSON)).isInstanceOf(InputValidationException.class);
    }

    @Test
    void resolveAndRefundValidateTheirOwnNarrowContracts() {
        var resolve = JSON.createObjectNode()
                .put("action", "apply_to_order")
                .put("expectedPaymentStatus", "succeeded")
                .put("note", "verified");
        assertThatThrownBy(() -> MoneyCommands.resolve(ORDER, resolve, KEY, JSON))
                .isInstanceOf(InputValidationException.class);
        resolve.put("action", "close_review");
        assertThat(MoneyCommands.resolve(ORDER, resolve, KEY, JSON).orderId()).isNull();
        var refund = JSON.createObjectNode()
                .put("expectedPaymentStatus", "succeeded")
                .put("reference", " evidence ");
        assertThat(MoneyCommands.refund(ORDER, refund, KEY, JSON).reference()).isEqualTo("evidence");
        assertThatThrownBy(() -> MoneyCommands.refund(ORDER, refund.put("amountVnd", 1), KEY, JSON))
                .isInstanceOf(InputValidationException.class);
    }

    private static ObjectNode bank() {
        var body = JSON.createObjectNode()
                .put("expectedStatus", "awaiting_payment")
                .put("method", "bank")
                .put("amountVnd", 40000)
                .put("reference", "ref");
        body.putObject("bankReceipt")
                .put("bankCode", "CTG")
                .put("accountNo", "00123456")
                .put("transferContent", "CANEABC123")
                .put("transactionAt", "2026-11-15T01:30:20Z");
        return body;
    }
}
