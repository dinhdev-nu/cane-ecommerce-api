package vn.cane.fixture;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Disposable test configuration only; excluded from the application JAR. */
public final class LinkTestKeys {
    private static final String KEY =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new SecureRandom().generateSeed(32));
    public static final String JSON = "{\"fixture\":\"" + KEY + "\"}";

    private LinkTestKeys() {}

    public static Map<String, Object> properties() {
        return Map.of("LINK_SECRETS", JSON, "LINK_ACTIVE_KID", "fixture");
    }

    public static Map<String, Object> with(Map<String, ?> existing) {
        var result = new HashMap<String, Object>(existing);
        result.putAll(properties());
        return result;
    }
}
