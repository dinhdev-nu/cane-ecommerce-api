package vn.cane.orders.application;

import java.util.Optional;
import java.util.UUID;
import vn.cane.orders.domain.OrderLinkState;

public interface OrderLinkRepository {
    Optional<OrderLinkState> find(UUID orderId);

    Optional<OrderLinkState> lock(UUID orderId);
}
