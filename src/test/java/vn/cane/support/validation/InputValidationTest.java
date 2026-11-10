package vn.cane.support.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputValidationTest {
    @Test
    void preservesPasswordAndNormalizesOtherTextWithoutTruncating() {
        String password = "  mật khẩu thử nghiệm  ";
        assertThat(InputNormalizer.creationPassword(password)).isEqualTo(password);
        assertThat(InputNormalizer.creationPassword("😀".repeat(15))).isEqualTo("😀".repeat(15));
        assertThat(InputNormalizer.creationPassword("x".repeat(128))).hasSize(128);
        for (String invalid : List.of("x".repeat(14), "x".repeat(129))) {
            assertThatThrownBy(() -> InputNormalizer.creationPassword(invalid))
                    .isInstanceOf(InputValidationException.class);
        }
        assertThat(InputNormalizer.username("  OwNeR  ")).isEqualTo("owner");
        assertThat(InputNormalizer.optionalNote(" \n\t", "note")).isNull();
        assertThat(InputNormalizer.optionalNote(null, "note")).isNull();
        assertThat(InputNormalizer.requiredText(" 😀 ", 1, "name")).isEqualTo("😀");
        assertThatThrownBy(() -> InputNormalizer.requiredText("x".repeat(101), 100, "name"))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void normalizesEmailAndPhoneAndRejectsMalformedValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var normalizer = new InputNormalizer(factory.getValidator());
            assertThat(normalizer.email(" Test+Tag@EXAMPLE.Invalid ", "email")).isEqualTo("test+tag@example.invalid");
            for (String invalid : List.of("not-an-email", "a@", "a b@example.invalid", "x".repeat(255))) {
                assertThatThrownBy(() -> normalizer.email(invalid, "email"))
                        .isInstanceOf(InputValidationException.class);
            }
        }
        assertThat(InputNormalizer.phone(" (+84) 900-123.456 ", "phone")).isEqualTo("+84900123456");
        assertThat(InputNormalizer.phone("0900 123 456", "phone")).isEqualTo("0900123456");
        for (String invalid : List.of("12345678", "1".repeat(16), "+84abc1234567", "0900/123456", "++84900123456")) {
            assertThatThrownBy(() -> InputNormalizer.phone(invalid, "phone"))
                    .isInstanceOf(InputValidationException.class);
        }
    }

    @Test
    void enforcesIntegerAndSafeJsonMoneyDomainsIncludingArithmeticResults() {
        assertThat(NumericInputs.vnd(BigInteger.valueOf(NumericInputs.MAX_VND), true, "priceVnd"))
                .isEqualTo(NumericInputs.MAX_VND);
        assertThat(NumericInputs.integer(BigInteger.valueOf(Integer.MAX_VALUE), 0, Integer.MAX_VALUE, "stockQty"))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(NumericInputs.integer(
                        BigInteger.valueOf(Integer.MIN_VALUE), Integer.MIN_VALUE, Integer.MAX_VALUE, "delta"))
                .isEqualTo(Integer.MIN_VALUE);
        assertThat(NumericInputs.addVnd(NumericInputs.MAX_VND - 1, 1, "totalVnd"))
                .isEqualTo(NumericInputs.MAX_VND);
        assertThat(NumericInputs.multiplyVnd(25000, 99, "subtotalVnd")).isEqualTo(2475000);
        for (BigInteger invalid : List.of(
                BigInteger.valueOf(-1),
                BigInteger.ONE.shiftLeft(100),
                BigInteger.valueOf(NumericInputs.MAX_VND).add(BigInteger.ONE))) {
            assertThatThrownBy(() -> NumericInputs.vnd(invalid, false, "amountVnd"))
                    .isInstanceOf(InputValidationException.class);
        }
        assertThatThrownBy(() -> NumericInputs.vnd(BigInteger.ZERO, true, "priceVnd"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> NumericInputs.addVnd(NumericInputs.MAX_VND, 1, "totalVnd"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> NumericInputs.multiplyVnd(NumericInputs.MAX_VND, Integer.MAX_VALUE, "subtotalVnd"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(
                        () -> NumericInputs.integer(BigInteger.valueOf(2147483648L), 0, Integer.MAX_VALUE, "stockQty"))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void preservesInstantPrecisionAndNormalizesReceiptTimeToSecondsOnly() {
        String timestamp = "2026-11-10T00:00:00.123456789Z";
        assertThat(TimeInputs.utc(timestamp, "expiresAt")).isEqualTo(Instant.parse(timestamp));
        assertThat(TimeInputs.transactionAt(timestamp, "transactionAt"))
                .isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
        assertThat(Instant.parse("2026-11-09T17:00:00Z")
                        .atZone(TimeInputs.BUSINESS_ZONE)
                        .toLocalDate()
                        .toString())
                .isEqualTo("2026-11-10");
        for (String invalid : List.of("2026-11-10T07:00:00+07:00", "2026-11-10T00:00:00", "not-a-time")) {
            assertThatThrownBy(() -> TimeInputs.utc(invalid, "transactionAt"))
                    .isInstanceOf(InputValidationException.class);
        }
        assertThat(TimeInputs.expiry(Instant.parse(timestamp), 90, ChronoUnit.DAYS, "link_ttl_days"))
                .isEqualTo(Instant.parse(timestamp).plus(90, ChronoUnit.DAYS));
        assertThatThrownBy(() -> TimeInputs.expiry(
                        Instant.parse(timestamp), Integer.MAX_VALUE, ChronoUnit.DAYS, "link_ttl_days"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> TimeInputs.expiry(Instant.MAX, 1, ChronoUnit.DAYS, "link_ttl_days"))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void enforcesCanonicalUuidOpaqueKeysAndUniqueCaseSensitiveSkus() {
        assertThat(InputNormalizer.uuid("550E8400-E29B-41D4-A716-446655440000", "id")
                        .toString())
                .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThatThrownBy(() -> InputNormalizer.uuid("1-1-1-1-1", "id")).isInstanceOf(InputValidationException.class);
        assertThat(InputNormalizer.idempotencyKey("a".repeat(16))).hasSize(16);
        assertThat(InputNormalizer.idempotencyKey("a".repeat(200))).hasSize(200);
        for (String invalid : List.of("a".repeat(15), "a".repeat(201), " white-space-key- ", "non-ascii-key-😀")) {
            assertThatThrownBy(() -> InputNormalizer.idempotencyKey(invalid))
                    .isInstanceOf(InputValidationException.class);
        }
        InputNormalizer.uniqueSkus(List.of("A", "a"));
        assertThatThrownBy(() -> InputNormalizer.uniqueSkus(List.of("A", " A ")))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> InputNormalizer.uniqueSkus(List.of())).isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> InputNormalizer.uniqueSkus(java.util.stream.IntStream.range(0, 10)
                        .mapToObj(Integer::toString)
                        .toList()))
                .isInstanceOf(InputValidationException.class);
    }
}
