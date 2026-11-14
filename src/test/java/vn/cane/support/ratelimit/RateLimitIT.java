package vn.cane.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.CaneApplication;
import vn.cane.fixture.TestInfrastructure;

/** Real Redis and Tomcat filters, without adding a production or test login controller. */
@Testcontainers
class RateLimitIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private static ConfigurableApplicationContext context;
    private static HttpClient client;
    private static StringRedisTemplate redis;
    private static RateLimiter limiter;
    private static final AtomicInteger IPS = new AtomicInteger(20);

    @BeforeAll
    static void prepare() throws Exception {
        TestInfrastructure.prepareCore(POSTGRES);
        context = start("");
        redis = context.getBean(StringRedisTemplate.class);
        limiter = context.getBean(RateLimiter.class);
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void close() {
        if (client != null) client.close();
        if (context != null) context.close();
    }

    @ParameterizedTest
    @EnumSource(RateGroup.class)
    void eachGroupEnforcesItsOwnLimitThroughRealRedis(RateGroup group) throws Exception {
        String ip = ip();
        for (int index = 0; index < group.limit(); index++) {
            var accepted = limiter.check(group, ip);
            assertThat(accepted.allowed()).isTrue();
            assertThat(accepted.fallback()).isFalse();
            assertThat(accepted.retryAfterSeconds()).isBetween(1L, 60L);
        }
        var rejected = limiter.check(group, ip);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.fallback()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(redis.opsForValue().get(key(group, ip))).isEqualTo(Integer.toString(group.limit() + 1));
        assertThat(limiter.check(group, ip()).allowed()).isTrue();
        for (var other : RateGroup.values()) {
            if (other != group) assertThat(limiter.check(other, ip).allowed()).isTrue();
        }
    }

    @Test
    void incrementAndTtlAreAtomicDoNotExtendAndExpiredKeysRestart() throws Exception {
        String key = "cane:test:limit:probe:" + UUID.randomUUID();
        var counter = new RedisRateCounter(redis);
        try {
            var first = counter.increment(key);
            assertThat(first.count()).isEqualTo(1);
            assertThat(first.remainingMillis()).isBetween(55_000L, 60_000L);
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(55_000L, 60_000L);
            redis.expire(key, Duration.ofSeconds(5));
            var second = counter.increment(key);
            assertThat(second.count()).isEqualTo(2);
            assertThat(second.remainingMillis()).isBetween(1L, 5_000L);
            redis.expire(key, Duration.ofMillis(10));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (redis.opsForValue().get(key) != null) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Redis key did not expire");
                Thread.sleep(10);
            }
            assertThat(counter.increment(key).count()).isEqualTo(1);
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void concurrentRequestsHaveOneIncrementEachAndExactlyFiveAreAdmitted() throws Exception {
        String ip = ip();
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 100; index++)
                futures.add(executor.submit(() -> {
                    start.await();
                    var result = limiter.check(RateGroup.LOGIN, ip);
                    assertThat(result.fallback()).isFalse();
                    if (result.allowed()) allowed.incrementAndGet();
                    return null;
                }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(allowed).hasValue(5);
        assertThat(redis.opsForValue().get(key(RateGroup.LOGIN, ip))).isEqualTo("100");
        assertThat(redis.getExpire(key(RateGroup.LOGIN, ip), TimeUnit.MILLISECONDS))
                .isBetween(1L, 60_000L);
    }

    @Test
    void httpLoginCountsOnceBeforeCsrfAndUntrustedForwardingCannotBypassLimit() throws Exception {
        String key = key(RateGroup.LOGIN, "127.0.0.1");
        redis.delete(key);
        try {
            for (int index = 0; index < 5; index++)
                assertThat(post(context, "/api/v1/admin/auth/login", "192.0.2." + index)
                                .statusCode())
                        .isEqualTo(403);
            var response = post(context, "/api/v1/admin/auth/login", "198.51.100.1");
            assertRateLimited(response);
            assertThat(redis.opsForValue().get(key)).isEqualTo("6");
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void encodedLoginPathCannotEscapeTheSameCounter() throws Exception {
        String key = key(RateGroup.LOGIN, "127.0.0.1");
        redis.delete(key);
        try {
            for (int index = 0; index < 5; index++)
                assertThat(post(context, "/api/v1/admin/auth/login").statusCode())
                        .isEqualTo(403);
            assertRateLimited(post(context, "/api/v1/admin/auth/l%6fgin"));
            assertThat(redis.opsForValue().get(key)).isEqualTo("6");
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void trustedChainCombinesHeaderLinesAndStopsAtNearestUntrustedPeer() throws Exception {
        try (var trusted = start("127.0.0.1/32,10.0.0.0/24")) {
            String realIp = ip();
            for (int index = 0; index < 5; index++)
                assertThat(post(trusted, "/api/v1/admin/auth/login", "198.51.100." + index, realIp + ", 10.0.0.2")
                                .statusCode())
                        .isEqualTo(403);
            assertRateLimited(post(trusted, "/api/v1/admin/auth/login", "203.0.113.100", realIp + ", 10.0.0.2"));
            assertThat(redis.opsForValue().get(key(RateGroup.LOGIN, realIp))).isEqualTo("6");
            assertThat(post(trusted, "/api/v1/admin/auth/login", ip() + ", 10.0.0.2")
                            .statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    void redisTimeoutFallsBackWithinFiniteTimeAndRecovers() {
        String ip = ip();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        long started = System.nanoTime();
        try {
            var result = limiter.check(RateGroup.LOGIN, ip);
            assertThat(result).isEqualTo(new RateLimiter.Decision(true, 60, true));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(limiter.check(RateGroup.LOGIN, ip()).fallback()).isFalse();
    }

    @Test
    void connectionRefusedUsesSameProcessThresholdWithoutRedisRetries() throws Exception {
        int closedPort;
        try (var probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        var connection = new RedisStandaloneConfiguration("127.0.0.1", closedPort);
        var configuration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .shutdownTimeout(Duration.ZERO)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(200))
                                .build())
                        .build())
                .build();
        var factory = new LettuceConnectionFactory(connection, configuration);
        try {
            factory.afterPropertiesSet();
            var failedRedis = new StringRedisTemplate(factory);
            var fallback = new RateLimiter(
                    "test", new RedisRateCounter(failedRedis), new ProcessRateCounter(Clock.systemUTC()));
            String ip = ip();
            for (int index = 0; index < 5; index++)
                assertThat(fallback.check(RateGroup.LOGIN, ip).allowed()).isTrue();
            assertThat(fallback.check(RateGroup.LOGIN, ip).allowed()).isFalse();
        } finally {
            factory.destroy();
        }
    }

    @Test
    void noevictionOutOfMemoryUsesFallbackAndExistingDataSurvives() {
        String prefix = "cane:test:oom:" + UUID.randomUUID() + ":";
        var filled = new ArrayList<String>();
        String payload = "x".repeat(256 * 1024);
        // Build the payload inside Redis: a large client input buffer can itself cause a
        // transient OOM that disappears before the limiter executes.
        var fill = new DefaultRedisScript<Long>("""
                #!lua
                redis.call('SET', KEYS[1], string.rep('x', 262144), 'PX', 60000)
                return 1
                """, Long.class);
        String existingIp = ip();
        limiter.check(RateGroup.LOGIN, existingIp);
        boolean outOfMemory = false;
        try {
            for (int index = 0; index < 1024; index++) {
                String key = prefix + index;
                filled.add(key);
                try {
                    redis.execute(fill, java.util.List.of(key));
                } catch (DataAccessException failure) {
                    assertThat(failure).hasStackTraceContaining("OOM");
                    outOfMemory = true;
                    break;
                }
            }
            assertThat(outOfMemory).isTrue();
            var independent =
                    new RateLimiter("test", new RedisRateCounter(redis), new ProcessRateCounter(Clock.systemUTC()));
            for (String ip : java.util.List.of(existingIp, ip())) {
                for (int index = 0; index < 5; index++)
                    assertThat(independent.check(RateGroup.LOGIN, ip)).satisfies(result -> {
                        assertThat(result.allowed()).isTrue();
                        assertThat(result.fallback()).isTrue();
                    });
                assertThat(independent.check(RateGroup.LOGIN, ip).allowed()).isFalse();
            }
            assertThat(redis.opsForValue().get(filled.getFirst())).isEqualTo(payload);
        } finally {
            redis.delete(filled);
        }
        assertThat(limiter.check(RateGroup.LOGIN, ip()).fallback()).isFalse();
    }

    @Test
    void runtimeAclRefusesOtherNamespacesAndEmptyRedisStateStartsFreshCounter() throws Exception {
        assertThatThrownBy(() -> redis.opsForValue().get("cane:production:limit:login:probe"))
                .isInstanceOf(DataAccessException.class);
        String ip = ip();
        for (int index = 0; index < 6; index++) limiter.check(RateGroup.LOGIN, ip);
        assertThat(limiter.check(RateGroup.LOGIN, ip).allowed()).isFalse();
        redis.delete(key(RateGroup.LOGIN, ip)); // Empty Redis state, without granting FLUSH or evicting another key.
        assertThat(limiter.check(RateGroup.LOGIN, ip).allowed()).isTrue();
    }

    private static void assertRateLimited(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        long retry = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retry).isBetween(1L, 60L);
        String requestId = response.headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        var error = JsonMapper.builder().build().readTree(response.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(error.get("requestId").asString()).isEqualTo(requestId);
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext target, String path, String... forwarding)
            throws Exception {
        int port = ((WebServerApplicationContext) target).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        for (String value : forwarding) request.header("X-Forwarded-For", value);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String ip() {
        return "192.0.2." + IPS.getAndIncrement();
    }

    private static String key(RateGroup group, String ip) throws Exception {
        String digest = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8)));
        return "cane:test:limit:" + group.key() + ":" + digest;
    }

    private static ConfigurableApplicationContext start(String trusted) {
        var app = new SpringApplication(CaneApplication.class);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of(
                "CANE_DB_URL",
                POSTGRES.getJdbcUrl(),
                "CANE_DB_USER",
                "cane_api",
                "CANE_DB_PASSWORD",
                TestInfrastructure.API_PASSWORD,
                "CANE_REDIS_HOST",
                REDIS.getHost(),
                "CANE_REDIS_PORT",
                REDIS.getMappedPort(6379).toString(),
                "CANE_REDIS_USER",
                "cane_runtime",
                "CANE_REDIS_PASSWORD",
                TestInfrastructure.REDIS_PASSWORD));
        return app.run(
                "--spring.profiles.active=test,api",
                "--spring.main.keep-alive=false",
                "--cane.http.trusted-proxies=" + trusted);
    }
}
