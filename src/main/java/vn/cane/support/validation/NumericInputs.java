package vn.cane.support.validation;

import java.math.BigInteger;

public final class NumericInputs {
    public static final long MAX_VND = 9_007_199_254_740_991L;

    private NumericInputs() {}

    public static long integer(BigInteger value, long minimum, long maximum, String field) {
        if (value == null) {
            throw InputValidationException.malformed(field);
        }
        if (value.compareTo(BigInteger.valueOf(minimum)) < 0 || value.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw InputValidationException.invalid(field, "Số nguyên nằm ngoài miền cho phép.");
        }
        return value.longValueExact();
    }

    public static long vnd(BigInteger value, boolean positive, String field) {
        return integer(value, positive ? 1 : 0, MAX_VND, field);
    }

    public static long addVnd(long left, long right, String field) {
        requireVnd(left, field);
        requireVnd(right, field);
        // Both operands are bounded: the sum fits long, then must fit the JSON money domain.
        return vnd(BigInteger.valueOf(left).add(BigInteger.valueOf(right)), false, field);
    }

    public static long multiplyVnd(long amountVnd, int quantity, String field) {
        requireVnd(amountVnd, field);
        if (quantity < 0) {
            throw InputValidationException.invalid(field, "Số lượng không được âm.");
        }
        return vnd(BigInteger.valueOf(amountVnd).multiply(BigInteger.valueOf(quantity)), false, field);
    }

    private static void requireVnd(long value, String field) {
        vnd(BigInteger.valueOf(value), false, field);
    }
}
