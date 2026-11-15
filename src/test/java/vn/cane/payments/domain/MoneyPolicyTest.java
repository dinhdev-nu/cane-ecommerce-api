package vn.cane.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MoneyPolicyTest {
    private static final UUID ID = UUID.randomUUID();

    @Test
    void bankContentMustContainExactlyOneDelimitedMatchingCode() {
        for (var content : new String[] {"CANEABC123", "Pay: CANEABC123."})
            assertThat(bank(content).identifies("CANEABC123")).isTrue();
        for (var content : new String[] {
            "XCANEABC123", "CANEABC123X", "caneabc123", "CANEABC123 CANEZZZ999", "CANEABC123 CANEABC123"
        }) assertThat(bank(content).identifies("CANEABC123")).as(content).isFalse();
    }

    @Test
    void cashFiveStatesAndBankAwaitingAreExplicit() {
        for (var state : new String[] {"confirmed", "preparing", "ready_for_pickup", "shipping", "completed"}) {
            var order = order(state, "unpaid", "cod", state.equals("ready_for_pickup") ? "pickup" : "delivery");
            assertThatCode(() -> MoneyPolicy.apply(order, receipt("cash", false, 40000, null), null, false, true))
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> MoneyPolicy.apply(
                        order("awaiting_payment", "unpaid", "bank_transfer", "delivery"),
                        receipt("bank", false, 40000, bank("CANEABC123")),
                        null,
                        false,
                        true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> MoneyPolicy.apply(
                        order("cancelled", "unpaid", "cod", "delivery"),
                        receipt("cash", false, 40000, null),
                        null,
                        false,
                        true))
                .isInstanceOf(MoneyRejected.class);
    }

    @Test
    void ownerResolveCanVerifyWrongContentAndLateCancelledMoneyButNotWrongAmountOrAccount() {
        var order = order("cancelled", "unpaid", "bank_transfer", "delivery");
        var wrongContent = receipt("bank", false, 40000, bank("wrong memo"));
        assertThatCode(() -> MoneyPolicy.apply(order, wrongContent, null, true, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> MoneyPolicy.apply(order, wrongContent, null, true, true))
                .isInstanceOf(MoneyRejected.class);
        assertThatThrownBy(() ->
                        MoneyPolicy.apply(order, receipt("bank", false, 39999, bank("CANEABC123")), null, true, false))
                .isInstanceOf(MoneyRejected.class);
        var otherBank = new BankEvidence("CTG", "00999", "ref", "CANEABC123", Instant.EPOCH);
        assertThatThrownBy(() -> MoneyPolicy.apply(order, receipt("bank", false, 40000, otherBank), null, true, false))
                .isInstanceOf(MoneyRejected.class);
    }

    @Test
    void corruptionFailsClosedAndAuxiliaryRefundLeavesPaidPrincipalValid() {
        var paid = order("completed", "paid", "cod", "delivery");
        var principal = receipt("cash", true, 40000, null);
        assertThatCode(() -> MoneyPolicy.refund(paid, receipt("cash", false, 10000, null), principal))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> MoneyPolicy.refund(paid, principal, principal)).isInstanceOf(MoneyRejected.class);
        assertThatThrownBy(() -> MoneyPolicy.invariant(paid, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MoneyPolicy.invariant(paid, receipt("cash", true, 39999, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> MoneyPolicy.refund(
                        order("cancelled", "refund_pending", "cod", "delivery"), principal, principal))
                .doesNotThrowAnyException();
    }

    private static BankEvidence bank(String content) {
        return new BankEvidence("CTG", "00123", "ref", content, Instant.EPOCH);
    }

    private static MoneyOrder order(String status, String payment, String method, String fulfillment) {
        return new MoneyOrder(ID, "CANEABC123", status, payment, method, fulfillment, 40000, "CTG", "00123");
    }

    private static PaymentReceipt receipt(String method, boolean applied, long amount, BankEvidence bank) {
        return new PaymentReceipt(
                UUID.randomUUID(),
                ID,
                method,
                amount,
                "succeeded",
                applied,
                true,
                bank,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
