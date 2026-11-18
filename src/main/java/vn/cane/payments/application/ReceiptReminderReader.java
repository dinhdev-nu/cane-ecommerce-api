package vn.cane.payments.application;

import java.util.List;
import java.util.UUID;
import vn.cane.support.audit.ReminderTarget;

public interface ReceiptReminderReader {
    UUID upperId();

    List<ReminderTarget> page(UUID after, UUID through);

    ReminderTarget lock(UUID id);
}
