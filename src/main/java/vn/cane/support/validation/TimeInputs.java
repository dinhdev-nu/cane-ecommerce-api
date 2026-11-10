package vn.cane.support.validation;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class TimeInputs {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // PostgreSQL timestamptz has microsecond storage and ends in year 294276 AD.
    private static final Instant DATABASE_END = Instant.parse("+294277-01-01T00:00:00Z");

    private TimeInputs() {}

    public static Instant utc(String value, String field) {
        String text = InputNormalizer.requiredText(value, 100, field);
        if (text.endsWith("Z")) {
            try {
                return Instant.parse(text);
            } catch (DateTimeException ignored) {
                // No rejected input in the exception returned to callers/logs.
            }
        }
        throw InputValidationException.invalid(field, "Thời gian cần ISO 8601 UTC với hậu tố Z.");
    }

    public static Instant transactionAt(String value, String field) {
        return utc(value, field).truncatedTo(ChronoUnit.SECONDS);
    }

    public static Instant expiry(Instant base, long amount, ChronoUnit unit, String field) {
        try {
            Instant result = base.plus(amount, unit);
            if (amount > 0 && result.isAfter(base) && result.isBefore(DATABASE_END)) {
                return result;
            }
        } catch (DateTimeException | ArithmeticException ignored) {
            // Deliberately exclude exception messages and values from public validation errors.
        }
        throw InputValidationException.invalid(field, "Thời lượng vượt miền thời gian cho phép.");
    }
}
