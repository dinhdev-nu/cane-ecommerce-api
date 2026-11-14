package vn.cane.admin.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/** Raw tokens are request/cookie material only. Never serialize or log them. */
public final class SessionToken {
    private final String value;

    private SessionToken(String value) {
        this.value = value;
    }

    public static SessionToken generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return new SessionToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public static Optional<SessionToken> parse(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) return Optional.empty();
        return Optional.of(new SessionToken(value));
    }

    public String value() {
        return value;
    }

    public byte[] hash() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    @Override
    public String toString() {
        return "SessionToken[redacted]";
    }
}
