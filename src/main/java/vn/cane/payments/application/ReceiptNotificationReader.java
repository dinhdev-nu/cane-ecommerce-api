package vn.cane.payments.application;

import java.util.UUID;

/** Immutable receipt identity/amount/method plus current alert flags. Historical links come from events. */
public interface ReceiptNotificationReader {
    Facts read(UUID id);

    public record Facts(long amountVnd, String method, boolean needsReview, boolean verified) {}
}
