package vn.cane.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.cane.CaneApplication;
import vn.cane.fixture.TestInfrastructure;

/** Staging/production profiles are exercised locally, without claiming a deployed environment. */
@Testcontainers
class RuntimeReadinessIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private String database;
    private String jdbcUrl;
    private JdbcTemplate owner;

    @BeforeEach
    void prepareDatabase() throws Exception {
        database = "cane_readiness_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        jdbcUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        owner = new JdbcTemplate(dataSource("cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api, cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "worker"})
    void validatesWithReadOnlyHistoryAccessAndWithoutBusinessOrProviderFixtures(String mode) {
        assertThat(readiness(mode).inspect()).isEqualTo(DatabaseReadiness.Status.READY);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.products", Integer.class))
                .isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.staff_users", Integer.class))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM cane.flyway_schema_history WHERE type='SQL'", Integer.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"history", "checksum", "future", "unapplied", "failed", "table", "setting", "grant"})
    void rejectsSchemaAndMinimumAccessDriftWithoutRepairingAnything(String fault) {
        switch (fault) {
            case "history" -> owner.execute("ALTER TABLE cane.flyway_schema_history RENAME TO missing_history");
            case "checksum" ->
                owner.update("UPDATE cane.flyway_schema_history SET checksum=checksum+1 WHERE type='SQL'");
            case "unapplied" -> owner.update("DELETE FROM cane.flyway_schema_history WHERE type='SQL'");
            case "failed" -> owner.update("UPDATE cane.flyway_schema_history SET success=false WHERE type='SQL'");
            case "future" -> owner.update("""
                    INSERT INTO cane.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
                    SELECT max(installed_rank)+1,'002','future fixture','SQL','V002__future.sql',1,current_user,0,true FROM cane.flyway_schema_history
                    """);
            case "table" -> owner.execute("ALTER TABLE cane.products RENAME TO missing_products");
            case "setting" -> owner.update("DELETE FROM cane.settings WHERE key='store'");
            case "grant" -> owner.execute("REVOKE SELECT ON cane.orders FROM cane_api");
            default -> throw new AssertionError("Unknown fixture");
        }
        String before = catalogState();
        assertThat(readiness("api").inspect()).isEqualTo(DatabaseReadiness.Status.SCHEMA_UNAVAILABLE);
        assertThat(catalogState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ddl", "owner", "membership"})
    void rejectsUnsafeRuntimeRoles(String fault) {
        switch (fault) {
            case "ddl" -> owner.execute("GRANT CREATE ON SCHEMA public TO cane_api");
            case "owner" ->
                new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()))
                        .execute("ALTER TABLE cane.products OWNER TO cane_api");
            case "membership" -> {
                try (var connection = POSTGRES.createConnection("");
                        var statement = connection.createStatement()) {
                    statement.execute("GRANT cane_worker TO cane_api");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
            default -> throw new AssertionError("Unknown fixture");
        }
        try {
            assertThat(readiness("api").inspect()).isEqualTo(DatabaseReadiness.Status.ROLE_UNSAFE);
        } finally {
            if (fault.equals("membership")) {
                try (var connection = POSTGRES.createConnection("");
                        var statement = connection.createStatement()) {
                    statement.execute("REVOKE cane_worker FROM cane_api");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
        }
    }

    @Test
    void refusesWrongConnectionSchemaUnboundedStatementsAndCredentials() {
        for (String options : new String[] {
            "-c search_path=public -c statement_timeout=5000 -c lock_timeout=2000",
            "-c search_path=cane,pg_catalog -c statement_timeout=0 -c lock_timeout=2000"
        }) {
            var source = dataSource("cane_api", TestInfrastructure.API_PASSWORD);
            var properties = connectionProperties();
            properties.setProperty("options", options);
            source.setConnectionProperties(properties);
            assertThat(new DatabaseReadiness(source, "api").inspect())
                    .isEqualTo(DatabaseReadiness.Status.CONFIGURATION_UNSAFE);
        }
        assertThat(new DatabaseReadiness(dataSource("cane_api", "invalid-fixture-password"), "api").inspect())
                .isEqualTo(DatabaseReadiness.Status.DATABASE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "worker"})
    void startupFailureHappensBeforeHttpOrScheduledJobsStart(String mode) {
        owner.update("UPDATE cane.flyway_schema_history SET checksum=checksum+1 WHERE type='SQL'");
        var serverStarted = new AtomicBoolean();
        Heartbeat.CALLS.set(0);
        var app = application(mode);
        app.addListeners((ApplicationListener<WebServerInitializedEvent>) event -> serverStarted.set(true));
        assertThatThrownBy(() -> app.run("--spring.profiles.active=test," + mode, "--spring.main.keep-alive=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PostgreSQL startup check failed: SCHEMA_UNAVAILABLE");
        assertThat(serverStarted.get()).isFalse();
        assertThat(Heartbeat.CALLS.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"staging,api", "production,worker"})
    void environmentProfilesUseSameJarAndCanValidateWithASingleConnectionPool(String environment, String mode) {
        try (var context = application(mode)
                .run(
                        "--spring.profiles.active=" + environment + "," + mode,
                        "--server.port=0",
                        "--spring.main.keep-alive=false",
                        "--spring.datasource.hikari.maximum-pool-size=1")) {
            assertThat(context.getEnvironment().getProperty("cane.environment")).isEqualTo(environment);
            assertThat(context.getBean(InfrastructureReadiness.class).inspect().ready())
                    .isTrue();
            assertThat(context instanceof WebServerApplicationContext).isEqualTo(mode.equals("api"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://cane.example.test", "https://cane.example.test/path"})
    void invalidPublicOriginPreventsApiFromServingRequests(String origin) {
        var serverStarted = new AtomicBoolean();
        var app = application("api");
        app.addListeners((ApplicationListener<WebServerInitializedEvent>) event -> serverStarted.set(true));
        assertThatThrownBy(() -> app.run(
                        "--spring.profiles.active=staging,api",
                        "--server.port=0",
                        "--spring.main.keep-alive=false",
                        "--cane.http.public-origin=" + origin))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                        "PUBLIC_ORIGIN must be a configured HTTP origin; staging/production require HTTPS");
        assertThat(serverStarted.get()).isFalse();
    }

    @Test
    void onDemandDiagnosticsDetectSchemaLossAfterStartupWithoutCachingAHealthyResult() {
        try (var context =
                application("worker").run("--spring.profiles.active=test,worker", "--spring.main.keep-alive=false")) {
            var diagnostics = context.getBean(InfrastructureReadiness.class);
            assertThat(diagnostics.inspect().ready()).isTrue();
            owner.execute("ALTER SCHEMA cane RENAME TO cane_missing_probe");
            assertThat(diagnostics.inspect().ready()).isFalse();
        }
    }

    private DatabaseReadiness readiness(String mode) {
        return new DatabaseReadiness(
                dataSource(
                        "cane_" + mode,
                        mode.equals("api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD),
                mode);
    }

    private DriverManagerDataSource dataSource(String username, String password) {
        var source = new DriverManagerDataSource(jdbcUrl, username, password);
        source.setConnectionProperties(connectionProperties());
        return source;
    }

    private static Properties connectionProperties() {
        var properties = new Properties();
        properties.setProperty(
                "options", "-c search_path=cane,pg_catalog -c statement_timeout=5000 -c lock_timeout=2000");
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "6");
        return properties;
    }

    private SpringApplication application(String mode) {
        var properties = new HashMap<String, Object>();
        properties.put("CANE_DB_URL", jdbcUrl);
        properties.put("CANE_DB_USER", "cane_" + mode);
        properties.put(
                "CANE_DB_PASSWORD",
                mode.equals("api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD);
        properties.put("CANE_REDIS_HOST", REDIS.getHost());
        properties.put("CANE_REDIS_PORT", REDIS.getMappedPort(6379).toString());
        properties.put("CANE_REDIS_USER", "cane_runtime");
        properties.put("CANE_REDIS_PASSWORD", TestInfrastructure.REDIS_PASSWORD);
        properties.put("PUBLIC_ORIGIN", "https://cane.example.test");
        var app = new SpringApplication(CaneApplication.class, HeartbeatConfiguration.class);
        app.setDefaultProperties(properties);
        app.setLogStartupInfo(false);
        return app;
    }

    private String catalogState() {
        boolean hasHistory = Boolean.TRUE.equals(
                owner.queryForObject("SELECT to_regclass('cane.flyway_schema_history') IS NOT NULL", Boolean.class));
        String history = hasHistory
                ? owner.queryForObject(
                        "SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text FROM cane.flyway_schema_history h",
                        String.class)
                : "missing";
        return history + owner.queryForObject("""
                SELECT json_build_object('tables',(SELECT json_agg(relname ORDER BY relname) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='cane'),
                  'settings',(SELECT jsonb_agg(to_jsonb(s) ORDER BY key) FROM cane.settings s))::text
                """, String.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HeartbeatConfiguration {
        @Bean
        Heartbeat heartbeat() {
            return new Heartbeat();
        }
    }

    static class Heartbeat {
        private static final AtomicInteger CALLS = new AtomicInteger();

        @Scheduled(fixedDelay = 10)
        void tick() {
            CALLS.incrementAndGet();
        }
    }
}
