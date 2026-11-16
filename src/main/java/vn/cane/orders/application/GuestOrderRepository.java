package vn.cane.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vn.cane.orders.domain.OrderLinkState;

public interface GuestOrderRepository {
    Optional<Snapshot> find(UUID id);

    List<GuestOrderView.Item> items(UUID id);

    List<GuestOrderView.Moment> timeline(UUID id);

    void requestCancel(UUID id, Instant at, String reason);

    void rotate(OrderLinkState link);

    record Snapshot(
            UUID id,
            String number,
            String status,
            String paymentStatus,
            String fulfillment,
            String name,
            String phone,
            String email,
            JsonNode address,
            JsonNode store,
            long subtotal,
            long shippingFee,
            long total,
            JsonNode bank,
            String bankMode,
            Instant dueAt,
            boolean deliveryIssue,
            Instant cancelRequestedAt,
            String cancelKind,
            long linkVersion,
            Instant linkExpiresAt,
            Instant createdAt) {
        @Override
        public String toString() {
            return "OrderSnapshot[redacted]";
        }
    }
}
