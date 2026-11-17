package vn.cane.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import vn.cane.admin.application.StaffSessionService;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffCsrf;
import vn.cane.support.transaction.Transactions;

/** Shared real HTTP/runtime-role infrastructure for WP06; no externally sent messages. */
@Testcontainers
public abstract class AdminHttpFixture {
    @Container
    protected static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    protected static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static ConfigurableApplicationContext context;
    protected static JdbcTemplate sql;
    protected static HttpClient http;
    protected static String base;
    protected StaffSessionService.IssuedSession owner;
    protected StaffSessionService.IssuedSession operator;
    protected String csrf;

    @BeforeAll
    static void startAdminApi() throws Exception {
        String database = "cane_admin_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
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
        http = HttpClient.newHttpClient();
        for (var p : resource("/catalog/wp02/seed-fixture.json").get("products")) {
            sql.update(
                    "INSERT INTO cane.products(sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope) VALUES (?,?,?,?,10000,?,?)",
                    p.path("sku").asString(),
                    p.path("name").asString(),
                    p.path("unitLabel").asString(),
                    p.path("priceVnd").asLong(),
                    p.path("isActive").asBoolean(),
                    p.path("deliveryScope").asString());
        }
    }

    @AfterAll
    static void closeAdminApi() {
        if (http != null) http.close();
        if (context != null) context.close();
    }

    @BeforeEach
    void resetAdminConfig() throws Exception {
        resource("/admin/wp02/settings-default.json").properties().forEach(e -> setting(e.getKey(), e.getValue()));
        for (var s : resource("/admin/wp02/settings-scenarios.json").get("scenarios")) {
            if (s.path("name").asString().equals("complete"))
                s.path("patch").properties().forEach(e -> setting(e.getKey(), e.getValue()));
        }
        setting("accepting_orders", JSON.booleanNode(true));
        String ip = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest("127.0.0.1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        context.getBean(StringRedisTemplate.class)
                .delete(List.of(
                        "cane:test:cache:prices:v1", "cane:test:limit:quote:" + ip, "cane:test:limit:place:" + ip));
        owner = issue("owner");
        operator = issue("operator");
        csrf = send("GET", "admin/me", null, null, null, false)
                .headers()
                .firstValue(StaffCsrf.HEADER)
                .orElseThrow();
    }

    protected StaffSessionService.IssuedSession issue(String role) {
        UUID id = sql.queryForObject(
                "INSERT INTO cane.staff_users(username,password_hash,role) VALUES (?,?,?) RETURNING id",
                UUID.class,
                "fixture-" + key(),
                "fixture-not-login-hash",
                role);
        return context.getBean(Transactions.class)
                .write(() -> context.getBean(StaffSessionService.class).issue(id, 1, null));
    }

    protected UUID staff(StaffSessionService.IssuedSession session) {
        return context.getBean(StaffSessionService.class)
                .authenticate(session.token().value())
                .orElseThrow()
                .actor()
                .id();
    }

    protected Order place(String fulfillment, String method) throws Exception {
        String sku = sql.queryForObject(
                "SELECT sku FROM cane.products WHERE delivery_scope='city' AND is_active ORDER BY sku LIMIT 1",
                String.class);
        return place(fulfillment, method, Map.of(sku, 2));
    }

    protected Order place(String fulfillment, String method, Map<String, Integer> quantities) throws Exception {
        var body = JSON.createObjectNode().put("fulfillmentMethod", fulfillment).put("paymentMethod", method);
        var items = body.putArray("items");
        quantities.forEach((sku, quantity) -> items.addObject().put("sku", sku).put("quantity", quantity));
        if (fulfillment.equals("pickup")) body.putNull("address");
        else
            body.putObject("address")
                    .put(
                            "wardCode",
                            setting("delivery_zones").get(0).path("wardCode").asString())
                    .put("line1", "MOCK WP06 address");
        long total = ok(send("POST", "checkout/quote", body, null, null, false))
                .path("totalVnd")
                .asLong();
        body.putObject("recipient")
                .put("name", "MOCK WP06 " + key())
                .put("phone", "0338825982")
                .put("email", "wp06@example.invalid");
        body.put("expectedTotalVnd", total).putNull("note");
        var response = send("POST", "orders", body, key(), null, false);
        assertThat(response.statusCode()).as("Place failed").isEqualTo(201);
        String number =
                JSON.readTree(response.body()).path("order").path("orderNumber").asString();
        UUID id = sql.queryForObject("SELECT id FROM cane.orders WHERE order_number=?", UUID.class, number);
        return new Order(id, number, total, quantities.keySet().iterator().next());
    }

    protected ObjectNode cash(String expected, long amount) {
        return JSON.createObjectNode()
                .put("expectedStatus", expected)
                .put("method", "cash")
                .put("amountVnd", amount)
                .putNull("reference")
                .putNull("bankReceipt");
    }

    protected void setting(String key, JsonNode value) {
        sql.update("UPDATE cane.settings SET value=?::jsonb WHERE key=?", value.toString(), key);
    }

    protected JsonNode setting(String key) {
        return JSON.readTree(
                sql.queryForObject("SELECT value::text FROM cane.settings WHERE key=?", String.class, key));
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    protected static JsonNode resource(String path) throws Exception {
        try (var input = AdminHttpFixture.class.getResourceAsStream(path)) {
            return JSON.readTree(input);
        }
    }

    protected static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .as(
                        "HTTP error code %s",
                        JSON.readTree(response.body())
                                .path("error")
                                .path("code")
                                .asString())
                .isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return JSON.readTree(response.body());
    }

    protected static void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString())
                .isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    protected HttpResponse<String> get(String route, StaffSessionService.IssuedSession session) throws Exception {
        return send("GET", route, null, null, session, false);
    }

    protected HttpResponse<String> post(
            String route, JsonNode body, String key, StaffSessionService.IssuedSession session) throws Exception {
        return send("POST", route, body, key, session, true);
    }

    protected HttpResponse<String> send(
            String method,
            String route,
            JsonNode body,
            String key,
            StaffSessionService.IssuedSession session,
            boolean withCsrf)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(20));
        if (body != null) builder.header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        String cookie = session == null
                ? ""
                : SessionCookies.NAME + "=" + session.token().value();
        if (withCsrf) {
            builder.header("Origin", "https://cane.example.test").header(StaffCsrf.HEADER, csrf);
            cookie += (cookie.isEmpty() ? "" : "; ") + StaffCsrf.COOKIE + "=" + csrf;
        }
        if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        return http.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected record Order(UUID id, String number, long total, String sku) {}
}
