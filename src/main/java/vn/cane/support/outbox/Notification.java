package vn.cane.support.outbox;

import java.util.Objects;
import java.util.UUID;

/** All candidates for one event are supplied together so customer email wins a recipient collision. */
public record Notification(
        UUID id, Kind kind, UUID orderId, UUID paymentId, Channel channel, String recipient, Audience audience) {
    public Notification {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(audience);
        if (recipient == null || recipient.isBlank())
            throw new IllegalArgumentException("Notification recipient is required");
    }

    @Override
    public String toString() {
        return "Notification[redacted]";
    }

    public enum Kind {
        ORDER_PLACED,
        PAYMENT_RECEIVED,
        SHIPPED,
        READY_PICKUP,
        COMPLETED,
        CANCELLED,
        DELIVERY_FAILED_CLOSED,
        REFUNDED,
        LINK_RESENT,
        OWNER_NEW_ORDER,
        OWNER_PAYMENT_DUE,
        OWNER_ALERT;

        public String databaseValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public boolean ownerOnly() {
            return this == OWNER_NEW_ORDER || this == OWNER_PAYMENT_DUE || this == OWNER_ALERT;
        }
    }

    public enum Channel {
        EMAIL,
        TELEGRAM;

        public String databaseValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Audience {
        CUSTOMER,
        OWNER
    }
}
