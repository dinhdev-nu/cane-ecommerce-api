package vn.cane.orders.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Preserves the exact database value; the signer separately requires millisecond precision. */
public record OrderLinkState(UUID orderId, long version, Instant expiresAt) {
    public OrderLinkState {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(expiresAt);
        if (version <= 0 || expiresAt.toEpochMilli() <= 0)
            throw new IllegalArgumentException("Invalid order link state");
    }

    public static Instant millisecondInstant(Instant instant) {
        return Objects.requireNonNull(instant).truncatedTo(ChronoUnit.MILLIS);
    }

    public boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    public boolean hasMillisecondPrecision() {
        return expiresAt.equals(Instant.ofEpochMilli(expiresAt.toEpochMilli()));
    }

    @Override
    public String toString() {
        return "OrderLinkState[redacted]";
    }
}
