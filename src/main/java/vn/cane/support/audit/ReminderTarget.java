package vn.cane.support.audit;

import java.time.Instant;
import java.util.UUID;

/** Identifiers and an authoritative age anchor; never contains customer data. */
public record ReminderTarget(UUID id, UUID orderId, UUID paymentId, Instant since) {}
