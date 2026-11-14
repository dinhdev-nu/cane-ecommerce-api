package vn.cane.support.validation;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class InputNormalizer {
    private final Validator validator;

    public InputNormalizer(Validator validator) {
        this.validator = validator;
    }

    public static String requiredText(String value, int maximum, String field) {
        if (value == null) {
            throw InputValidationException.malformed(field);
        }
        String normalized = value.strip();
        if (normalized.isBlank() || length(normalized) > maximum) {
            throw InputValidationException.invalid(field, "Chuỗi trống hoặc vượt giới hạn độ dài.");
        }
        return normalized;
    }

    public static String optionalNote(String value, String field) {
        if (value == null || value.strip().isEmpty()) {
            return null;
        }
        return requiredText(value, 1000, field);
    }

    public static String username(String value) {
        return requiredText(value, 100, "username").toLowerCase(Locale.ROOT);
    }

    public static String creationPassword(String value) {
        validateCreationPassword(value);
        return value;
    }

    public static void validateCreationPassword(CharSequence value) {
        if (value == null) {
            throw InputValidationException.malformed("password");
        }
        int length = Character.codePointCount(value, 0, value.length());
        if (length < 15 || length > 128) {
            throw InputValidationException.invalid("password", "Mật khẩu cần 15–128 ký tự.");
        }
    }

    public String email(String value, String field) {
        String email = requiredText(value, 254, field).toLowerCase(Locale.ROOT);
        if (!validator.validate(new EmailValue(email)).isEmpty()) {
            throw InputValidationException.invalid(field, "Địa chỉ email không hợp lệ.");
        }
        return email;
    }

    public static String phone(String value, String field) {
        String phone = requiredText(value, 32, field);
        // Separators are removed; a leading international '+' and all digits are preserved.
        phone = phone.replaceAll("[\\s().-]", "");
        if (!phone.matches("\\+?[0-9]{9,15}")) {
            throw InputValidationException.invalid(field, "Số điện thoại cần 9–15 chữ số.");
        }
        return phone;
    }

    public static UUID uuid(String value, String field) {
        if (value == null) {
            throw InputValidationException.malformed(field);
        }
        String text = value.strip();
        try {
            UUID uuid = UUID.fromString(text);
            if (uuid.toString().equalsIgnoreCase(text)) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {
            // Return a fixed message, never the parsing exception with the rejected value.
        }
        throw InputValidationException.malformed(field);
    }

    public static String idempotencyKey(String value) {
        if (value == null
                || value.length() < 16
                || value.length() > 200
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw InputValidationException.malformed("Idempotency-Key");
        }
        return value;
    }

    public static void uniqueSkus(List<String> skus) {
        if (skus == null || skus.isEmpty() || skus.size() > 9) {
            throw InputValidationException.invalid("items", "Cần từ 1 đến 9 SKU duy nhất.");
        }
        var unique = new HashSet<String>();
        for (int index = 0; index < skus.size(); index++) {
            if (!unique.add(requiredText(skus.get(index), 100, "items[" + index + "].sku"))) {
                throw InputValidationException.invalid("items", "SKU không được trùng.");
            }
        }
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private record EmailValue(@Email String value) {}
}
