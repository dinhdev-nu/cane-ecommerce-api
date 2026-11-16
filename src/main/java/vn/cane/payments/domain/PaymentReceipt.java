package vn.cane.payments.domain;

import java.time.Instant;
import java.util.UUID;

public record PaymentReceipt(
        UUID id,
        UUID orderId,
        String method,
        long amountVnd,
        String status,
        boolean appliedToOrder,
        boolean orderLinkVerified,
        BankEvidence bank,
        boolean needsReview,
        String note,
        String confirmedBy,
        Instant confirmedAt,
        String reviewedBy,
        Instant reviewedAt,
        Instant refundedAt,
        String refundReference) {
    @Override
    public String toString() {
        return "PaymentReceipt[redacted]";
    }
}
