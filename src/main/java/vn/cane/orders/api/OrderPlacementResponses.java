package vn.cane.orders.api;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.orders.application.OrderLinkTokens;
import vn.cane.orders.application.OrderPlacementRepository.PlacedOrder;
import vn.cane.orders.domain.OrderLinkState;

/** Explicit allowlist; neither persistence rows nor request hashes are serialized to clients. */
@Component
public final class OrderPlacementResponses {
    private final OrderLinkTokens tokens;
    private final Clock clock;
    private final String origin;

    public OrderPlacementResponses(
            OrderLinkTokens tokens, Clock clock, @Value("${cane.http.public-origin}") String origin) {
        this.tokens = tokens;
        this.clock = clock;
        this.origin = origin.replaceAll("/$", "");
    }

    public Response response(PlacedOrder row) {
        boolean awaiting = row.status().equals("awaiting_payment");
        var state = new OrderLinkState(row.id(), row.linkVersion(), row.linkExpiresAt());
        String url = state.validAt(clock.instant())
                ? origin + "/orders/view#t=" + tokens.sign(state).value()
                : null;
        var order = new Order(
                row.orderNumber(),
                row.status(),
                row.paymentStatus(),
                row.fulfillmentMethod(),
                row.subtotalVnd(),
                row.shippingFeeVnd(),
                row.totalVnd(),
                row.bankMode(),
                awaiting && "manual".equals(row.bankMode()) ? row.dueAt() : null,
                awaiting && "auto".equals(row.bankMode()) ? row.dueAt() : null);
        var bank = awaiting && row.bank() != null
                ? new BankInstructions(
                        CheckoutSettings.text(row.bank(), "bankCode"),
                        CheckoutSettings.text(row.bank(), "bankName"),
                        CheckoutSettings.text(row.bank(), "accountNo"),
                        CheckoutSettings.text(row.bank(), "accountName"),
                        row.totalVnd(),
                        row.orderNumber())
                : null;
        return new Response(order, url, bank);
    }

    public record Response(Order order, String viewUrl, BankInstructions bankInstructions) {
        @Override
        public String toString() {
            return "OrderResponse[redacted]";
        }
    }

    public record Order(
            String orderNumber,
            String status,
            String paymentStatus,
            String fulfillmentMethod,
            long subtotalVnd,
            long shippingFeeVnd,
            long totalVnd,
            String bankConfirmationMode,
            Instant paymentReminderAt,
            Instant expiresAt) {}

    public record BankInstructions(
            String bankCode,
            String bankName,
            String accountNo,
            String accountName,
            long amountVnd,
            String transferContent) {
        @Override
        public String toString() {
            return "BankInstructions[redacted]";
        }
    }
}
