package vn.cane.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.CaneApplication;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffCsrf;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.transaction.Transactions;

/** Real auth routes and the production SecurityFilterChain; no substitute controller or chain. */
@Testcontainers
class AdminAuthIT {
    private static final String TRUSTED = "https://cane.example.test";
    private static final String ME = "/api/v1/admin/me";
    private static final String LOGOUT = "/api/v1/admin/auth/logout";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate operations;
    private static StaffSessionService sessions;
    private static Transactions transactions;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        TestInfrastructure.prepareCore(POSTGRES);
        operations = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
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
        context = app.run("--spring.profiles.active=test,api", "--spring.main.keep-alive=false");
        sessions = context.getBean(StaffSessionService.class);
        transactions = context.getBean(Transactions.class);
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (context != null) context.close();
    }

    @Test
    void anonymousMeBootstrapsCsrfWith401JsonAndDoesNotCreateSession() throws Exception {
        int before = countSessions();
        var response = send("GET", ME, null, Map.of());
        assertError(response, 401, "AUTH_REQUIRED");
        String token = response.headers().firstValue(StaffCsrf.HEADER).orElseThrow();
        assertThat(response.headers().allValues("Set-Cookie"))
                .singleElement()
                .asString()
                .contains(StaffCsrf.COOKIE + "=" + token, "Secure", "Path=/", "SameSite=Lax")
                .doesNotContain("HttpOnly", "Domain=", "JSESSIONID");
        assertThat(response.body()).doesNotContain("username", "sessionExpiresAt", token);
        assertThat(countSessions()).isEqualTo(before);
    }

    @Test
    void meReturnsAllowlistedCurrentPermissionsAndNeverExtendsSession() throws Exception {
        var issued = issue("operator");
        String before = sessionSnapshot(issued);
        var bootstrap = bootstrap();
        var headers = Map.of("Cookie", bootstrap.cookie() + "; " + sessionCookie(issued));
        var response = send("GET", ME, null, headers);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder("user", "permissions", "csrfToken", "sessionExpiresAt");
        assertThat(body.get("user").propertyNames()).containsExactlyInAnyOrder("id", "username", "role");
        assertThat(body.get("user").get("role").asString()).isEqualTo("operator");
        assertThat(body.get("permissions").propertyNames()).containsExactly("canConfirmBank");
        assertThat(body.get("permissions").get("canConfirmBank").asBoolean()).isFalse();
        assertThat(body.get("csrfToken").asString()).isEqualTo(bootstrap.token());
        assertThat(response.headers().firstValue(StaffCsrf.HEADER)).contains(bootstrap.token());
        assertThat(java.time.Instant.parse(body.get("sessionExpiresAt").asString()))
                .isEqualTo(issued.expiresAt());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        try {
            operations.update("UPDATE cane.settings SET value='true' WHERE key='operator_can_confirm_bank'");
            assertThat(JsonMapper.builder()
                            .build()
                            .readTree(send("GET", ME, null, headers).body())
                            .get("permissions")
                            .get("canConfirmBank")
                            .asBoolean())
                    .isTrue();
        } finally {
            operations.update("UPDATE cane.settings SET value='false' WHERE key='operator_can_confirm_bank'");
        }
        assertThat(sessionSnapshot(issued)).isEqualTo(before);
        assertThat(response.body()).doesNotContain(issued.token().value(), "tokenHash", "authVersion", "password");
        assertCommon(response);
    }

    @Test
    void expiredMeStillBootstrapsAndClearsOnlySessionCookie() throws Exception {
        var issued = issue("owner");
        operations.update(
                "UPDATE cane.staff_sessions SET created_at=now()-interval '9 hours',expires_at=now()-interval '1 hour' WHERE token_hash=?",
                issued.token().hash());
        var response = send("GET", ME, null, Map.of("Cookie", sessionCookie(issued)));
        assertError(response, 401, "AUTH_REQUIRED");
        assertThat(response.headers().firstValue(StaffCsrf.HEADER)).isPresent();
        assertThat(response.headers().allValues("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie).contains(SessionCookies.NAME + "=;", "Max-Age=0", "HttpOnly"));
        assertThat(countSession(issued)).isEqualTo(1);
    }

    @Test
    void logoutDeletesExactlyCurrentSessionAndClearsBothCookiesAfterCommit() throws Exception {
        var issued = issue("owner");
        UUID staff = operations.queryForObject(
                "SELECT staff_id FROM cane.staff_sessions WHERE token_hash=?",
                UUID.class,
                issued.token().hash());
        var otherBrowser = transactions.write(() -> sessions.issue(staff, 1, null));
        var bootstrap = bootstrap();
        var response = send("POST", LOGOUT, "{}", valid(bootstrap, issued));
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertCleared(response, SessionCookies.NAME, true);
        assertCleared(response, StaffCsrf.COOKIE, false);
        assertThat(response.headers().firstValue(StaffCsrf.HEADER)).isEmpty();
        assertThat(countSession(issued)).isZero();
        assertThat(countSession(otherBrowser)).isEqualTo(1);
        assertCommon(response);
        assertError(send("GET", ME, null, Map.of("Cookie", sessionCookie(issued))), 401, "AUTH_REQUIRED");
        assertThat(send("GET", ME, null, Map.of("Cookie", sessionCookie(otherBrowser)))
                        .statusCode())
                .isEqualTo(200);
        assertNoBusinessWrites();
    }

    @Test
    void logoutMissingRevokedOrMalformedSessionIsIdempotentButStillNeedsCsrfAndOrigin() throws Exception {
        var issued = issue("operator");
        sessions.revoke(issued.token().value());
        for (String cookie : new String[] {"", sessionCookie(issued), SessionCookies.NAME + "=malformed"}) {
            var bootstrap = bootstrap();
            var headers = new LinkedHashMap<>(valid(bootstrap, null));
            if (!cookie.isEmpty()) headers.put("Cookie", bootstrap.cookie() + "; " + cookie);
            var response = send("POST", LOGOUT, "{}", headers);
            assertThat(response.statusCode()).isEqualTo(204);
            assertCleared(response, StaffCsrf.COOKIE, false);
            assertCleared(response, SessionCookies.NAME, true);
        }
        assertError(send("POST", LOGOUT, "{}", Map.of()), 403, "FORBIDDEN");
        assertNoBusinessWrites();
    }

    @Test
    void invalidOriginOrFetchSiteCannotLogoutEvenWithValidCsrfAndReferer() throws Exception {
        var issued = issue("owner");
        var bootstrap = bootstrap();
        String before = sessionSnapshot(issued);
        for (String origin : new String[] {
            "", "null", "https://evil.test", "http://cane.example.test", "https://cane.example.test:444"
        }) {
            var headers = new LinkedHashMap<>(valid(bootstrap, issued));
            headers.remove("Origin");
            if (!origin.isEmpty()) headers.put("Origin", origin);
            assertError(send("POST", LOGOUT, "{}", headers), 403, "FORBIDDEN");
        }
        var headers = new LinkedHashMap<>(valid(bootstrap, issued));
        headers.put("Origin", "https://evil.test");
        headers.put("Referer", TRUSTED + "/admin");
        headers.put("X-Forwarded-Host", "cane.example.test");
        assertError(send("POST", LOGOUT, "{}", headers), 403, "FORBIDDEN");
        headers.put("Origin", TRUSTED);
        headers.put("Sec-Fetch-Site", "cross-site");
        assertError(send("POST", LOGOUT, "{}", headers), 403, "FORBIDDEN");
        assertThat(sessionSnapshot(issued)).isEqualTo(before);
        headers.remove("Origin");
        headers.remove("Sec-Fetch-Site");
        assertThat(send("POST", LOGOUT, "{}", headers).statusCode()).isEqualTo(204);
    }

    @Test
    void csrfMustBeCanonicalHeaderMatchingOneCookieAndRejectsQueryOrBodyFallback() throws Exception {
        var issued = issue("operator");
        var bootstrap = bootstrap();
        String before = sessionSnapshot(issued);
        var headers = new LinkedHashMap<>(valid(bootstrap, issued));
        headers.remove(StaffCsrf.HEADER);
        assertError(send("POST", LOGOUT, "{}", headers), 403, "FORBIDDEN");
        assertError(send("POST", LOGOUT + "?_csrf=" + bootstrap.token(), "{}", headers), 403, "FORBIDDEN");
        assertError(send("POST", LOGOUT, "{\"_csrf\":\"" + bootstrap.token() + "\"}", headers), 403, "FORBIDDEN");
        for (String token :
                new String[] {"malformed", UUID.randomUUID().toString(), bootstrap.token() + "," + bootstrap.token()}) {
            headers.put(StaffCsrf.HEADER, token);
            assertError(send("POST", LOGOUT, "{}", headers), 403, "FORBIDDEN");
        }
        headers.put(StaffCsrf.HEADER, bootstrap.token());
        headers.put("Cookie", sessionCookie(issued));
        assertError(send("POST", LOGOUT, "{}", headers), 403, "FORBIDDEN");
        headers.put("Cookie", bootstrap.cookie() + "; " + bootstrap.cookie() + "; " + sessionCookie(issued));
        assertError(send("POST", LOGOUT, "{}", headers), 403, "FORBIDDEN");
        assertThat(sessionSnapshot(issued)).isEqualTo(before);
        assertNoBusinessWrites();
    }

    @Test
    void everyAdminWriteIncludingLoginIsProtectedAndOtherRoutesRemainClosed() throws Exception {
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            assertError(send(method, "/api/v1/admin/unknown", "{}", Map.of()), 403, "FORBIDDEN");
        }
        assertError(send("POST", "/api/v1/admin/auth/login", "{}", Map.of()), 403, "FORBIDDEN");
        var bootstrap = bootstrap();
        assertError(send("POST", "/api/v1/admin/auth/login", "{}", valid(bootstrap, null)), 400, "INVALID_INPUT");
        assertError(send("GET", "/api/v1/admin/unknown", null, Map.of()), 401, "AUTH_REQUIRED");
        assertError(send("POST", "/api/v1/checkout/place", "{}", Map.of()), 403, "FORBIDDEN");
        var response = send("GET", "/api/v1/shop", null, Map.of("Origin", "https://evil.test"));
        assertError(response, 403, "FORBIDDEN");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertNoBusinessWrites();
    }

    @Test
    void authContractsRejectUnknownFieldsAndBodyQueryWithoutChangingSession() throws Exception {
        var issued = issue("owner");
        var bootstrap = bootstrap();
        var valid = valid(bootstrap, issued);
        for (String body : new String[] {"{\"ignored\":true}", "null", "[]", "{} {}", ""}) {
            assertError(send("POST", LOGOUT, body, valid), 400, "INVALID_INPUT");
        }
        assertError(send("POST", LOGOUT + "?extra=1", "{}", valid), 400, "INVALID_INPUT");
        assertError(send("GET", ME + "?extra=1", null, Map.of()), 400, "INVALID_INPUT");
        assertError(send("GET", ME, "{}", Map.of()), 400, "INVALID_INPUT");
        assertThat(countSession(issued)).isEqualTo(1);
    }

    @Test
    void failedLogoutTransactionPreservesSessionAndCookiesThenCanRecover() throws Exception {
        var issued = issue("owner");
        var bootstrap = bootstrap();
        String before = sessionSnapshot(issued);
        try (var connection = operations.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement(
                    "SELECT token_hash FROM cane.staff_sessions WHERE token_hash=? FOR UPDATE")) {
                lock.setBytes(1, issued.token().hash());
                lock.executeQuery().close();
            }
            var response = send("POST", LOGOUT, "{}", valid(bootstrap, issued));
            assertError(response, 503, "SERVICE_UNAVAILABLE");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            connection.rollback();
        }
        assertThat(sessionSnapshot(issued)).isEqualTo(before);
        assertThat(send("POST", LOGOUT, "{}", valid(bootstrap, issued)).statusCode())
                .isEqualTo(204);
        assertThat(countSession(issued)).isZero();
    }

    private static Bootstrap bootstrap() throws Exception {
        var response = send("GET", ME, null, Map.of());
        assertError(response, 401, "AUTH_REQUIRED");
        String token = response.headers().firstValue(StaffCsrf.HEADER).orElseThrow();
        return new Bootstrap(token, StaffCsrf.COOKIE + "=" + token);
    }

    private static StaffSessionService.IssuedSession issue(String role) {
        UUID staff = operations.queryForObject(
                "INSERT INTO cane.staff_users(username,password_hash,role) VALUES (?,?,?) RETURNING id",
                UUID.class,
                "fixture-" + UUID.randomUUID(),
                "fixture-not-a-login-hash",
                role);
        return transactions.write(() -> sessions.issue(staff, 1, null));
    }

    private static Map<String, String> valid(Bootstrap bootstrap, StaffSessionService.IssuedSession session) {
        return Map.of(
                "Origin",
                TRUSTED,
                StaffCsrf.HEADER,
                bootstrap.token(),
                "Cookie",
                bootstrap.cookie() + (session == null ? "" : "; " + sessionCookie(session)));
    }

    private static String sessionCookie(StaffSessionService.IssuedSession session) {
        return SessionCookies.NAME + "=" + session.token().value();
    }

    private static int countSessions() {
        return operations.queryForObject("SELECT count(*) FROM cane.staff_sessions", Integer.class);
    }

    private static int countSession(StaffSessionService.IssuedSession issued) {
        return operations.queryForObject(
                "SELECT count(*) FROM cane.staff_sessions WHERE token_hash=?",
                Integer.class,
                issued.token().hash());
    }

    private static String sessionSnapshot(StaffSessionService.IssuedSession issued) {
        return operations.queryForObject(
                "SELECT row_to_json(s)::text FROM cane.staff_sessions s WHERE token_hash=?",
                String.class,
                issued.token().hash());
    }

    private static void assertNoBusinessWrites() {
        for (String table : new String[] {"business_events", "notification_outbox", "stock_movements"}) {
            assertThat(operations.queryForObject("SELECT count(*) FROM cane." + table, Integer.class))
                    .isZero();
        }
    }

    private static HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(12))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var error = JsonMapper.builder().build().readTree(response.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.get("requestId").asString())
                .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertCommon(response);
    }

    private static void assertCommon(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie").toString()).doesNotContain("JSESSIONID");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
    }

    private static void assertCleared(HttpResponse<String> response, String name, boolean httpOnly) {
        var matching = new ArrayList<String>();
        for (String cookie : response.headers().allValues("Set-Cookie"))
            if (cookie.startsWith(name + "=;")) matching.add(cookie);
        assertThat(matching).isNotEmpty().allSatisfy(cookie -> {
            assertThat(cookie).contains("Secure", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
            // Tomcat may render Servlet maxAge=0 as an already-expired Expires attribute.
            assertThat(java.net.HttpCookie.parse(cookie).getFirst().getMaxAge()).isZero();
            assertThat(cookie.contains("HttpOnly")).isEqualTo(httpOnly);
        });
    }

    private record Bootstrap(String token, String cookie) {}
}
