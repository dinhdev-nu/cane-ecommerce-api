package vn.cane.orders.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.cane.payments.application.PaymentReadService;

/** Explicit staff projections. Persistence rows, operation receipts and tokens never reach this DTO. */
public final class AdminOrderView {
    private AdminOrderView() {}

    public record Recipient(String name, String phone, String email) {
        @Override
        public String toString() {
            return "Recipient[redacted]";
        }
    }

    public record Name(String name) {}

    public record Address(String wardCode, String wardName, String line1) {}

    public record Store(String name, String address, String openingHours, String pickupGuidance) {}

    public record Item(
            String sku,
            String name,
            String unitLabel,
            long unitPriceVnd,
            int quantity,
            long lineTotalVnd,
            String deliveryScope) {}

    public record Summary(
            UUID id,
            String orderNumber,
            String status,
            String paymentStatus,
            String paymentMethod,
            String fulfillmentMethod,
            Name recipient,
            long totalVnd,
            boolean deliveryIssue,
            boolean cancelRequested,
            String cancelKind,
            Instant createdAt,
            Instant updatedAt) {}

    public record Order(
            UUID id,
            String orderNumber,
            String status,
            String paymentStatus,
            String fulfillmentMethod,
            String paymentMethod,
            Recipient recipient,
            Address address,
            Store storeSnapshot,
            String note,
            long subtotalVnd,
            long shippingFeeVnd,
            long totalVnd,
            String bankConfirmationMode,
            Instant paymentReminderAt,
            Instant expiresAt,
            GuestOrderView.Bank bankInstructions,
            boolean deliveryIssue,
            String deliveryIssueReason,
            Instant cancelRequestedAt,
            String cancelRequestNote,
            String cancelKind,
            String cancelReason,
            Instant cancelledAt,
            long linkVersion,
            Instant linkExpiresAt,
            Instant createdAt,
            Instant updatedAt) {
        @Override
        public String toString() {
            return "AdminOrder[redacted]";
        }
    }

    public record Moment(
            UUID id,
            String kind,
            String fromStatus,
            String toStatus,
            String actor,
            String label,
            String note,
            Instant createdAt) {}

    public record Principal(
            UUID id, String method, long amountVnd, String status, Instant confirmedAt, Instant refundedAt) {}

    public record Permissions(
            boolean canConfirmBank,
            boolean canCancel,
            boolean canCloseDeliveryIssue,
            boolean canRefund,
            boolean canResendLink) {}

    public record Detail(
            Order order,
            List<Item> items,
            List<Moment> timeline,
            Principal principalPayment,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<PaymentReadService.Payment> extraPayments,
            Permissions permissions) {
        @Override
        public String toString() {
            return "AdminOrderDetail[redacted]";
        }
    }

    public record Failure(
            UUID id,
            UUID orderId,
            UUID eventId,
            UUID paymentId,
            String kind,
            String channel,
            int attempts,
            Instant exhaustedAt,
            String lastError) {}

    public record Failures(long total, List<Failure> items) {}

    public record Page(
            List<Summary> items,
            int page,
            int pageSize,
            long total,
            Instant serverTime,
            @JsonInclude(JsonInclude.Include.NON_NULL) Failures notificationFailures) {}
}
