package vn.cane.payments.domain;

import java.time.Instant;
import java.util.regex.Pattern;
import vn.cane.support.validation.BankReceiptKey;

/** Immutable normalized evidence, with precision shared by manual entry and future adapters. */
public record BankEvidence(
        String bankCode, String accountNo, String reference, String transferContent, Instant transactionAt) {
    private static final Pattern ORDER_TOKEN = Pattern.compile("(?<![A-Za-z0-9])CANE[A-Z0-9]{6}(?![A-Za-z0-9])");

    public String key() {
        return BankReceiptKey.encode(bankCode, accountNo, reference);
    }

    public boolean identifies(String orderNumber) {
        var matches = ORDER_TOKEN
                .matcher(transferContent)
                .results()
                .map(m -> m.group())
                .toList();
        return matches.size() == 1 && matches.getFirst().equals(orderNumber);
    }

    @Override
    public String toString() {
        return "BankEvidence[redacted]";
    }
}
