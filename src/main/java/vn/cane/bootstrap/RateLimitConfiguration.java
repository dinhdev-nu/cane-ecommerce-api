package vn.cane.bootstrap;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import vn.cane.support.ratelimit.ClientIpResolver;
import vn.cane.support.ratelimit.ProcessRateCounter;
import vn.cane.support.ratelimit.RateLimiter;
import vn.cane.support.ratelimit.RedisRateCounter;

@Configuration(proxyBeanMethods = false)
@Profile("api & !worker")
public class RateLimitConfiguration {
    @Bean
    RateLimiter rateLimiter(Environment environment, StringRedisTemplate redis, Clock clock) {
        return new RateLimiter(
                environment.getRequiredProperty("cane.environment"),
                new RedisRateCounter(redis),
                new ProcessRateCounter(clock));
    }

    @Bean
    ClientIpResolver clientIpResolver(Environment environment) {
        String ranges = environment.getProperty("cane.http.trusted-proxies", "");
        return new ClientIpResolver(
                ranges.isBlank()
                        ? List.of()
                        : Arrays.stream(ranges.split(",", -1))
                                .map(String::strip)
                                .toList());
    }
}
