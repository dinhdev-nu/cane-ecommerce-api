package vn.cane.orders.infrastructure.security;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable key ring. No secret getter, logging or mutable active-key switch. Redeploy API/worker together. */
public final class LinkKeyRing {
    private final String activeKid;
    private final Map<String, SecretKey> keys;

    private LinkKeyRing(String activeKid, Map<String, SecretKey> keys) {
        this.activeKid = activeKid;
        this.keys = Map.copyOf(keys);
    }

    public static LinkKeyRing parse(String configuration, String activeKid) {
        try {
            if (configuration == null
                    || configuration.isBlank()
                    || configuration.length() > 65_536
                    || !validKid(activeKid)) throw invalid();
            var json = JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build()
                    .readTree(configuration);
            if (!json.isObject() || json.isEmpty()) throw invalid();
            var keys = new HashMap<String, SecretKey>();
            for (String kid : json.propertyNames()) {
                if (!validKid(kid) || !json.get(kid).isString()) throw invalid();
                String encoded = json.get(kid).asString();
                byte[] bytes = Base64.getUrlDecoder().decode(encoded);
                try {
                    if (bytes.length < 32
                            || !Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(bytes)
                                    .equals(encoded)) throw invalid();
                    keys.put(kid, new SecretKeySpec(bytes, "HmacSHA256"));
                } finally {
                    java.util.Arrays.fill(bytes, (byte) 0);
                }
            }
            if (!keys.containsKey(activeKid)) throw invalid();
            return new LinkKeyRing(activeKid, keys);
        } catch (RuntimeException failure) {
            throw invalid();
        } // Never retain parser/decoder input or its cause.
    }

    static boolean validKid(String kid) {
        return kid != null && kid.matches("[A-Za-z0-9_-]{1,32}");
    }

    String activeKid() {
        return activeKid;
    }

    SecretKey key(String kid) {
        return keys.get(kid);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Order link key configuration is missing or invalid");
    }

    @Override
    public String toString() {
        return "LinkKeyRing[redacted]";
    }
}
