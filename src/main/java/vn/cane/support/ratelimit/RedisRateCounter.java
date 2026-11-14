package vn.cane.support.ratelimit;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisRateCounter implements RateCounter {
    private static final DefaultRedisScript<List> INCREMENT = new DefaultRedisScript<>("""
            #!lua
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], 60000) end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);
    private final StringRedisTemplate redis;

    public RedisRateCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Hit increment(String key) {
        List<?> result = redis.execute(INCREMENT, List.of(key));
        if (result == null
                || result.size() != 2
                || !(result.get(0) instanceof Number count)
                || !(result.get(1) instanceof Number remaining)) {
            throw new IllegalStateException("Invalid Redis rate counter response");
        }
        return new Hit(count.longValue(), remaining.longValue());
    }
}
