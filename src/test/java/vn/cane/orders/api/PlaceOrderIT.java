package vn.cane.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.CaneApplication;
import vn.cane.fixture.LinkTestKeys;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.orders.application.OrderLinkService;

/** Real HTTP/security, role grants, PostgreSQL locks/rollback and Redis invalidation. */
@Testcontainers
class PlaceOrderIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate sql;
    private static StringRedisTemplate redis;
    private static HttpClient http;
    private static String base;

    static void start() throws Exception {
        String database = "cane_place_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        sql = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        sql.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        sql.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api,cane_worker");
        sql.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        var app = new SpringApplication(CaneApplication.class);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(LinkTestKeys.with(Map.of(
                "CANE_DB_URL",
                url,
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
        base = "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort() + "/api/v1/";
        redis = context.getBean(StringRedisTemplate.class);
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (http != null) http.close();
        if (context != null) context.close();
    }

    @BeforeEach
    void fixtures() throws Exception {
        // A fresh database per case preserves the immutable audit triggers without bypassing them.
        start();
        var defaults = resource("/admin/wp02/settings-default.json");
        defaults.properties().forEach(e -> setting(e.getKey(), e.getValue()));
        for (var scenario : resource("/admin/wp02/settings-scenarios.json").get("scenarios"))
            if (scenario.get("name").asString().equals("complete"))
                scenario.get("patch").properties().forEach(e -> setting(e.getKey(), e.getValue()));
        setting("accepting_orders", JSON.booleanNode(true));
        for (var p : resource("/catalog/wp02/seed-fixture.json").get("products"))
            sql.update(
                    "INSERT INTO cane.products(sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope) VALUES (?,?,?,?,100,?,?)",
                    p.get("sku").asString(),
                    p.get("name").asString(),
                    p.get("unitLabel").asString(),
                    p.get("priceVnd").asLong(),
                    p.get("isActive").asBoolean(),
                    p.get("deliveryScope").asString());
        String ipDigest = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest("127.0.0.1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // Runtime ACL deliberately forbids KEYS/FLUSH. Delete only keys this fixture owns.
        redis.delete(List.of(
                "cane:test:cache:prices:v1", "cane:test:limit:quote:" + ipDigest, "cane:test:limit:place:" + ipDigest));
    }

    @Test
    void quoteIsReadOnlyWithNullFeesNearGuardsAndAuthoritativePrices() throws Exception {
        var input = input("delivery", "cod");
        var quote = body(post("checkout/quote", input, null));
        assertThat(quote.get("canSubmit").asBoolean()).isTrue();
        assertThat(quote.toString()).doesNotContain("stockQty", "available", "accountNo");
        assertThat(count("orders")).isZero();
        assertThat(count("business_events")).isZero();
        setting("shipping_fee_vnd", JSON.nullNode());
        quote = body(post("checkout/quote", input, null));
        assertThat(quote.get("shippingFeeVnd").isNull()).isTrue();
        assertThat(quote.get("totalVnd").isNull()).isTrue();
        assertThat(body(post("checkout/quote", input("pickup", "cod"), null))
                        .get("shippingFeeVnd")
                        .asLong())
                .isZero();
        input.putArray("items").addObject().put("sku", "missing").put("quantity", 1);
        quote = body(post("checkout/quote", input, null));
        assertThat(quote.get("subtotalVnd").isNull()).isTrue();
        assertThat(quote.get("items").get(0).get("name").isNull()).isTrue();
        var defaults = resource("/admin/wp02/settings-scenarios.json");
        for (var scenario : defaults.get("scenarios"))
            if (scenario.get("name").asString().equals("complete"))
                scenario.get("patch").properties().forEach(e -> setting(e.getKey(), e.getValue()));
        var zones = JSON.readTree(
                sql.queryForObject("SELECT value::text FROM cane.settings WHERE key='delivery_zones'", String.class));
        String far = null;
        for (var z : zones)
            if (!z.get("allowNear").asBoolean()) far = z.get("wardCode").asString();
        if (far != null) {
            input = input("delivery", "cod");
            ((ObjectNode) input.get("address")).put("wardCode", far);
            input.putArray("items").addObject().put("sku", nearSku()).put("quantity", 1);
            quote = body(post("checkout/quote", input, null));
            assertThat(quote.get("canSubmit").asBoolean()).isFalse();
            assertThat(quote.get("itemErrors").toString()).contains("OUTSIDE_NEAR_AREA");
        }
        var fake = input("pickup", "cod");
        fake.put("totalVnd", 1);
        ((ObjectNode) fake.get("items").get(0)).put("unitPriceVnd", 1);
        var actual = body(post("checkout/quote", fake, null));
        assertThat(actual.get("totalVnd").asLong()).isEqualTo(price());
        setting("free_ship_threshold_vnd", JSON.valueToTree(price()));
        assertThat(body(post("checkout/quote", input("delivery", "cod"), null))
                        .get("shippingFeeVnd")
                        .asLong())
                .isZero();
    }

    @Test
    void allFourCombinationsSnapshotAndOutboxAreCommittedWithoutPaymentReceipts() throws Exception {
        var config = JSON.readTree(
                sql.queryForObject("SELECT jsonb_object_agg(key,value)::text FROM cane.settings", String.class));
        var itemSnapshot = sql.queryForMap("""
                SELECT sku,name AS name_snapshot,unit_label AS unit_label_snapshot,
                  price_vnd AS unit_price_vnd,1 AS quantity,delivery_scope AS delivery_scope_snapshot
                FROM cane.products WHERE sku=?
                """, sku());
        for (String fulfillment : List.of("delivery", "pickup"))
            for (String method : List.of("cod", "bank_transfer")) {
                var command = order(fulfillment, method);
                String key = key();
                var response = post("orders", command, key);
                assertThat(response.statusCode()).isEqualTo(201);
                var result = body(response);
                assertThat(result.get("order").get("status").asString())
                        .isEqualTo(method.equals("cod") ? "confirmed" : "awaiting_payment");
                assertThat(result.get("order").get("paymentStatus").asString()).isEqualTo("unpaid");
                assertThat(result.get("order").get("expiresAt").isNull()).isTrue();
                assertThat(result.get("bankInstructions").isNull()).isEqualTo(method.equals("cod"));
                if (method.equals("bank_transfer"))
                    assertThat(result.get("order").get("paymentReminderAt").isString())
                            .isTrue();
                String token = result.get("viewUrl").asString().split("#t=", 2)[1];
                context.getBean(OrderLinkService.class).authorizeRead(token);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(result.toString()).doesNotContain("recipient", "requestHash", "stockQty", "bank_snapshot");
                UUID orderId = sql.queryForObject(
                        "SELECT id FROM cane.orders WHERE order_number=?",
                        UUID.class,
                        result.get("order").get("orderNumber").asString());
                assertThat(sql.queryForMap("""
                                SELECT sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,delivery_scope_snapshot
                                FROM cane.order_items WHERE order_id=?
                                """, orderId)).isEqualTo(itemSnapshot);
                var snapshots = JSON.readTree(sql.queryForObject("""
                        SELECT jsonb_build_object('address',address,'store',store_snapshot,'bank',bank_snapshot)::text
                        FROM cane.orders WHERE id=?
                        """, String.class, orderId));
                var expectedAddress = fulfillment.equals("delivery")
                        ? JSON.createObjectNode()
                                .put(
                                        "wardCode",
                                        command.get("address").get("wardCode").asString())
                                .put(
                                        "wardName",
                                        config.get("delivery_zones")
                                                .get(0)
                                                .get("name")
                                                .asString())
                                .put(
                                        "line1",
                                        command.get("address").get("line1").asString())
                        : JSON.nullNode();
                assertThat(snapshots.get("address")).isEqualTo(expectedAddress);
                assertThat(snapshots.get("store"))
                        .isEqualTo(fulfillment.equals("pickup") ? config.get("store") : JSON.nullNode());
                assertThat(snapshots.get("bank"))
                        .isEqualTo(method.equals("bank_transfer") ? config.get("bank_details") : JSON.nullNode());
                assertThat(sql.queryForList("""
                                SELECT n.kind,n.channel,n.recipient FROM cane.notification_outbox n
                                JOIN cane.business_events e ON e.id=n.event_id AND e.order_id=n.order_id
                                WHERE n.order_id=? AND e.kind='order_placed'
                                """, orderId))
                        .containsExactlyInAnyOrder(
                                Map.of("kind", "order_placed", "channel", "email", "recipient", "wp03@example.invalid"),
                                Map.of(
                                        "kind",
                                        "owner_new_order",
                                        "channel",
                                        "email",
                                        "recipient",
                                        config.get("owner_notify").get("email").asString()));
                var replay = post("orders", command, key);
                assertThat(replay.statusCode()).isEqualTo(200);
                assertThat(body(replay)).isEqualTo(result);
            }
        assertThat(count("orders")).isEqualTo(4);
        assertThat(count("order_items")).isEqualTo(4);
        assertThat(count("stock_movements")).isEqualTo(4);
        assertThat(count("business_events")).isEqualTo(4);
        assertThat(count("notification_outbox")).isEqualTo(8);
        assertThat(count("payments")).isZero();
        assertThat(sql.queryForObject("SELECT stock_qty FROM cane.products WHERE sku=?", Integer.class, sku()))
                .isEqualTo(96);
    }

    @Test
    void replayBeforeChangedShopPriceStockAndMethodAndExpiredLinkDoesNotDuplicate() throws Exception {
        String key = key();
        var command = order("pickup", "bank_transfer");
        var first = body(post("orders", command, key));
        setting("accepting_orders", JSON.booleanNode(false));
        setting("enabled_payment_methods", JSON.createArrayNode());
        setting("bank_details", JSON.nullNode());
        sql.update("UPDATE cane.products SET price_vnd=price_vnd+1,stock_qty=0,is_active=false");
        command.put("expectedTotalVnd", price() + 1);
        var replay = post("orders", command, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(body(replay)).isEqualTo(first);
        command.put("note", "changed");
        assertThat(body(post("orders", command, key)).path("error").path("code").asString())
                .isEqualTo("IDEMPOTENCY_MISMATCH");
        command.putNull("note");
        sql.update("UPDATE cane.orders SET link_expires_at=created_at+interval '1 millisecond'");
        assertThat(body(post("orders", command, key)).get("viewUrl").isNull()).isTrue();
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("stock_movements")).isEqualTo(1);
        assertThat(count("business_events")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT unit_price_vnd FROM cane.order_items", Long.class))
                .isEqualTo(first.get("order").get("subtotalVnd").asLong());
    }

    @Test
    void changedTotalAndOneMissingLineRollbackTheWholePurchase() throws Exception {
        var command = order("pickup", "cod");
        command.put("expectedTotalVnd", price() + 1);
        var changed = post("orders", command, key());
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(body(changed).path("error").path("quote").get("canSubmit").asBoolean())
                .isTrue();
        command = order("pickup", "cod");
        ((tools.jackson.databind.node.ArrayNode) command.get("items"))
                .addObject()
                .put("sku", nearSku())
                .put("quantity", 101);
        var failed = post("orders", command, key());
        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(body(failed).path("error").path("itemErrors").toString()).contains("available");
        assertThat(count("orders")).isZero();
        assertThat(count("stock_movements")).isZero();
        assertThat(sql.queryForObject("SELECT min(stock_qty) FROM cane.products", Integer.class))
                .isEqualTo(100);
    }

    @Test
    void lastStockAndSameKeyRacesHaveExactlyOneEffect() throws Exception {
        sql.update("UPDATE cane.products SET stock_qty=1 WHERE sku=?", sku());
        var command = order("pickup", "cod");
        var results = race(command, key(), key());
        assertThat(results.stream().filter(r -> r.statusCode() == 201).count()).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
        sql.update("UPDATE cane.products SET stock_qty=1 WHERE sku=?", sku());
        String key = key();
        results = race(command, key, key);
        for (var result : results) assertThat(result.statusCode()).isIn(200, 201, 409);
        assertThat(post("orders", command, key).statusCode()).isEqualTo(200);
        assertThat(count("orders")).isEqualTo(2);
        assertThat(count("stock_movements")).isEqualTo(2);
        assertThat(count("business_events")).isEqualTo(2);
    }

    @Test
    void outboxFailureRollsBackStockOrderItemsLedgerAndEventThenSameKeySucceeds() throws Exception {
        var command = order("pickup", "cod");
        String key = key();
        http.send(
                HttpRequest.newBuilder(URI.create(base + "prices")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(redis.opsForValue().get("cane:test:cache:prices:v1")).isNotNull();
        sql.execute("REVOKE INSERT ON cane.notification_outbox FROM cane_api");
        try {
            assertThat(post("orders", command, key).statusCode()).isEqualTo(503);
        } finally {
            sql.execute("GRANT INSERT ON cane.notification_outbox TO cane_api");
        }
        for (String table :
                List.of("orders", "order_items", "business_events", "stock_movements", "notification_outbox"))
            assertThat(count(table)).isZero();
        assertThat(sql.queryForObject("SELECT min(stock_qty) FROM cane.products", Integer.class))
                .isEqualTo(100);
        assertThat(redis.opsForValue().get("cane:test:cache:prices:v1")).isNotNull();
        assertThat(post("orders", command, key).statusCode()).isEqualTo(201);
        assertThat(post("orders", command, key).statusCode()).isEqualTo(200);
    }

    @Test
    void redisFailureDoesNotPreventSqlQuoteCommitOrReplay() throws Exception {
        var command = order("pickup", "cod");
        String key = key();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(post("checkout/quote", input("pickup", "cod"), null).statusCode())
                    .isEqualTo(200);
            assertThat(post("orders", command, key).statusCode()).isEqualTo(201);
            assertThat(post("orders", command, key).statusCode()).isEqualTo(200);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("business_events")).isEqualTo(1);
        assertThat(count("stock_movements")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT stock_qty FROM cane.products WHERE sku=?", Integer.class, sku()))
                .isEqualTo(99);
    }

    @Test
    void placeLimitDoesNotCommitTheEleventhOrder() throws Exception {
        var command = order("pickup", "cod");
        for (int i = 0; i < 10; i++)
            assertThat(post("orders", command, key()).statusCode()).isEqualTo(201);
        var limited = post("orders", command, key());
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(count("orders")).isEqualTo(10);
        assertThat(count("stock_movements")).isEqualTo(10);
    }

    @Test
    void newOrderInvalidatesPricesAndQuoteLimitIsApplied() throws Exception {
        http.send(
                HttpRequest.newBuilder(URI.create(base + "prices")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(redis.opsForValue().get("cane:test:cache:prices:v1")).isNotNull();
        assertThat(post("orders", order("pickup", "cod"), key()).statusCode()).isEqualTo(201);
        assertThat(redis.opsForValue().get("cane:test:cache:prices:v1")).isNull();
        for (int i = 0; i < 29; i++)
            assertThat(post("checkout/quote", input("pickup", "cod"), null).statusCode())
                    .isEqualTo(200);
        // order() obtains one quote above: this is request 31 in this isolated Redis window.
        var limited = post("checkout/quote", input("pickup", "cod"), null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    private List<HttpResponse<String>> race(ObjectNode command, String first, String second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var tasks = List.of(first, second).stream()
                    .map(key -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return post("orders", command, key);
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    private ObjectNode input(String fulfillment, String method) {
        var body = JSON.createObjectNode().put("fulfillmentMethod", fulfillment).put("paymentMethod", method);
        body.putArray("items").addObject().put("sku", sku()).put("quantity", 1);
        if (fulfillment.equals("pickup")) body.putNull("address");
        else {
            var zones = JSON.readTree(sql.queryForObject(
                    "SELECT value::text FROM cane.settings WHERE key='delivery_zones'", String.class));
            body.putObject("address")
                    .put("wardCode", zones.get(0).get("wardCode").asString())
                    .put("line1", "MOCK TEST address");
        }
        return body;
    }

    private ObjectNode order(String fulfillment, String method) throws Exception {
        var result = input(fulfillment, method);
        long total = body(post("checkout/quote", result, null)).get("totalVnd").asLong();
        result.putObject("recipient")
                .put("name", "MOCK TEST customer")
                .put("phone", "0338825982")
                .put("email", "wp03@example.invalid");
        return result.putNull("note").put("expectedTotalVnd", total);
    }

    private String sku() {
        return sql.queryForObject(
                "SELECT sku FROM cane.products WHERE delivery_scope='city' ORDER BY sku LIMIT 1", String.class);
    }

    private String nearSku() {
        return sql.queryForObject(
                "SELECT sku FROM cane.products WHERE delivery_scope='near' ORDER BY sku LIMIT 1", String.class);
    }

    private long price() {
        return sql.queryForObject("SELECT price_vnd FROM cane.products WHERE sku=?", Long.class, sku());
    }

    private long count(String table) {
        return sql.queryForObject("SELECT count(*) FROM cane." + table, Long.class);
    }

    private void setting(String key, JsonNode value) {
        sql.update("UPDATE cane.settings SET value=CAST(? AS jsonb) WHERE key=?", value.toString(), key);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode resource(String path) throws Exception {
        try (var stream = PlaceOrderIT.class.getResourceAsStream(path)) {
            return JSON.readTree(stream);
        }
    }

    private JsonNode body(HttpResponse<String> result) {
        return JSON.readTree(result.body());
    }

    private HttpResponse<String> post(String route, JsonNode body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + route))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
