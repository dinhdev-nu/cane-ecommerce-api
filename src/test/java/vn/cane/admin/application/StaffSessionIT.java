package vn.cane.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.CaneApplication;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffSessionFilter;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

@Testcontainers
class StaffSessionIT {
    private static final String PREFIX = "/api/v1/admin/__session-test";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private static ConfigurableApplicationContext context;
    private static StaffSessionService sessions;
    private static StaffAuthorization authorization;
    private static Transactions transactions;
    private static JdbcTemplate operations;
    private static HttpClient client;
    private static String origin;

    @BeforeAll
    static void start() throws Exception {
        TestInfrastructure.prepareCore(POSTGRES);
        operations = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        var app = new SpringApplication(CaneApplication.class, ProbeConfiguration.class);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(vn.cane.fixture.LinkTestKeys.with(Map.of(
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
                TestInfrastructure.REDIS_PASSWORD)));
        context = app.run("--spring.profiles.active=test,api", "--spring.main.keep-alive=false");
        sessions = context.getBean(StaffSessionService.class);
        authorization = context.getBean(StaffAuthorization.class);
        transactions = context.getBean(Transactions.class);
        client = HttpClient.newHttpClient();
        origin = "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (context != null) context.close();
    }

    @Test
    void runtimeIssuanceStoresOnlyHashAndExactlyEightHoursAndPollingNeverRefreshes() throws Exception {
        var staff = staff("operator");
        var issued = issue(staff, null);
        assertThat(Duration.between(issued.createdAt(), issued.expiresAt())).isEqualTo(Duration.ofHours(8));
        var row = operations.queryForMap(
                "SELECT * FROM cane.staff_sessions WHERE token_hash=?",
                issued.token().hash());
        assertThat(row.keySet())
                .containsExactlyInAnyOrder("token_hash", "staff_id", "auth_version", "created_at", "expires_at");
        assertThat((byte[]) row.get("token_hash")).isEqualTo(issued.token().hash());
        assertThat(Arrays.equals(
                        (byte[]) row.get("token_hash"),
                        java.util.Base64.getUrlDecoder().decode(issued.token().value())))
                .isFalse();
        String before = sessionSnapshot(issued);
        for (int index = 0; index < 3; index++) {
            var response = get(PREFIX + "/identity", issued.token().value());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains(staff.toString(), "operator")
                    .doesNotContain(issued.token().value(), "tokenHash", "password");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        assertThat(sessionSnapshot(issued)).isEqualTo(before);
        assertThat(issued.toString()).isEqualTo("IssuedSession[redacted]");
    }

    @Test
    void replacingBrowserSessionDeletesOnlyPreviousTokenAndRollsBackAsOneUnit() {
        UUID first = staff("owner");
        UUID second = staff("operator");
        var old = issue(first, null);
        var otherBrowser = issue(first, null);
        var replacement = issue(second, old.token().value());
        assertThat(sessions.authenticate(old.token().value())).isEmpty();
        assertThat(sessions.authenticate(otherBrowser.token().value())).isPresent();
        assertThat(sessions.authenticate(replacement.token().value())
                        .orElseThrow()
                        .actor()
                        .id())
                .isEqualTo(second);
        int count = sessionCount();
        assertThatThrownBy(() -> transactions.write(() -> {
                    sessions.issue(first, 1, replacement.token().value());
                    throw new IllegalStateException("Controlled rollback after session replacement");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sessionCount()).isEqualTo(count);
        assertThat(sessions.authenticate(replacement.token().value())).isPresent();
    }

    @Test
    void revokeIsExactAndIdempotent() {
        UUID staff = staff("operator");
        var one = issue(staff, null);
        var two = issue(staff, null);
        sessions.revoke(one.token().value());
        sessions.revoke(one.token().value());
        sessions.revoke("malformed");
        assertThat(sessions.authenticate(one.token().value())).isEmpty();
        assertThat(sessions.authenticate(two.token().value())).isPresent();
    }

    @Test
    void sqlCurrentRoleAndBankFlagApplyToNextRequestAndToEarlierPrincipal() throws Exception {
        UUID staff = staff("operator");
        var issued = issue(staff, null);
        var principal = sessions.authenticate(issued.token().value()).orElseThrow();
        try {
            setBank(false);
            assertThat(get(PREFIX + "/bank", issued.token().value()).statusCode())
                    .isEqualTo(403);
            setBank(true);
            assertThat(get(PREFIX + "/bank", issued.token().value()).statusCode())
                    .isEqualTo(200);
            assertThat(authorization.requireBankConfirmation(principal).canConfirmBank())
                    .isTrue();
            setBank(false);
            assertThatThrownBy(() -> authorization.requireBankConfirmation(principal))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            operations.update("UPDATE cane.staff_users SET role='owner' WHERE id=?", staff);
            assertThat(get(PREFIX + "/owner", issued.token().value()).statusCode())
                    .isEqualTo(200);
            operations.update("UPDATE cane.staff_users SET role='operator' WHERE id=?", staff);
            assertThat(get(PREFIX + "/owner", issued.token().value()).statusCode())
                    .isEqualTo(403);
        } finally {
            setBank(false);
        }
    }

    @Test
    void inactiveChangedVersionAndExpiryRefuseExistingSessionsWithoutWaitingForCleanup() throws Exception {
        UUID staff = staff("owner");
        var issued = issue(staff, null);
        operations.update(
                "UPDATE cane.staff_users SET failed_count=5,locked_until=now()+interval '15 minutes' WHERE id=?",
                staff);
        assertThat(get(PREFIX + "/identity", issued.token().value()).statusCode())
                .isEqualTo(200);
        operations.update("UPDATE cane.staff_users SET is_active=false WHERE id=?", staff);
        assertAuthRequired(get(PREFIX + "/identity", issued.token().value()));
        operations.update("UPDATE cane.staff_users SET is_active=true,auth_version=2 WHERE id=?", staff);
        assertAuthRequired(get(PREFIX + "/identity", issued.token().value()));
        operations.update("UPDATE cane.staff_users SET auth_version=1 WHERE id=?", staff);
        operations.update("""
                UPDATE cane.staff_sessions SET created_at=statement_timestamp()-interval '8 hours 1 second',
                    expires_at=statement_timestamp()-interval '1 second' WHERE token_hash=?
                """, issued.token().hash());
        assertAuthRequired(get(PREFIX + "/identity", issued.token().value()));
        assertThat(operations.queryForObject(
                        "SELECT count(*) FROM cane.staff_sessions WHERE token_hash=?",
                        Integer.class,
                        issued.token().hash()))
                .isEqualTo(1);
    }

    @Test
    void missingMalformedDuplicateAndBearerCannotActAsCookieOrCreateHttpSession() throws Exception {
        var issued = issue(staff("owner"), null);
        assertAuthRequired(get(PREFIX + "/identity", null));
        assertAuthRequired(get(PREFIX + "/identity", "malformed"));
        var bearer = client.send(
                HttpRequest.newBuilder(URI.create(origin + PREFIX + "/identity"))
                        .header("Authorization", "Bearer " + issued.token().value())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertAuthRequired(bearer);
        var duplicate = client.send(
                HttpRequest.newBuilder(URI.create(origin + PREFIX + "/identity"))
                        .header(
                                "Cookie",
                                SessionCookies.NAME + "=" + issued.token().value() + "; " + SessionCookies.NAME + "="
                                        + issued.token().value())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertAuthRequired(duplicate);
        var valid = get(PREFIX + "/identity", issued.token().value());
        assertThat(valid.body()).contains("\"httpSession\":false");
        assertThat(valid.headers().allValues("Set-Cookie")).noneMatch(value -> value.contains("JSESSIONID"));
        assertAuthRequired(get("/api/v1/admin/not-implemented", null)); // Production Security chain.
        assertThat(get("/api/v1/admin/not-implemented", issued.token().value()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void sqlUnavailableFailsClosedWith503EvenWhenRedisIsHealthyThenRecovers() throws Exception {
        var issued = issue(staff("owner"), null);
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        long started = System.nanoTime();
        try {
            var response = get(PREFIX + "/identity", issued.token().value());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body())
                    .contains("SERVICE_UNAVAILABLE")
                    .doesNotContain(issued.token().value(), "jdbc:");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        } finally {
            POSTGRES.getDockerClient()
                    .unpauseContainerCmd(POSTGRES.getContainerId())
                    .exec();
        }
        assertThat(get(PREFIX + "/identity", issued.token().value()).statusCode())
                .isEqualTo(200);
    }

    @Test
    void changedStaffWhileIssuanceWaitsOnRowLockCannotReceiveSessionForOldVersion() throws Exception {
        UUID staff = staff("operator");
        int before = sessionCount();
        try (var connection = operations.getDataSource().getConnection();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var change = connection.prepareStatement("UPDATE cane.staff_users SET auth_version=2 WHERE id=?")) {
                change.setObject(1, staff);
                change.executeUpdate();
            }
            var creation = executor.submit(() -> transactions.write(() -> sessions.issue(staff, 1, null)));
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (operations.queryForObject("""
                                SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                                WHERE a.datname=current_database() AND a.usename='cane_api' AND NOT l.granted
                        """, Integer.class) == 0) {
                    if (System.nanoTime() >= deadline)
                        throw new AssertionError("Session insert did not wait for staff lock");
                    Thread.sleep(10);
                }
            } finally {
                connection.commit();
            }
            assertThatThrownBy(() -> creation.get(8, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(sessionCount()).isEqualTo(before);
    }

    private static UUID staff(String role) {
        return operations.queryForObject(
                "INSERT INTO cane.staff_users(username,password_hash,role) VALUES (?,?,?) RETURNING id",
                UUID.class,
                "fixture-" + UUID.randomUUID(),
                "fixture-not-a-login-hash",
                role);
    }

    private static StaffSessionService.IssuedSession issue(UUID staff, String previous) {
        return transactions.write(() -> sessions.issue(staff, 1, previous));
    }

    private static int sessionCount() {
        return operations.queryForObject("SELECT count(*) FROM cane.staff_sessions", Integer.class);
    }

    private static String sessionSnapshot(StaffSessionService.IssuedSession issued) {
        return operations.queryForObject(
                "SELECT row_to_json(s)::text FROM cane.staff_sessions s WHERE token_hash=?",
                String.class,
                issued.token().hash());
    }

    private static void setBank(boolean value) {
        operations.update(
                "UPDATE cane.settings SET value=?::jsonb WHERE key='operator_can_confirm_bank'",
                Boolean.toString(value));
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(Duration.ofSeconds(12))
                .GET();
        if (token != null) request.header("Cookie", SessionCookies.NAME + "=" + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertAuthRequired(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().allValues("Set-Cookie"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith(SessionCookies.NAME + "=;")
                        .contains("Max-Age=0", "Path=/", "Secure", "HttpOnly", "SameSite=Lax")
                        .doesNotContain("Domain=", "JSESSIONID"));
        var error = JsonMapper.builder().build().readTree(response.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo("AUTH_REQUIRED");
        assertThat(error.get("requestId").asString())
                .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController(StaffAuthorization authorization) {
            return new ProbeController(authorization);
        }

        @Bean
        @Order(0)
        SecurityFilterChain probeSecurity(
                HttpSecurity http, StaffSessionService sessions, SessionCookies cookies, ApiErrors errors)
                throws Exception {
            return http.securityMatcher(PREFIX + "/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .addFilterAfter(new StaffSessionFilter(sessions, cookies, errors), CsrfFilter.class)
                    .sessionManagement(policy -> policy.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .securityContext(policy -> policy.securityContextRepository(new NullSecurityContextRepository()))
                    .requestCache(cache -> cache.disable())
                    .exceptionHandling(policy -> policy.authenticationEntryPoint((request, response, failure) ->
                            errors.write(request, response, 401, ApiErrorCode.AUTH_REQUIRED)))
                    .build();
        }
    }

    @TestComponent
    @RestController
    @RequestMapping(PREFIX)
    static class ProbeController {
        private final StaffAuthorization authorization;

        ProbeController(StaffAuthorization authorization) {
            this.authorization = authorization;
        }

        @GetMapping("/identity")
        Map<String, Object> identity(
                @AuthenticationPrincipal StaffPrincipal principal, jakarta.servlet.http.HttpServletRequest request) {
            var actor = authorization.requireCurrent(principal);
            return Map.of(
                    "id",
                    actor.id(),
                    "role",
                    actor.role().databaseValue(),
                    "canConfirmBank",
                    actor.canConfirmBank(),
                    "expiresAt",
                    actor.sessionExpiresAt(),
                    "httpSession",
                    request.getSession(false) != null);
        }

        @GetMapping("/owner")
        Map<String, Object> owner(@AuthenticationPrincipal StaffPrincipal principal) {
            return Map.of("id", authorization.requireOwner(principal).id());
        }

        @GetMapping("/bank")
        Map<String, Object> bank(@AuthenticationPrincipal StaffPrincipal principal) {
            return Map.of("id", authorization.requireBankConfirmation(principal).id());
        }
    }
}
