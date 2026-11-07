package vn.cane.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.cane.CaneApplication;
import vn.cane.fixture.TestInfrastructure;

@Testcontainers
class BackendSkeletonIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    @Test
    void apiStartsHttpWithoutSchedulersAuthenticationOrBusinessEndpoints() throws Exception {
        try (var context = start("api", Map.of())) {
            assertThat(context).isInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertPoolAndSqlSettings(context, 5);
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                for (String method : new String[] {"GET", "POST"}) {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/shop"))
                            .timeout(Duration.ofSeconds(5))
                            .method(method, HttpRequest.BodyPublishers.noBody())
                            .build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(403);
                    assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                            .contains("no-store");
                    assertThat(response.headers().allValues("Set-Cookie").toString())
                            .doesNotContain("JSESSIONID");
                }
            }
        }
    }

    @Test
    void workerHasSchedulingButNoHttpServerOrServletSecurity() {
        try (var context = start("worker", Map.of())) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
            assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertPoolAndSqlSettings(context, 3);
        }
    }

    @Test
    void runtimeRolesCannotCreateSchemaButMigrationRoleCan() throws Exception {
        for (String role : new String[] {"api", "worker"}) {
            String password = role.equals("api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD;
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "cane_" + role, password);
                    var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("CREATE SCHEMA runtime_must_not_create"))
                        .isInstanceOf(java.sql.SQLException.class)
                        .extracting("SQLState")
                        .isEqualTo("42501");
            }
        }
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA prep02_permission_probe");
            statement.execute("DROP SCHEMA prep02_permission_probe");
        }
    }

    @Test
    void flywayValidatesEmptyCoreWithoutCreatingBusinessSchema() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD)
                .defaultSchema("cane")
                .schemas("cane")
                .createSchemas(true)
                .locations("classpath:db/migration/core")
                .failOnMissingLocations(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .load();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname = 'cane'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    void redisRequiresAuthenticationRestrictsNamespaceAndKeepsFiniteTtl() throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(REDIS.getHost(), REDIS.getMappedPort(6379)), 1000);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(response.readLine()).startsWith("-NOAUTH");
        }
        try (var context = start("api", Map.of())) {
            var redis = context.getBean(StringRedisTemplate.class);
            String key = "cane:test:prep02:connection";
            try {
                redis.opsForValue().set(key, "connection-ok", Duration.ofSeconds(30));
                assertThat(redis.opsForValue().get(key)).isEqualTo("connection-ok");
                assertThat(redis.getExpire(key)).isBetween(1L, 30L);
                assertThatThrownBy(() -> redis.opsForValue().get("outside:cane:namespace"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class)
                        .rootCause()
                        .hasMessageContaining("NOPERM");
            } finally {
                redis.delete(key);
            }
        }
    }

    @Test
    void redisFailureDoesNotStopTheSkeleton() {
        try (var context = start("worker", Map.of("CANE_REDIS_PORT", "1"))) {
            assertThat(context.isActive()).isTrue();
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
        }
    }

    @Test
    void wrongDatabaseCredentialStopsStartup() {
        assertThatThrownBy(() -> start("worker", Map.of("CANE_DB_PASSWORD", "invalid-fixture-password")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL startup check failed");
    }

    @Test
    void databaseOwnerCannotBeUsedAsTheRuntimeAccount() {
        assertThatThrownBy(() -> start(
                        "worker",
                        Map.of("CANE_DB_USER", POSTGRES.getUsername(), "CANE_DB_PASSWORD", POSTGRES.getPassword())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not have ownership or DDL privileges");
    }

    @Test
    void accidentallyGrantedSchemaDdlStopsTheRuntime() throws Exception {
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("GRANT CREATE ON SCHEMA public TO cane_worker");
            try {
                assertThatThrownBy(() -> start("worker", Map.of()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must not have ownership or DDL privileges");
            } finally {
                statement.execute("REVOKE CREATE ON SCHEMA public FROM cane_worker");
            }
        }
    }

    @Test
    void runtimeCannotOwnAnOrdinarySchemaStartingWithPg() throws Exception {
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA pgbusiness_probe AUTHORIZATION cane_worker");
            try {
                assertThatThrownBy(() -> start("worker", Map.of()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must not have ownership or DDL privileges");
            } finally {
                statement.execute("DROP SCHEMA pgbusiness_probe");
            }
        }
    }

    private static ConfigurableApplicationContext start(String mode, Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("CANE_DB_URL", POSTGRES.getJdbcUrl());
        properties.put("CANE_DB_USER", "cane_" + mode);
        properties.put(
                "CANE_DB_PASSWORD",
                mode.equals("api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD);
        properties.put("CANE_REDIS_HOST", REDIS.getHost());
        properties.put("CANE_REDIS_PORT", REDIS.getMappedPort(6379).toString());
        properties.put("CANE_REDIS_USER", "cane_runtime");
        properties.put("CANE_REDIS_PASSWORD", TestInfrastructure.REDIS_PASSWORD);
        properties.putAll(overrides);
        var application = new SpringApplication(CaneApplication.class);
        application.setDefaultProperties(properties);
        application.setLogStartupInfo(false);
        return application.run("--spring.profiles.active=test," + mode, "--spring.main.keep-alive=false");
    }

    private static void assertPoolAndSqlSettings(ConfigurableApplicationContext context, int maximum) {
        var pool = context.getBean(HikariDataSource.class);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(maximum);
        assertThat(pool.getMinimumIdle()).isEqualTo(1);
        assertThat(pool.getConnectionTimeout()).isEqualTo(3000);
        assertThat(pool.getValidationTimeout()).isEqualTo(1000);
        var jdbc = context.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SHOW search_path", String.class)).isEqualTo("cane,pg_catalog");
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2s");
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                .isEqualTo("read committed");
    }
}
