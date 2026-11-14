package vn.cane.support.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

public final class RateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimiter.class);
    private final RateCounter redis;
    private final RateCounter process;
    private final String environment;
    private final AtomicBoolean fallback = new AtomicBoolean();

    public RateLimiter(String environment, RateCounter redis, RateCounter process) {
        if (!Set.of("local", "test", "staging", "production").contains(environment)) {
            throw new IllegalArgumentException("Invalid rate limit namespace");
        }
        this.environment = environment;
        this.redis = redis;
        this.process = process;
    }

    public Decision check(RateGroup group, String clientIp) {
        String canonical = ClientIpResolver.canonicalAddress(clientIp);
        String key = "cane:" + environment + ":limit:" + group.key() + ":" + hash(canonical);
        RateCounter.Hit hit;
        boolean usedFallback;
        try {
            hit = redis.increment(key); // No application retry after an ambiguous Redis outcome.
            usedFallback = false;
            if (fallback.compareAndSet(true, false))
                LOG.atInfo().addKeyValue("component", "ratelimit").log("Rate limiter Redis recovered");
        } catch (DataAccessException | IllegalStateException unavailable) {
            if (fallback.compareAndSet(false, true))
                LOG.atWarn().addKeyValue("component", "ratelimit").log("Rate limiter using process fallback");
            hit = process.increment(key);
            usedFallback = true;
        }
        long retryAfter = Math.max(1, (hit.remainingMillis() + 999) / 1000);
        return new Decision(hit.count() <= group.limit(), retryAfter, usedFallback);
    }

    private static String hash(String address) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds, boolean fallback) {}
}
