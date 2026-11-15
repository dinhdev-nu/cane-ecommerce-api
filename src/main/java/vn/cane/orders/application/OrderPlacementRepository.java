package vn.cane.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.catalog.application.CheckoutCatalog.Product;
import vn.cane.checkout.domain.Quote;

public interface OrderPlacementRepository {
    Optional<PlacedOrder> find(String key);

    PlacedOrder insert(
            UUID id,
            String number,
            Instant createdAt,
            PlaceOrderCommand command,
            CheckoutSettings settings,
            Quote quote,
            List<Product> products);

    record PlacedOrder(
            UUID id,
            String orderNumber,
            String status,
            String paymentStatus,
            String fulfillmentMethod,
            long subtotalVnd,
            long shippingFeeVnd,
            long totalVnd,
            String bankMode,
            Instant dueAt,
            JsonNode bank,
            long linkVersion,
            Instant linkExpiresAt,
            byte[] requestHash) {
        public PlacedOrder {
            requestHash = requestHash.clone();
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }

        @Override
        public String toString() {
            return "PlacedOrder[redacted]";
        }
    }
}
