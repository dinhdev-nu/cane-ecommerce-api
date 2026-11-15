package vn.cane.bootstrap.cli;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Best-effort, bounded post-commit DEL. No Boot context, HTTP, scheduler or cache read. */
public final class OperationalPricesInvalidation {
    private OperationalPricesInvalidation() {}

    /** CLI callback only: database work has committed, so cache failure is a warning. */
    public static void evictWithWarning(
            Map<String, String> environment, String expectedEnvironment, PrintWriter output) {
        try {
            evict(environment, expectedEnvironment);
        } catch (RuntimeException failure) {
            output.println(
                    "WARNING: Database changes committed, but prices cache invalidation failed. Check environment and Redis configuration; do not repeat the committed operation.");
            output.flush();
        }
    }

    public static void evict(Map<String, String> environment, String expectedEnvironment) {
        String target = required(environment, "CANE_ENVIRONMENT");
        if (!Set.of("local", "test", "staging", "production").contains(target) || !target.equals(expectedEnvironment))
            throw new IllegalArgumentException("Cache environment does not match operation");
        var server = new RedisStandaloneConfiguration(
                required(environment, "CANE_REDIS_HOST"), Integer.parseInt(required(environment, "CANE_REDIS_PORT")));
        server.setUsername(required(environment, "CANE_REDIS_USER"));
        server.setPassword(required(environment, "CANE_REDIS_PASSWORD"));
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofSeconds(1))
                                .build())
                        .build())
                .shutdownTimeout(Duration.ofMillis(100))
                .build();
        var connection = new LettuceConnectionFactory(server, client);
        try {
            connection.afterPropertiesSet();
            connection.start();
            new StringRedisTemplate(connection).delete("cane:" + target + ":cache:prices:v1");
        } finally {
            connection.destroy();
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Operational cache configuration missing");
        return value;
    }
}
