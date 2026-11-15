package vn.cane.catalog.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.application.PricesCache;
import vn.cane.catalog.domain.PublicPrice;

public final class RedisPricesCache implements PricesCache {
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Clock clock;
    private final String key;

    public RedisPricesCache(StringRedisTemplate redis, JsonMapper json, Clock clock, String environment) {
        if (!Set.of("local", "test", "staging", "production").contains(environment)) {
            throw new IllegalArgumentException("Invalid cache environment");
        }
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        key = "cane:" + environment + ":cache:prices:v1";
    }

    @Override
    public Entry get() {
        String encoded = redis.opsForValue().get(key);
        if (encoded == null) return null;
        if (encoded.length() > 16384) throw new IllegalArgumentException("Invalid cache payload");
        var root = json.readTree(encoded);
        requireFields(root, Set.of("expiresAt", "items"));
        Instant expiresAt = Instant.parse(root.get("expiresAt").asString());
        var items = root.get("items");
        if (!items.isArray() || items.size() > 9) throw new IllegalArgumentException("Invalid cache items");
        var parsed = new ArrayList<PublicPrice>();
        var seen = new HashSet<String>();
        for (var item : items) {
            requireFields(item, Set.of("sku", "priceVnd", "availability", "deliveryScope"));
            var price = item.get("priceVnd");
            if (!price.isIntegralNumber() || !price.canConvertToLong())
                throw new IllegalArgumentException("Invalid price");
            var product = new PublicPrice(
                    item.get("sku").asString(),
                    price.asLong(),
                    item.get("availability").asString(),
                    item.get("deliveryScope").asString());
            if (!seen.add(product.sku())) throw new IllegalArgumentException("Duplicate cached SKU");
            parsed.add(product);
        }
        return new Entry(expiresAt, parsed);
    }

    @Override
    public void put(Entry entry) {
        String encoded = json.writeValueAsString(entry);
        Duration remaining = Duration.between(clock.instant(), entry.expiresAt());
        if (remaining.toMillis() > 0) redis.opsForValue().set(key, encoded, Duration.ofMillis(remaining.toMillis()));
    }

    @Override
    public void evict() {
        redis.delete(key);
    }

    private static void requireFields(JsonNode value, Set<String> fields) {
        if (!value.isObject() || !value.propertyNames().equals(fields))
            throw new IllegalArgumentException("Invalid cache fields");
    }
}
