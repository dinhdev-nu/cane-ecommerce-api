package vn.cane.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.CaneApplication;
import vn.cane.bootstrap.cli.StorefrontDevSeedCli;
import vn.cane.catalog.application.PricesCache;
import vn.cane.catalog.application.PricesInvalidation;
import vn.cane.catalog.application.PricesReader;
import vn.cane.catalog.application.PublicPricesService;
import vn.cane.catalog.infrastructure.RedisPricesCache;
import vn.cane.catalog.infrastructure.cli.CatalogSeedCommand;
import vn.cane.catalog.infrastructure.cli.InitialStockCli;
import vn.cane.fixture.LinkTestKeys;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.transaction.Transactions;

/** Real HTTP chain, runtime SQL grants, Redis ACL and operational writers, on owned containers. */
@Testcontainers
class PublicStorefrontIT {
    private static final String KEY = "cane:test:cache:prices:v1";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = redisWithStablePort();

    private static GenericContainer<?> redisWithStablePort() {
        // Docker Desktop reassigns a randomly published host port on restart.
        // A free explicit binding keeps the same runtime endpoint for this restart test.
        try (var probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            var container = TestInfrastructure.redis();
            container.setPortBindings(List.of("127.0.0.1:" + probe.getLocalPort() + ":6379"));
            return container;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot allocate isolated Redis test port", failure);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate operations;
    private static StringRedisTemplate redis;
    private static HttpClient http;
    private static String base;
    private static String databaseUrl;

    @TempDir
    Path directory;

    @BeforeEach
    void start() throws Exception {
        String database = "cane_public_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        databaseUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        operations = new JdbcTemplate(
                new DriverManagerDataSource(databaseUrl, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        operations.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        operations.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api,cane_worker");
        operations.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        var app = new SpringApplication(CaneApplication.class);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(LinkTestKeys.with(Map.of(
                "CANE_DB_URL",
                databaseUrl,
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
                TestInfrastructure.REDIS_PASSWORD)));
        context = app.run("--spring.profiles.active=test,api", "--spring.main.keep-alive=false");
        redis = context.getBean(StringRedisTemplate.class);
        http = HttpClient.newHttpClient();
        base = "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort();
        fixtures();
    }

    @AfterEach
    void stop() {
        if (http != null) http.close();
        if (context != null) context.close();
    }

    void fixtures() throws Exception {
        redis.delete(KEY);
        var defaults = resource("/admin/wp02/settings-default.json");
        defaults.properties().forEach(entry -> setting(entry.getKey(), entry.getValue()));
        for (var product : resource("/catalog/wp02/seed-fixture.json").get("products")) {
            operations.update(
                    "INSERT INTO cane.products(sku,name,unit_label,price_vnd,is_active,delivery_scope) VALUES (?,?,?,?,?,?)",
                    product.get("sku").asString(),
                    product.get("name").asString(),
                    product.get("unitLabel").asString(),
                    product.get("priceVnd").asLong(),
                    product.get("isActive").asBoolean(),
                    product.get("deliveryScope").asString());
        }
        for (var item : resource("/catalog/wp02/stock-fixture.json").get("items")) {
            operations.update(
                    "UPDATE cane.products SET stock_qty=? WHERE sku=?",
                    item.get("stockQty").asInt(),
                    item.get("sku").asString());
        }
    }

    @Test
    void publicShopDefaultsCompleteAndChangesAreUncachedAndDoNotWrite() throws Exception {
        String before = snapshot();
        assertThat(body(get("shop"))).isEqualTo(resource("/public/wp02/shop-default.json"));
        assertThat(snapshot()).isEqualTo(before);
        for (var scenario : resource("/admin/wp02/settings-scenarios.json").get("scenarios")) {
            if (scenario.get("name").asString().equals("complete"))
                scenario.get("patch").properties().forEach(e -> setting(e.getKey(), e.getValue()));
        }
        assertThat(body(get("shop"))).isEqualTo(resource("/public/wp02/shop-complete.json"));
        setting("accepting_orders", JSON.valueToTree(false));
        setting("bank_details", JSON.nullNode());
        setting("shipping_fee_vnd", JSON.nullNode());
        var changed = body(get("shop"));
        assertThat(changed.get("acceptingOrders").asBoolean()).isFalse();
        assertThat(changed.get("deliveryEnabled").asBoolean()).isFalse();
        assertThat(changed.get("enabledPaymentMethods").toString()).isEqualTo("[\"cod\"]");
        assertThat(changed.toString()).doesNotContain("accountNo", "owner_notify", "operator_can_confirm_bank");
    }

    @Test
    void pricesAvailabilityAllowlistAndMissingSkuWithNoOperationalWrites() throws Exception {
        String before = snapshot();
        var response = body(get("prices"));
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("serverTime", "items");
        assertThat(Instant.parse(response.get("serverTime").asString())).isBeforeOrEqualTo(Instant.now());
        assertThat(response.get("items")).hasSize(9);
        assertThat(response.get("items")).anySatisfy(item -> {
            assertThat(item.propertyNames())
                    .containsExactlyInAnyOrder("sku", "priceVnd", "availability", "deliveryScope");
        });
        assertThat(response.get("items").toString())
                .contains("\"paused\"", "\"out\"", "\"low\"", "\"in_stock\"")
                .doesNotContain("stockQty", "unitLabel", "name");
        assertThat(snapshot()).isEqualTo(before);
        operations.update("DELETE FROM cane.products WHERE sku='cane-tea'");
        redis.delete(KEY);
        assertThat(body(get("prices")).get("items")).hasSize(8);
    }

    @Test
    void exactGetRoutesOnlyAndNoCookiesOrCorsEvenWithForeignOrigin() throws Exception {
        for (String route : List.of("shop", "prices")) {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/" + route))
                            .header("Origin", "https://foreign.invalid")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            body(response);
            assertThat(response.headers().allValues("set-cookie")).isEmpty();
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .isEmpty();
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD")) {
                var denied = http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/v1/" + route))
                                .method(method, HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(403);
                assertThat(denied.headers().firstValue("cache-control")).contains("no-store");
            }
        }
        for (String route : List.of("shop", "prices")) {
            assertThat(get(route + "?unsupported=1").statusCode()).isEqualTo(400);
            var withBody = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/" + route))
                            .header("Content-Type", "application/json")
                            .method("GET", HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(withBody.statusCode()).isEqualTo(400);
        }
    }

    @Test
    void realRedisFixedTtlMalformedFallbackAndResponseTimeNeverCached() throws Exception {
        var clock = new MutableClock();
        var cache = new RedisPricesCache(redis, JSON, clock, "test");
        var reads = new AtomicInteger();
        PricesReader sql = () -> {
            reads.incrementAndGet();
            return context.getBean(PricesReader.class).read();
        };
        var service = new PublicPricesService(sql, cache, clock);
        var first = service.read();
        long ttl = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 15000L);
        assertThat(redis.opsForValue().get(KEY)).doesNotContain("serverTime", "stockQty", "unitLabel", "actor");
        clock.now = clock.now.plusMillis(14999);
        assertThat(service.read().serverTime()).isAfter(first.serverTime());
        assertThat(reads.get()).isEqualTo(1);
        assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        clock.now = clock.now.plusMillis(1);
        service.read();
        assertThat(reads.get()).isEqualTo(2);
        redis.opsForValue().set(KEY, "{\"items\":[],\"expiresAt\":false,\"private\":true}");
        assertThat(service.read().items()).hasSize(9);
        assertThat(reads.get()).isEqualTo(3);
        var expired = new PricesCache.Entry(clock.now, first.items());
        cache.evict();
        cache.put(expired);
        assertThat(redis.opsForValue().get(KEY)).isNull();
    }

    @Test
    void invalidationIsAfterCommitRollbackDoesNotEvictAndDelFailureCannotUndoCommit() {
        var transactions = context.getBean(Transactions.class);
        var invalidation = context.getBean(PricesInvalidation.class);
        redis.opsForValue().set(KEY, "sentinel");
        transactions.write(() -> {
            invalidation.afterCommit();
            assertThat(redis.opsForValue().get(KEY)).isEqualTo("sentinel");
            return null;
        });
        assertThat(redis.opsForValue().get(KEY)).isNull();
        redis.opsForValue().set(KEY, "sentinel");
        assertThatThrownBy(() -> transactions.write(() -> {
                    invalidation.afterCommit();
                    throw new IllegalStateException("rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("sentinel");
        var broken = new PricesInvalidation(transactions, () -> {
            throw new IllegalStateException("DEL unavailable");
        });
        transactions.write(() -> {
            context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)
                    .update("UPDATE cane.settings SET value='false' WHERE key='accepting_orders'", Map.of());
            broken.afterCommit();
            return null;
        });
        assertThat(operations.queryForObject(
                        "SELECT value::text FROM cane.settings WHERE key='accepting_orders'", String.class))
                .isEqualTo("false");
    }

    @Test
    void oldLoaderAfterCommitEvictionRetainsOriginalDeadlineAndConcurrentMissesNeedNoLock() throws Exception {
        var clock = new MutableClock();
        var cache = new RedisPricesCache(redis, JSON, clock, "test");
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reader = context.getBean(PricesReader.class);
        var service = new PublicPricesService(
                () -> {
                    var result = reader.read();
                    loaded.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Loader barrier");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return result;
                },
                cache,
                clock);
        Instant began = clock.now;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(service::read);
            try {
                assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
                context.getBean(Transactions.class).write(() -> {
                    context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)
                            .update("UPDATE cane.products SET price_vnd=44001 WHERE sku='cane-tea'", Map.of());
                    context.getBean(PricesInvalidation.class).afterCommit();
                    return null;
                });
                clock.now = began.plusSeconds(10);
                new PublicPricesService(reader, cache, clock).read();
            } finally {
                release.countDown();
            }
            old.get(5, TimeUnit.SECONDS);
        }
        assertThat(cache.get().expiresAt()).isEqualTo(began.plusSeconds(15));
        assertThat(cache.get().items()).anySatisfy(item -> {
            assertThat(item.sku()).isEqualTo("cane-tea");
            assertThat(item.priceVnd()).isEqualTo(33000);
        });
        assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).isBetween(1L, 5000L);
        clock.now = began.plusSeconds(15);
        assertThat(new PublicPricesService(reader, cache, clock).read().items()).anySatisfy(item -> {
            assertThat(item.sku()).isEqualTo("cane-tea");
            assertThat(item.priceVnd()).isEqualTo(44001);
        });
        assertThat(cache.get().expiresAt()).isEqualTo(began.plusSeconds(30));
    }

    @Test
    void sqlErrorsAre503WithNoStaleOrDefaultFallbackAndShopDoesNotNeedRedis() throws Exception {
        operations.execute("REVOKE SELECT ON cane.products FROM cane_api");
        operations.execute("REVOKE SELECT ON cane.settings FROM cane_api");
        try {
            for (String route : List.of("shop", "prices")) {
                var response = get(route);
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(JSON.readTree(response.body()).at("/error/code").asString())
                        .isEqualTo("SERVICE_UNAVAILABLE");
                assertThat(response.headers().firstValue("cache-control")).contains("no-store");
                assertThat(response.headers().firstValue("x-request-id")).isPresent();
            }
        } finally {
            operations.execute("GRANT SELECT ON cane.products,cane.settings TO cane_api");
        }
    }

    @Test
    void realTimeoutFallsBackToSqlWithinBoundAndShopRemainsIndependent() throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        long started = System.nanoTime();
        try {
            assertThat(body(get("shop")).get("storeName").isNull()).isTrue();
            assertThat(body(get("prices")).get("items")).hasSize(9);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(body(get("prices")).get("items")).hasSize(9);
    }

    @Test
    void connectionRefusedFallsBackAndEmptyRedisAfterRestartRepopulates() throws Exception {
        int closedPort;
        try (var probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .shutdownTimeout(Duration.ZERO)
                .clientOptions(io.lettuce.core.ClientOptions.builder()
                        .socketOptions(io.lettuce.core.SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(200))
                                .build())
                        .build())
                .build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", closedPort), client);
        try {
            factory.afterPropertiesSet();
            var failed = new RedisPricesCache(new StringRedisTemplate(factory), JSON, Clock.systemUTC(), "test");
            long started = System.nanoTime();
            assertThat(new PublicPricesService(context.getBean(PricesReader.class), failed, Clock.systemUTC())
                            .read()
                            .items())
                    .hasSize(9);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        } finally {
            factory.destroy();
        }
        body(get("prices"));
        assertThat(redis.opsForValue().get(KEY)).isNotNull();
        REDIS.getDockerClient().restartContainerCmd(REDIS.getContainerId()).exec();
        ((LettuceConnectionFactory) redis.getConnectionFactory()).resetConnection();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    try (var connection = redis.getConnectionFactory().getConnection()) {
                        assertThat(connection.ping()).isEqualTo("PONG");
                    }
                });
        assertThat(redis.opsForValue().get(KEY)).isNull();
        assertThat(body(get("prices")).get("items")).hasSize(9);
        assertThat(redis.opsForValue().get(KEY)).isNotNull();
    }

    @Test
    void noevictionOomDoesNotFailSqlAndOtherNamespacesAreDenied() throws Exception {
        assertThatThrownBy(() -> redis.opsForValue().get("cane:production:cache:prices:v1"))
                .isInstanceOf(DataAccessException.class);
        var filled = new java.util.ArrayList<String>();
        var fill = new DefaultRedisScript<Long>(
                "redis.call('SET', KEYS[1], string.rep('x', 262144), 'PX', 60000); return 1", Long.class);
        boolean exhausted = false;
        try {
            for (int index = 0; index < 1024; index++) {
                String key = "cane:test:oom:prices:" + index;
                filled.add(key);
                try {
                    redis.execute(fill, List.of(key));
                } catch (DataAccessException failure) {
                    assertThat(failure).hasStackTraceContaining("OOM");
                    exhausted = true;
                    break;
                }
            }
            assertThat(exhausted).isTrue();
            assertThat(body(get("prices")).get("items")).hasSize(9);
            assertThat(redis.opsForValue().get(filled.getFirst())).hasSize(262144);
        } finally {
            redis.delete(filled);
        }
        redis.delete(KEY);
        assertThat(body(get("prices")).get("items")).hasSize(9);
        assertThat(redis.opsForValue().get(KEY)).isNotNull();
    }

    @Test
    void realOperationalSeedAndInitialStockEvictHotCacheAndReplayDoesNotEvict() throws Exception {
        var environment = operationalEnvironment();
        operations.update("DELETE FROM cane.products");
        redis.opsForValue().set(KEY, "hot-cache");
        var output = new PrintWriter(new StringWriter());
        var seed = new String[] {
            "--manifest",
            Path.of("src/test/resources/catalog/wp02/seed-fixture.json")
                    .toAbsolutePath()
                    .toString(),
            "--environment",
            "test"
        };
        assertThat(CatalogSeedCommand.run(seed, environment, output)).isZero();
        assertThat(redis.opsForValue().get(KEY)).isNull();
        redis.opsForValue().set(KEY, "hot-cache");
        assertThat(CatalogSeedCommand.run(seed, environment, output)).isZero();
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("hot-cache");
        UUID owner = UUID.randomUUID();
        operations.update(
                "INSERT INTO cane.staff_users(id,username,password_hash,role) VALUES (?,?,'fixture-hash','owner')",
                owner,
                "fixture-" + owner);
        var manifest = JSON.createObjectNode()
                .put("dataKind", "fixture")
                .put("ownerId", owner.toString())
                .put("sku", "cane-tea")
                .put("quantity", 6)
                .put("reason", "WP02 cache writer verification")
                .put("key", "wp02-initial-stock-test");
        var path = directory.resolve("initial-stock.json");
        Files.writeString(path, manifest.toString());
        var stock = new String[] {"--manifest", path.toString(), "--environment", "test"};
        assertThat(InitialStockCli.run(stock, environment, output)).isZero();
        assertThat(redis.opsForValue().get(KEY)).isNull();
        redis.opsForValue().set(KEY, "hot-cache");
        assertThat(InitialStockCli.run(stock, environment, output)).isZero();
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("hot-cache");
        assertThat(operations.queryForObject("SELECT stock_qty FROM cane.products WHERE sku='cane-tea'", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void devConfigurationLoadsAllSettingsAndStockButRefusesLiveEnvironments() throws Exception {
        var environment = operationalEnvironment();
        var args = new String[] {
            "--settings",
            Path.of("scripts/dev/wp02-settings.json").toAbsolutePath().toString(),
            "--stock",
            Path.of("src/test/resources/catalog/wp02/stock-fixture.json")
                    .toAbsolutePath()
                    .toString(),
            "--environment",
            "test"
        };
        var output = new PrintWriter(new StringWriter());
        redis.opsForValue().set(KEY, "old");
        assertThat(StorefrontDevSeedCli.run(args, environment, output)).isZero();
        assertThat(redis.opsForValue().get(KEY)).isNull();
        var shop = body(get("shop"));
        assertThat(shop.get("storeName").asString()).isEqualTo("Cane Shop");
        assertThat(shop.get("deliveryEnabled").asBoolean()).isTrue();
        assertThat(shop.get("shippingFeeVnd").asInt()).isEqualTo(15000);
        assertThat(shop.get("deliveryZones")).hasSize(4);
        assertThat(shop.get("enabledPaymentMethods").toString()).contains("cod", "bank_transfer");
        assertThat(shop.toString()).doesNotContain("0338825982\",\"account", "accountNo", "bank_details");
        assertThat(operations.queryForObject(
                        "SELECT value->>'accountNo' FROM cane.settings WHERE key='bank_details'", String.class))
                .isEqualTo("0338825982");
        var snapshot = snapshot();
        args[5] = "production";
        environment.put("CANE_ENVIRONMENT", "production");
        assertThat(StorefrontDevSeedCli.run(args, environment, output)).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(snapshot);
    }

    private static Map<String, String> operationalEnvironment() {
        var environment = new HashMap<String, String>();
        environment.put("CANE_OPERATIONS_DB_URL", databaseUrl);
        environment.put("CANE_OPERATIONS_DB_USER", "cane_migrator");
        environment.put("CANE_OPERATIONS_DB_PASSWORD", TestInfrastructure.MIGRATION_PASSWORD);
        environment.put("CANE_API_DB_USER", "cane_api");
        environment.put("CANE_WORKER_DB_USER", "cane_worker");
        environment.put("CANE_ENVIRONMENT", "test");
        environment.put("CANE_REDIS_HOST", REDIS.getHost());
        environment.put("CANE_REDIS_PORT", REDIS.getMappedPort(6379).toString());
        environment.put("CANE_REDIS_USER", "cane_runtime");
        environment.put("CANE_REDIS_PASSWORD", TestInfrastructure.REDIS_PASSWORD);
        return environment;
    }

    private static HttpResponse<String> get(String route) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/" + route))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.headers().firstValue("x-request-id")).isPresent();
        return JSON.readTree(response.body());
    }

    private static JsonNode resource(String path) throws Exception {
        return JSON.readTree(PublicStorefrontIT.class.getResourceAsStream(path));
    }

    private static void setting(String key, JsonNode value) {
        operations.update("UPDATE cane.settings SET value=?::jsonb WHERE key=?", value.toString(), key);
    }

    private static String snapshot() {
        return operations.queryForObject("""
                SELECT jsonb_build_object('products',(SELECT jsonb_agg(to_jsonb(p) ORDER BY sku) FROM cane.products p),
                    'orders',(SELECT count(*) FROM cane.orders),'events',(SELECT count(*) FROM cane.business_events),
                    'outbox',(SELECT count(*) FROM cane.notification_outbox),'movements',(SELECT count(*) FROM cane.stock_movements))::text
                """, String.class);
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
