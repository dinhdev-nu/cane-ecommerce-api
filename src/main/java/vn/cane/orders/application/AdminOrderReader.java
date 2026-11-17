package vn.cane.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminOrderReader {
    Instant serverTime();

    List<AdminOrderView.Summary> page(AdminOrderQuery query);

    long count(AdminOrderQuery query);

    Optional<AdminOrderView.Order> find(UUID id);

    List<AdminOrderView.Item> items(UUID id);

    List<AdminOrderView.Moment> timeline(UUID id);

    AdminOrderView.Failures failures();
}
