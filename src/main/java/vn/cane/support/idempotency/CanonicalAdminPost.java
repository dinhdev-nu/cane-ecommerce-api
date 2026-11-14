package vn.cane.support.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.support.validation.InputNormalizer;

/** Built from a validated immutable command and the server's resolved route, never raw request JSON. */
public final class CanonicalAdminPost {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String key;
    private final byte[] hash;

    public CanonicalAdminPost(String method, String normalizedRoute, JsonNode normalizedBody, String key) {
        this.key = InputNormalizer.idempotencyKey(key);
        String verb = Objects.requireNonNull(method).toUpperCase(Locale.ROOT);
        if (!verb.equals("POST") || !supportedRoute(normalizedRoute) || !normalizedBody.isObject())
            throw new IllegalArgumentException("A normalized admin business POST is required");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            frame(digest, verb.getBytes(StandardCharsets.UTF_8));
            frame(digest, normalizedRoute.getBytes(StandardCharsets.UTF_8));
            frame(digest, JSON.writeValueAsBytes(canonical(normalizedBody)));
            hash = digest.digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public String key() {
        return key;
    }

    public byte[] hash() {
        return hash.clone();
    }

    private static boolean supportedRoute(String route) {
        if (route == null
                || route.contains("?")
                || route.contains("#")
                || route.contains("\\")
                || route.contains("%")
                || route.contains("//")
                || Arrays.stream(route.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))
                || route.endsWith("/")
                || route.codePoints().anyMatch(Character::isWhitespace)) return false;
        return route.equals("/api/v1/admin/stock-adjustments")
                || route.equals("/api/v1/admin/payments")
                || route.startsWith("/api/v1/admin/payments/")
                || route.startsWith("/api/v1/admin/orders/");
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = JSON.createObjectNode();
            node.propertyNames().stream().sorted().forEach(name -> sorted.set(name, canonical(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        if (node.isNull() || node.isString() || node.isBoolean() || node.isIntegralNumber()) return node;
        throw new IllegalArgumentException("Admin command contains a non-normalized JSON value");
    }

    private static void frame(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    @Override
    public String toString() {
        return "CanonicalAdminPost[redacted]";
    }
}
