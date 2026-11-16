package vn.cane.support.validation;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Stable storage encoding shared by receipt writers and audit readers; never an API reference. */
public final class BankReceiptKey {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BankReceiptKey() {}

    public static String encode(String bankCode, String accountNo, String reference) {
        return JSON.writeValueAsString(List.of(bankCode, accountNo, reference));
    }

    public static String reference(String stored) {
        try {
            var parts = JSON.readTree(stored);
            if (!parts.isArray() || parts.size() != 3) throw new IllegalStateException("Invalid receipt key");
            for (var part : parts)
                if (!part.isString() || part.asString().isBlank())
                    throw new IllegalStateException("Invalid receipt key");
            return parts.get(2).asString();
        } catch (RuntimeException invalid) {
            // A stored-key failure is corruption, never a client input error or an API reference.
            throw new IllegalStateException("Invalid receipt key");
        }
    }
}
