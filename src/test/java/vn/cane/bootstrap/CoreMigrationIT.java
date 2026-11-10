package vn.cane.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.cane.fixture.TestInfrastructure;

/** WP01-01: migrations and storage contracts, using a fresh database for each scenario. */
@Testcontainers
class CoreMigrationIT {
    private static final String CORE = "classpath:db/migration/core";
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/core/V001__cane_core.sql");

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @TempDir
    Path migrationCopies;

    private String jdbcUrl;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createDisposableDatabase() throws Exception {
        String database = "cane_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            // The identifier is generated here, never taken from a request or external input.
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        jdbcUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(jdbcUrl, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
    }

    @Test
    void validatesPendingSeparatelyThenMigratesAndStrictlyValidates() {
        assertThat(configure(CORE).load().validateWithResult().validationSuccessful)
                .isFalse();
        assertThat(configure(CORE)
                        .ignoreMigrationPatterns("*:pending")
                        .load()
                        .validateWithResult()
                        .validationSuccessful)
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regnamespace('cane') IS NULL", Boolean.class))
                .isTrue();
        assertThat(coreFlyway().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(coreFlyway().validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cane.flyway_schema_history WHERE type='SQL' AND success", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_regclass('cane.payment_webhooks') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void matchesAllCoreColumnsKeysIndexesChecksAndTriggerObjects() throws Exception {
        coreFlyway().migrate();
        var columns = jdbc.queryForList("""
                SELECT table_name || E'\t' || column_name || E'\t' || data_type || E'\t'
                    || is_nullable || E'\t' || is_generated
                FROM information_schema.columns
                WHERE table_schema='cane' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name, ordinal_position
                """, String.class);
        assertThat(columns).containsExactlyInAnyOrderElementsOf(contract("core-columns.tsv"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='cane' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'
                """, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE connamespace='cane'::regnamespace AND contype='f'
                """, Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE connamespace='cane'::regnamespace AND contype='p'
                    AND conrelid <> 'cane.flyway_schema_history'::regclass
                """, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE connamespace='cane'::regnamespace AND contype='c'
                """, String.class)).containsAll(contract("core-checks.txt"));
        assertThat(jdbc.queryForList("""
                SELECT i.relname FROM pg_index x JOIN pg_class i ON i.oid=x.indexrelid
                JOIN pg_class t ON t.oid=x.indrelid
                WHERE t.relnamespace='cane'::regnamespace AND t.relname <> 'flyway_schema_history'
                    AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid=x.indexrelid)
                """, String.class))
                .containsExactlyInAnyOrderElementsOf(contract("core-indexes.txt"));
        assertThat(jdbc.queryForList("""
                SELECT proname FROM pg_proc WHERE pronamespace='cane'::regnamespace
                """, String.class))
                .containsExactlyInAnyOrder("touch_updated_at", "reject_audit_mutation", "protect_payment_receipt");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger g JOIN pg_class t ON t.oid=g.tgrelid
                WHERE t.relnamespace='cane'::regnamespace AND NOT g.tgisinternal AND g.tgenabled='O'
                """, Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog'])
                FROM pg_proc WHERE pronamespace='cane'::regnamespace
                """, Boolean.class)).isTrue();
    }

    @Test
    void enforcesStorageDomainsAndUniqueKeysWithoutLeavingFixtures() throws Exception {
        coreFlyway().migrate();
        try (var connection =
                        DriverManager.getConnection(jdbcUrl, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD);
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(resource("core-constraints.sql"));
                try (var result = statement.executeQuery("SELECT count(*) FROM pg_temp.schema_check_results")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isGreaterThanOrEqualTo(80);
                }
                var rejectedConstraints = new ArrayList<String>();
                try (var result = statement.executeQuery("""
                        SELECT DISTINCT rejected_constraint FROM pg_temp.schema_check_results
                        WHERE rejected_constraint IS NOT NULL
                        """)) {
                    while (result.next()) {
                        rejectedConstraints.add(result.getString(1));
                    }
                }
                assertThat(rejectedConstraints).containsAll(contract("core-checks.txt"));
            } finally {
                connection.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cane.orders", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cane.products", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cane.settings", Integer.class))
                .isEqualTo(16);
    }

    @Test
    void rerunPreservesSettingsAndDoesNotAddHistory() {
        coreFlyway().migrate();
        assertThat(jdbc.queryForList("SELECT key || '=' || value::text FROM cane.settings", String.class))
                .containsExactlyInAnyOrder(
                        "store=null",
                        "accepting_orders=true",
                        "enabled_payment_methods=[]",
                        "shipping_fee_vnd=null",
                        "free_ship_threshold_vnd=null",
                        "delivery_zones=[]",
                        "bank_details=null",
                        "bank_auto_confirm=false",
                        "operator_can_confirm_bank=false",
                        "support=null",
                        "owner_notify=null",
                        "low_stock_threshold=5",
                        "max_qty_per_sku=99",
                        "bank_hold_min=60",
                        "link_ttl_days=90",
                        "stale_alert_hours=24");
        jdbc.update("UPDATE cane.settings SET value='false' WHERE key='accepting_orders'");
        assertThat(coreFlyway().migrate().migrationsExecuted).isZero();
        coreFlyway().validate();
        assertThat(jdbc.queryForObject(
                        "SELECT value::text FROM cane.settings WHERE key='accepting_orders'", String.class))
                .isEqualTo("false");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cane.flyway_schema_history WHERE type='SQL'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void checksumMismatchStopsValidationAndMigrationWithoutRepair() throws Exception {
        coreFlyway().migrate();
        int checksum =
                jdbc.queryForObject("SELECT checksum FROM cane.flyway_schema_history WHERE type='SQL'", Integer.class);
        Files.writeString(
                migrationCopies.resolve(MIGRATION.getFileName()),
                Files.readString(MIGRATION).replace("('stale_alert_hours', '24')", "('stale_alert_hours', '25')"));
        var changed =
                configure(copiesLocation()).ignoreMigrationPatterns("*:pending").load();
        assertThat(changed.validateWithResult().invalidMigrations)
                .anySatisfy(error ->
                        assertThat(error.errorDetails.errorCode.toString()).isEqualTo("CHECKSUM_MISMATCH"));
        assertThatThrownBy(changed::migrate).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT checksum FROM cane.flyway_schema_history WHERE type='SQL'", Integer.class))
                .isEqualTo(checksum);
        assertThat(jdbc.queryForObject(
                        "SELECT value::text FROM cane.settings WHERE key='stale_alert_hours'", String.class))
                .isEqualTo("24");
    }

    @Test
    void pendingExceptionDoesNotHideMissingAppliedMigration() {
        coreFlyway().migrate();
        var missing =
                configure(copiesLocation()).ignoreMigrationPatterns("*:pending").load();
        assertThat(missing.validateWithResult().validationSuccessful).isFalse();
        assertThatThrownBy(missing::migrate).isInstanceOf(FlywayException.class);
        assertThat(coreFlyway().validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void strictValidationRejectsAFutureVersionMissingFromThisRelease() throws Exception {
        Files.copy(MIGRATION, migrationCopies.resolve(MIGRATION.getFileName()));
        Files.writeString(migrationCopies.resolve("V002__future_fixture.sql"), "SELECT 1;");
        configure(copiesLocation()).load().migrate();
        assertThat(coreFlyway().validateWithResult().validationSuccessful).isFalse();
        assertThat(configure(CORE)
                        .ignoreMigrationPatterns("*:pending")
                        .load()
                        .validateWithResult()
                        .validationSuccessful)
                .isFalse();
        // Exercise the exact empty CLI property used after migrate, rather than relying
        // on the Java API setting above. Flyway otherwise defaults to ignoring future.
        var strictValidation = new ProcessBuilder("pwsh", "-NoProfile", "-Command", """
                $wrapper = if ($IsWindows) { './mvnw.cmd' } else { './mvnw' }
                & $wrapper '-B' '-ntp' '-Dflyway.ignoreMigrationPatterns=' 'flyway:validate'
                exit $LASTEXITCODE
                """);
        assertThat(runOperationalCommand(strictValidation, "strict-future.log", 1))
                .contains("Detected applied migration not resolved locally");
    }

    @Test
    void operationalCommandMigratesAndRerunsWithoutOverwritingSettings() throws Exception {
        runMigrationCommand("first-run.log");
        jdbc.update("UPDATE cane.settings SET value='false' WHERE key='accepting_orders'");
        runMigrationCommand("rerun.log");
        assertThat(jdbc.queryForObject(
                        "SELECT value::text FROM cane.settings WHERE key='accepting_orders'", String.class))
                .isEqualTo("false");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cane.flyway_schema_history WHERE type='SQL'", Integer.class))
                .isEqualTo(1);
        coreFlyway().validate();
    }

    @Test
    void operationalCommandRejectsRepeatableBeforeStartingMaven() throws Exception {
        Path script = migrationCopies.resolve("scripts/database/Migrate-Core.ps1");
        Files.createDirectories(script.getParent());
        Files.copy(Path.of("scripts/database/Migrate-Core.ps1"), script);
        Path core = migrationCopies.resolve("src/main/resources/db/migration/core/nested");
        Files.createDirectories(core);
        Files.writeString(core.resolve("R__not_allowed.sql"), "SELECT 1;");
        String output = runMigrationCommand(script, "repeatable.log", 1);
        assertThat(output).contains("Repeatable migrations are not allowed in core.");
        assertThat(output).doesNotContain("Scanning for projects");
        assertThat(jdbc.queryForObject("SELECT to_regnamespace('cane') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void refusesNonEmptySchemaWithoutHistoryAndDoesNotBaseline() {
        jdbc.execute("CREATE SCHEMA cane");
        jdbc.execute("CREATE TABLE cane.existing_data(id integer PRIMARY KEY)");
        jdbc.update("INSERT INTO cane.existing_data VALUES (7)");
        assertThatThrownBy(() -> coreFlyway().migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty");
        assertThat(jdbc.queryForObject("SELECT id FROM cane.existing_data", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT to_regclass('cane.orders') IS NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('cane.flyway_schema_history') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void initialMigrationFailureRollsBackCoreObjects() throws Exception {
        Files.writeString(
                migrationCopies.resolve(MIGRATION.getFileName()),
                Files.readString(MIGRATION).replace("CREATE TABLE orders (", "SELECT 1 / 0;\nCREATE TABLE orders ("));
        assertThatThrownBy(() -> configure(copiesLocation()).load().migrate()).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='cane' AND table_name <> 'flyway_schema_history'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cane.flyway_schema_history WHERE type='SQL'", Integer.class))
                .isZero();
    }

    @Test
    void upgradeFailureRollsBackDdlDataAndStopsLaterMigrations() throws Exception {
        coreFlyway().migrate();
        Files.copy(MIGRATION, migrationCopies.resolve(MIGRATION.getFileName()));
        Files.writeString(migrationCopies.resolve("V002__failure_fixture.sql"), """
                CREATE TABLE cane.upgrade_probe (id integer PRIMARY KEY);
                INSERT INTO cane.settings(key,value) VALUES ('upgrade_probe','true');
                SELECT 1 / 0;
                """);
        Files.writeString(
                migrationCopies.resolve("V003__must_not_run.sql"), "CREATE TABLE cane.must_not_run(id integer);");
        var upgrade = configure(copiesLocation()).load();
        assertThatThrownBy(upgrade::migrate).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT to_regclass('cane.upgrade_probe') IS NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('cane.must_not_run') IS NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cane.settings", Integer.class))
                .isEqualTo(16);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cane.flyway_schema_history WHERE type='SQL'", Integer.class))
                .isEqualTo(1);
        coreFlyway().validate();
    }

    @Test
    void runtimeAccountsCannotMigrateOrClean() {
        coreFlyway().migrate();
        for (String role : List.of("api", "worker")) {
            String password = role.equals("api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD;
            var runtime = configure(CORE)
                    .dataSource(jdbcUrl, "cane_" + role, password)
                    .load();
            assertThatThrownBy(runtime::migrate).isInstanceOf(FlywayException.class);
        }
        assertThatThrownBy(coreFlyway()::clean)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("cleanDisabled");
    }

    private Flyway coreFlyway() {
        return configure(CORE).load();
    }

    private FluentConfiguration configure(String location) {
        return Flyway.configure()
                .dataSource(jdbcUrl, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD)
                .defaultSchema("cane")
                .schemas("cane")
                .createSchemas(true)
                .locations(location)
                .failOnMissingLocations(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .ignoreMigrationPatterns(new String[0]);
    }

    private String copiesLocation() {
        return "filesystem:" + migrationCopies.toAbsolutePath();
    }

    private void runMigrationCommand(String logName) throws Exception {
        runMigrationCommand(Path.of("scripts/database/Migrate-Core.ps1"), logName, 0);
    }

    private String runMigrationCommand(Path script, String logName, int expectedExitCode) throws Exception {
        var command = new ProcessBuilder("pwsh", "-NoProfile", "-File", script.toString());
        return runOperationalCommand(command, logName, expectedExitCode);
    }

    private String runOperationalCommand(ProcessBuilder command, String logName, int expectedExitCode)
            throws Exception {
        command.environment().put("CANE_MIGRATION_URL", jdbcUrl);
        command.environment().put("CANE_MIGRATION_USER", "cane_migrator");
        command.environment().put("CANE_MIGRATION_PASSWORD", TestInfrastructure.MIGRATION_PASSWORD);
        Path log = migrationCopies.resolve(logName);
        var process =
                command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(180, TimeUnit.SECONDS))
                    .as("operational command completed")
                    .isTrue();
            String output = Files.readString(log);
            assertThat(output).doesNotContain(TestInfrastructure.MIGRATION_PASSWORD);
            assertThat(process.exitValue())
                    .withFailMessage("Unexpected command exit code:%n%s", output)
                    .isEqualTo(expectedExitCode);
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static List<String> contract(String file) throws IOException {
        return resource(file)
                .lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    private static String resource(String file) throws IOException {
        try (var stream = CoreMigrationIT.class.getResourceAsStream("/database/" + file)) {
            if (stream == null) {
                throw new IOException("Missing database test contract: " + file);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
