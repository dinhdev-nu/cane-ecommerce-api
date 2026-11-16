package vn.cane.orders.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.cane.payments.domain.MoneyOrder;

public interface OrderMoneyRepository {
    List<MoneyOrder> locked(Collection<UUID> ids);

    void apply(MoneyOrder order);

    void refunded(UUID orderId);

    String customerEmail(UUID orderId);
}
