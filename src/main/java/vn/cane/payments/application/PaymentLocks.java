package vn.cane.payments.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Acquire after all related orders and before products. Recheck links after locking receipts. */
public interface PaymentLocks {
    List<UUID> lockIds(Collection<UUID> paymentIds);
}
