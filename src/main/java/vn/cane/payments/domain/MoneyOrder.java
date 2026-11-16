package vn.cane.payments.domain;

import java.util.UUID;

/** Order-owned data passed to the money policy; Payments never reads or updates the Orders tables. */
public record MoneyOrder(
        UUID id,
        String orderNumber,
        String status,
        String paymentStatus,
        String paymentMethod,
        String fulfillmentMethod,
        long totalVnd,
        String bankCode,
        String accountNo) {
    @Override
    public String toString() {
        return "MoneyOrder[redacted]";
    }
}
