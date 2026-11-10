package vn.cane.support.validation;

import java.util.Map;

/** Safe field messages only; never retain the rejected input or a parser/SQL exception. */
public final class InputValidationException extends RuntimeException {
    private final boolean malformed;
    private final Map<String, String> fieldErrors;

    private InputValidationException(boolean malformed, Map<String, String> fieldErrors) {
        super("Invalid input");
        this.malformed = malformed;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public static InputValidationException malformed(String field) {
        return new InputValidationException(true, Map.of(field, "Kiểu hoặc cấu trúc dữ liệu không hợp lệ."));
    }

    public static InputValidationException invalid(String field, String message) {
        return new InputValidationException(false, Map.of(field, message));
    }

    public static InputValidationException invalid(Map<String, String> fieldErrors) {
        return new InputValidationException(false, fieldErrors);
    }

    public boolean isMalformed() {
        return malformed;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
