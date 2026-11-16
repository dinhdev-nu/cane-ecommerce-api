package vn.cane.orders.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Read contract for dispatch; caller supplies a consistent read snapshot. No order mutation. */
@Component
public final class NotificationOrderReader {
    private final GuestOrderRepository orders;

    public NotificationOrderReader(GuestOrderRepository orders) {
        this.orders = orders;
    }

    public Data read(UUID id) {
        var row = orders.find(id).orElseThrow(() -> new IllegalStateException("Notification order missing"));
        return new Data(row, orders.items(id));
    }

    public record Data(GuestOrderRepository.Snapshot order, List<GuestOrderView.Item> items) {
        @Override
        public String toString() {
            return "NotificationOrder[redacted]";
        }
    }
}
