package vn.cane.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.cane.support.audit.ReminderTarget;

public interface OrderReminderReader {
    List<UUID> manualDue(Instant now);

    UUID upperId();

    List<ReminderTarget> page(String type, UUID after, UUID through);

    ReminderTarget current(String type, UUID id);
}
