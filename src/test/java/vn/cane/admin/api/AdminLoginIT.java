package vn.cane.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.CaneApplication;
import vn.cane.admin.application.StaffLoginRepository;
import vn.cane.admin.application.StaffLoginService;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.admin.domain.SessionToken;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffCsrf;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.transaction.Transactions;

/** Production routes/chain and application races on real PostgreSQL with the runtime API grants. */
@Testcontainers
class AdminLoginIT {
    private static final String ORIGIN = "https://cane.example.test";
    private static final String LOGIN = "/api/v1/admin/auth/login";
    private static final String ME = "/api/v1/admin/me";
    private static final String PASSWORD = "  mật khẩu thử riêng 😀  ";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate operations;
    private static PasswordEncoder passwords;
    private static StaffLoginService logins;
    private static StaffSessionService sessions;
    private static Transactions transactions;
    private static HttpClient client;
    private static String base;
    private static String passwordHash;
    private static String loginRateKey;

    @BeforeAll
    static void start() throws Exception {
        TestInfrastructure.prepareCore(POSTGRES);
        operations = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        var app = new SpringApplication(CaneApplication.class);
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
        passwords = context.getBean(PasswordEncoder.class);
        logins = context.getBean(StaffLoginService.class);
        sessions = context.getBean(StaffSessionService.class);
        transactions = context.getBean(Transactions.class);
        passwordHash = passwords.encode(PASSWORD);
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort();
        loginRateKey = "cane:test:limit:login:"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest("127.0.0.1".getBytes(StandardCharsets.UTF_8)));
    }

    @BeforeEach
    void independentIpWindow() {
        // Delete only this test's known IP counter; keep the real limiter/ACL enabled.
        context.getBean(StringRedisTemplate.class).delete(loginRateKey);
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (context != null) context.close();
    }

    @Test
    void successfulLoginReplacesOnlyCurrentBrowserAndRotatesCsrfAfterCommit() throws Exception {
        var staff = staff("operator");
        operations.update("UPDATE cane.staff_users SET failed_count=3 WHERE id=?", staff.id());
        var old = transactions.write(() -> sessions.issue(staff.id(), 1, null));
        var other = transactions.write(() -> sessions.issue(staff.id(), 1, null));
        var bootstrap = bootstrap();
        var response =
                login("  " + staff.username().toUpperCase(java.util.Locale.ROOT) + "  ", PASSWORD, bootstrap, old);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("user", "permissions", "sessionExpiresAt");
        assertThat(body.get("user").propertyNames()).containsExactlyInAnyOrder("id", "username", "role");
        assertThat(body.get("user").get("username").asString()).isEqualTo(staff.username());
        assertThat(body.get("user").get("role").asString()).isEqualTo("operator");
        assertThat(body.get("permissions").get("canConfirmBank").asBoolean()).isFalse();
        String sessionCookie = cookie(response, SessionCookies.NAME);
        String rawToken = sessionCookie.substring(sessionCookie.indexOf('=') + 1);
        var token = SessionToken.parse(rawToken).orElseThrow();
        var lifetime = operations.queryForObject(
                "SELECT created_at,expires_at FROM cane.staff_sessions WHERE token_hash=?",
                (row, number) -> new Instant[] {
                    row.getObject("created_at", OffsetDateTime.class).toInstant(),
                    row.getObject("expires_at", OffsetDateTime.class).toInstant()
                },
                token.hash());
        var created = lifetime[0];
        var expires = lifetime[1];
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofHours(8));
        assertThat(Instant.parse(body.get("sessionExpiresAt").asString())).isEqualTo(expires);
        String setSession = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(SessionCookies.NAME + "="))
                .findFirst()
                .orElseThrow();
        assertThat(setSession)
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/")
                .doesNotContain("Domain=");
        assertThat(java.net.HttpCookie.parse(setSession).getFirst().getMaxAge()).isBetween(1L, 28800L);
        var freshCsrf = response.headers().firstValue(StaffCsrf.HEADER).orElseThrow();
        assertThat(freshCsrf).isNotEqualTo(bootstrap.token());
        assertThat(cookie(response, StaffCsrf.COOKIE)).isEqualTo(StaffCsrf.COOKIE + "=" + freshCsrf);
        assertThat(count(old)).isZero();
        assertThat(count(other)).isEqualTo(1);
        assertThat(state(staff).failed()).isZero();
        assertThat(response.body())
                .doesNotContain(rawToken, freshCsrf, passwordHash, PASSWORD, "authVersion", "tokenHash");
        assertThat(send("GET", ME, null, Map.of("Cookie", sessionCookie)).statusCode())
                .isEqualTo(200);
        var fresh = new Bootstrap(freshCsrf);
        assertError(
                send(
                        "POST",
                        "/api/v1/admin/auth/logout",
                        "{}",
                        headers(bootstrap, sessionCookie + "; " + fresh.cookie())),
                403,
                "FORBIDDEN");
        assertThat(send(
                                "POST",
                                "/api/v1/admin/auth/logout",
                                "{}",
                                headers(fresh, sessionCookie + "; " + fresh.cookie()))
                        .statusCode())
                .isEqualTo(204);
        assertNoBusinessWrites();
    }

    @Test
    void fifthWrongPasswordCommitsLockAndExistingSessionStaysValid() throws Exception {
        var staff = staff("owner");
        var old = transactions.write(() -> sessions.issue(staff.id(), 1, null));
        var bootstrap = bootstrap();
        for (int attempt = 1; attempt <= 5; attempt++) {
            Instant before = dbTime();
            var response = login(staff.username(), "wrong", bootstrap, old);
            assertError(response, 401, "AUTH_REQUIRED");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(state(staff).failed()).isEqualTo(attempt);
            if (attempt < 5) assertThat(state(staff).until()).isNull();
            else assertThat(state(staff).until()).isBetween(before.plusSeconds(900), dbTime().plusSeconds(900));
        }
        var locked = state(staff);
        assertThat(logins.login(staff.username(), PASSWORD, null)).isEmpty();
        assertThat(logins.login(staff.username(), "wrong", null)).isEmpty();
        assertThat(state(staff)).isEqualTo(locked);
        assertThat(send(
                                "GET",
                                ME,
                                null,
                                Map.of(
                                        "Cookie",
                                        SessionCookies.NAME + "=" + old.token().value()))
                        .statusCode())
                .isEqualTo(200);
        assertError(login(staff.username(), PASSWORD, bootstrap, old), 429, "RATE_LIMITED");
        assertThat(state(staff)).isEqualTo(locked);
    }

    @Test
    void unknownInactiveWrongAndLockedShareExactGenericError() throws Exception {
        var active = staff("owner");
        var inactive = staff("operator");
        var locked = staff("owner");
        operations.update("UPDATE cane.staff_users SET is_active=false WHERE id=?", inactive.id());
        operations.update(
                "UPDATE cane.staff_users SET failed_count=5,locked_until=clock_timestamp()+interval '15 minutes' WHERE id=?",
                locked.id());
        var bootstrap = bootstrap();
        for (var entry : Map.of(
                        "unknown-" + UUID.randomUUID(),
                        PASSWORD,
                        inactive.username(),
                        PASSWORD,
                        active.username(),
                        "wrong",
                        locked.username(),
                        PASSWORD)
                .entrySet()) {
            var response = login(entry.getKey(), entry.getValue(), bootstrap, null);
            assertError(response, 401, "AUTH_REQUIRED");
            var error = JsonMapper.builder().build().readTree(response.body()).get("error");
            assertThat(error.get("message").asString())
                    .isEqualTo(vn.cane.support.web.ApiErrorCode.AUTH_REQUIRED.message());
            assertThat(response.body())
                    .doesNotContain(entry.getKey(), PASSWORD, "failedCount", "lockedUntil", passwordHash);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        assertThat(state(inactive).failed()).isZero();
        assertThat(state(active).failed()).isEqualTo(1);
        assertThat(state(locked).failed()).isEqualTo(5);
    }

    @Test
    void expiredLockResetsBeforeWrongAttemptAndCorrectLoginClearsIt() {
        var staff = staff("owner");
        operations.update(
                "UPDATE cane.staff_users SET failed_count=5,locked_until=clock_timestamp()-interval '1 microsecond' WHERE id=?",
                staff.id());
        assertThat(logins.login(staff.username(), "wrong", null)).isEmpty();
        assertThat(state(staff)).isEqualTo(new State(1, null));
        var success = logins.login(staff.username(), PASSWORD, null).orElseThrow();
        assertThat(success.actor().canConfirmBank()).isTrue();
        assertThat(state(staff)).isEqualTo(new State(0, null));
        operations.update(
                "UPDATE cane.staff_users SET failed_count=5,locked_until=clock_timestamp()-interval '1 microsecond' WHERE id=?",
                staff.id());
        assertThat(logins.login(staff.username(), PASSWORD, null)).isPresent();
        assertThat(state(staff)).isEqualTo(new State(0, null));
    }

    @Test
    void concurrentWrongPasswordsCannotLoseCountsOrExtendFirstLock() throws Exception {
        var staff = staff("owner");
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(n -> executor.submit(() -> {
                        ready.countDown();
                        await(start);
                        return logins.login(staff.username(), "wrong", null);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures)
                assertThat(future.get(20, TimeUnit.SECONDS)).isEmpty();
        }
        var locked = state(staff);
        assertThat(locked.failed()).isEqualTo(5);
        assertThat(locked.until()).isNotNull();
        assertThat(logins.login(staff.username(), "wrong", null)).isEmpty();
        assertThat(state(staff)).isEqualTo(locked);
        assertThat(staffSessions(staff)).isZero();
    }

    @Test
    void passwordChangedAfterHashingForcesRollbackAndFreshVerification() throws Exception {
        var staff = staff("owner");
        var hashed = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var calls = new AtomicInteger();
        PasswordEncoder gated = new PasswordEncoder() {
            public String encode(CharSequence value) {
                return passwords.encode(value);
            }

            public boolean matches(CharSequence raw, String encoded) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                boolean matches = passwords.matches(raw, encoded);
                if (calls.incrementAndGet() == 1) {
                    hashed.countDown();
                    await(resume);
                }
                return matches;
            }
        };
        var racing = new StaffLoginService(context.getBean(StaffLoginRepository.class), gated, sessions, transactions);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> racing.login(staff.username(), PASSWORD, null));
            assertThat(hashed.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                String replacement = passwords.encode("a different password fixture");
                operations.update(
                        "UPDATE cane.staff_users SET password_hash=?,auth_version=auth_version+1 WHERE id=?",
                        replacement,
                        staff.id());
            } finally {
                resume.countDown();
            }
            assertThat(future.get(15, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(calls).hasValue(2);
        assertThat(state(staff).failed()).isEqualTo(1);
        assertThat(staffSessions(staff)).isZero();
    }

    @Test
    void accessChangedAfterHashingCannotCreateSessionOrIncrementInactiveAccount() throws Exception {
        var staff = staff("operator");
        var changed = new AtomicBoolean();
        var verifications = new AtomicInteger();
        PasswordEncoder racing = new PasswordEncoder() {
            public String encode(CharSequence value) {
                return passwords.encode(value);
            }

            public boolean matches(CharSequence raw, String encoded) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                verifications.incrementAndGet();
                boolean matched = passwords.matches(raw, encoded);
                if (changed.compareAndSet(false, true))
                    operations.update(
                            "UPDATE cane.staff_users SET is_active=false,auth_version=auth_version+1 WHERE id=?",
                            staff.id());
                return matched;
            }
        };
        var service =
                new StaffLoginService(context.getBean(StaffLoginRepository.class), racing, sessions, transactions);
        assertThat(service.login(staff.username(), PASSWORD, null)).isEmpty();
        assertThat(verifications).hasValue(2);
        assertThat(state(staff).failed()).isZero();
        assertThat(staffSessions(staff)).isZero();
    }

    @Test
    void failedSessionReplacementRollsBackCounterResetAndInsertWithoutIssuingCookies() throws Exception {
        var staff = staff("owner");
        operations.update("UPDATE cane.staff_users SET failed_count=3 WHERE id=?", staff.id());
        var old = transactions.write(() -> sessions.issue(staff.id(), 1, null));
        var bootstrap = bootstrap();
        try (var connection = operations.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement(
                    "SELECT token_hash FROM cane.staff_sessions WHERE token_hash=? FOR UPDATE")) {
                lock.setBytes(1, old.token().hash());
                lock.executeQuery().close();
            }
            var response = login(staff.username(), PASSWORD, bootstrap, old);
            assertError(response, 503, "SERVICE_UNAVAILABLE");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            connection.rollback();
        }
        assertThat(state(staff).failed()).isEqualTo(3);
        assertThat(count(old)).isEqualTo(1);
        assertThat(staffSessions(staff)).isEqualTo(1);
        assertThat(login(staff.username(), PASSWORD, bootstrap, old).statusCode())
                .isEqualTo(200);
    }

    @Test
    void csrfOriginAndIpRateRejectBeforeAccountMutation() throws Exception {
        var staff = staff("owner");
        var bootstrap = bootstrap();
        String body = body(staff.username(), "wrong");
        assertError(send("POST", LOGIN, body, Map.of()), 403, "FORBIDDEN");
        assertError(send("POST", LOGIN, body, Map.of("Origin", ORIGIN)), 403, "FORBIDDEN");
        assertError(
                send(
                        "POST",
                        LOGIN,
                        body,
                        Map.of(
                                "Origin",
                                "https://evil.test",
                                "Cookie",
                                bootstrap.cookie(),
                                StaffCsrf.HEADER,
                                bootstrap.token())),
                403,
                "FORBIDDEN");
        assertThat(state(staff).failed()).isZero();
        assertError(login(staff.username(), "wrong", bootstrap, null), 401, "AUTH_REQUIRED");
        assertError(login(staff.username(), "wrong", bootstrap, null), 401, "AUTH_REQUIRED");
        var limited = login(staff.username(), "wrong", bootstrap, null);
        assertError(limited, 429, "RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(state(staff).failed()).isEqualTo(2);
    }

    @Test
    void strictLoginValidationPreservesPasswordWhitespaceAndCodepointLimit() throws Exception {
        var staff = staff("owner");
        var bootstrap = bootstrap();
        for (String invalid : new String[] {
            "{}", "{\"username\":17,\"password\":\"x\"}", "{\"username\":\"x\",\"password\":\"x\",\"role\":\"owner\"}"
        }) {
            assertError(send("POST", LOGIN, invalid, headers(bootstrap, bootstrap.cookie())), 400, "INVALID_INPUT");
        }
        assertError(login(staff.username(), "😀".repeat(129), bootstrap, null), 422, "INVALID_INPUT");
        assertError(login(staff.username(), PASSWORD.strip(), bootstrap, null), 401, "AUTH_REQUIRED");
        assertThat(state(staff).failed()).isEqualTo(1);
        assertThat(logins.login(staff.username(), PASSWORD, null)).isPresent();
        assertThat(logins.login(staff.username(), "", null)).isEmpty();
        assertThat(state(staff).failed()).isEqualTo(1);
    }

    @Test
    void malformedStoredHashProducesGeneric401WithCryptoDiagnosticsDisabled() throws Exception {
        var staff = staff("owner");
        operations.update(
                "UPDATE cane.staff_users SET password_hash='malformed-sensitive-hash' WHERE id=?", staff.id());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                org.springframework.security.crypto.argon2.Argon2PasswordEncoder.class);
        assertThat(logger.getEffectiveLevel()).isEqualTo(ch.qos.logback.classic.Level.OFF);
        var response = login(staff.username(), PASSWORD, bootstrap(), null);
        assertError(response, 401, "AUTH_REQUIRED");
        assertThat(response.body()).doesNotContain("malformed-sensitive-hash", PASSWORD, staff.username());
        assertThat(state(staff).failed()).isEqualTo(1);
    }

    private static Staff staff(String role) {
        String username = "login-" + UUID.randomUUID();
        UUID id = operations.queryForObject(
                "INSERT INTO cane.staff_users(username,password_hash,role) VALUES (?,?,?) RETURNING id",
                UUID.class,
                username,
                passwordHash,
                role);
        return new Staff(id, username);
    }

    private static State state(Staff staff) {
        return operations.queryForObject(
                "SELECT failed_count,locked_until FROM cane.staff_users WHERE id=?",
                (r, n) -> {
                    var until = r.getObject("locked_until", OffsetDateTime.class);
                    return new State(r.getInt("failed_count"), until == null ? null : until.toInstant());
                },
                staff.id());
    }

    private static Instant dbTime() {
        return operations
                .queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)
                .toInstant();
    }

    private static int staffSessions(Staff staff) {
        return operations.queryForObject(
                "SELECT count(*) FROM cane.staff_sessions WHERE staff_id=?", Integer.class, staff.id());
    }

    private static int count(StaffSessionService.IssuedSession session) {
        return operations.queryForObject(
                "SELECT count(*) FROM cane.staff_sessions WHERE token_hash=?",
                Integer.class,
                session.token().hash());
    }

    private static Bootstrap bootstrap() throws Exception {
        var response = send("GET", ME, null, Map.of());
        assertError(response, 401, "AUTH_REQUIRED");
        return new Bootstrap(response.headers().firstValue(StaffCsrf.HEADER).orElseThrow());
    }

    private static HttpResponse<String> login(
            String username, String password, Bootstrap csrf, StaffSessionService.IssuedSession old) throws Exception {
        return send(
                "POST",
                LOGIN,
                body(username, password),
                headers(
                        csrf,
                        csrf.cookie()
                                + (old == null
                                        ? ""
                                        : "; " + SessionCookies.NAME + "="
                                                + old.token().value())));
    }

    private static String body(String username, String password) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("username", username, "password", password));
    }

    private static Map<String, String> headers(Bootstrap csrf, String cookie) {
        return Map.of("Origin", ORIGIN, StaffCsrf.HEADER, csrf.token(), "Cookie", cookie);
    }

    private static String cookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "=") && !c.startsWith(name + "=;"))
                .map(c -> c.split(";", 2)[0])
                .reduce((first, last) -> last)
                .orElseThrow();
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
        assertThat(response.statusCode()).as("HTTP status").isEqualTo(status);
        var error = JsonMapper.builder().build().readTree(response.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.get("requestId").asString())
                .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private static void assertNoBusinessWrites() {
        for (String table : new String[] {"business_events", "notification_outbox", "stock_movements"})
            assertThat(operations.queryForObject("SELECT count(*) FROM cane." + table, Integer.class))
                    .isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Login race barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record Staff(UUID id, String username) {}

    private record State(int failed, Instant until) {}

    private record Bootstrap(String token) {
        String cookie() {
            return StaffCsrf.COOKIE + "=" + token;
        }
    }
}
