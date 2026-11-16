package vn.cane.orders.application;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Public projection only; never serialize an order persistence row. */
public record GuestOrderView(Order order, List<Moment> timeline, Bank bankInstructions, JsonNode support) {
    public record Order(
            String orderNumber,
            String status,
            String paymentStatus,
            String fulfillmentMethod,
            List<Item> items,
            Recipient recipient,
            String addressSummary,
            Totals totals,
            boolean deliveryIssue,
            boolean cancelRequested,
            String cancelKind,
            String bankConfirmationMode,
            Instant paymentReminderAt,
            Instant expiresAt) {}

    public record Item(String name, String unitLabel, int quantity, long unitPriceVnd, long lineTotalVnd) {}

    public record Recipient(String name, String phoneMasked) {}

    public record Totals(long subtotalVnd, long shippingFeeVnd, long totalVnd) {}

    public record Moment(Instant at, String label) {}

    public record Bank(
            String bankCode,
            String bankName,
            String accountNo,
            String accountName,
            long amountVnd,
            String transferContent) {
        @Override
        public String toString() {
            return "Bank[redacted]";
        }
    }

    @Override
    public String toString() {
        return "GuestOrderView[redacted]";
    }
}
