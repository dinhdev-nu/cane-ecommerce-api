package vn.cane.worker;

import java.time.Instant;
import java.util.UUID;

/** Immutable fencing identity for one claimed attempt; recipient never appears in diagnostics. */
public record OutboxLease(
        UUID id,
        String kind,
        UUID orderId,
        UUID paymentId,
        UUID eventId,
        String channel,
        String recipient,
        int attempts,
        Instant lockedAt) {
    @Override
    public String toString() {
        return "OutboxLease[id=" + id + ",attempts=" + attempts + "]";
    }
}
