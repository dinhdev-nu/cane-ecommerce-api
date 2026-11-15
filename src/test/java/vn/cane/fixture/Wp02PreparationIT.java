package vn.cane.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.infrastructure.cli.CatalogSeedCommand;

/** Rehearses the isolated preparation runbook; public HTTP/cache behavior remains future work. */
@Testcontainers
class Wp02PreparationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Test
    void migratedDisposableDatabaseLoadsTheSeedStockAndSettingsWithoutOperationalRecords() throws Exception {
        TestInfrastructure.prepareCore(POSTGRES);
        var database = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        var json = JsonMapper.builder().build();
        Path resources = Path.of("src/test/resources");
        String defaults = Files.readString(resources.resolve("admin/wp02/settings-default.json"));
        assertThat(json.readTree(database.queryForObject(
                        "SELECT jsonb_object_agg(key,value)::text FROM cane.settings", String.class)))
                .isEqualTo(json.readTree(defaults));
        var output = new StringWriter();
        assertThat(CatalogSeedCommand.run(
                        new String[] {
                            "--manifest",
                            resources.resolve("catalog/wp02/seed-fixture.json").toString(),
                            "--environment",
                            "test"
                        },
                        Map.of(
                                "CANE_OPERATIONS_DB_URL",
                                POSTGRES.getJdbcUrl(),
                                "CANE_OPERATIONS_DB_USER",
                                "cane_migrator",
                                "CANE_OPERATIONS_DB_PASSWORD",
                                TestInfrastructure.MIGRATION_PASSWORD),
                        new PrintWriter(output)))
                .isZero();
        assertThat(output.toString()).contains("inserted=9 preserved=0");
        POSTGRES.copyFileToContainer(
                MountableFile.forHostPath(
                        resources.resolve("public/wp02/load-scenario.sql").toAbsolutePath()),
                "/tmp/wp02-load-scenario.sql");
        var scenarios = json.readTree(Files.readString(resources.resolve("admin/wp02/settings-scenarios.json")));
        String complete = scenarios
                .get("scenarios")
                .valueStream()
                .filter(row -> row.get("name").asString().equals("complete"))
                .findFirst()
                .orElseThrow()
                .get("patch")
                .toString();
        var loaded = POSTGRES.execInContainer(
                "psql",
                "-X",
                "--no-password",
                "-U",
                "cane_migrator",
                "-d",
                POSTGRES.getDatabaseName(),
                "-v",
                "stockJson=" + Files.readString(resources.resolve("catalog/wp02/stock-fixture.json")),
                "-v",
                "settingsJson=" + complete,
                "-f",
                "/tmp/wp02-load-scenario.sql");
        assertThat(loaded.getExitCode())
                .withFailMessage(loaded.getStdout() + loaded.getStderr())
                .isZero();
        var actual = database.queryForMap("""
                SELECT jsonb_object_agg(sku,stock_qty)::text AS stocks,
                       count(*) AS count, max(price_vnd) AS max_price FROM cane.products
                """);
        assertThat(((Number) actual.get("count")).intValue()).isEqualTo(9);
        assertThat(((Number) actual.get("max_price")).longValue()).isEqualTo(9_007_199_254_740_991L);
        assertThat(database.queryForObject(
                        "SELECT unit_label FROM cane.products WHERE sku='cane-juice-cup'", String.class))
                .isEqualTo("Cup 500 ml (with ice)");
        assertThat(database.queryForObject(
                        "SELECT name FROM cane.products WHERE sku='cane-juice-bottle'", String.class))
                .isEqualTo("Cane Juice · Bottle");
        var expectedStocks = json.createObjectNode();
        for (var row : json.readTree(Files.readString(resources.resolve("catalog/wp02/stock-fixture.json")))
                .get("items")) expectedStocks.set(row.get("sku").asString(), row.get("stockQty"));
        assertThat(json.readTree((String) actual.get("stocks"))).isEqualTo(expectedStocks);
        assertThat(database.queryForObject(
                        "SELECT value::text FROM cane.settings WHERE key='shipping_fee_vnd'", String.class))
                .isEqualTo("17001");
        for (String table :
                new String[] {"orders", "stock_movements", "business_events", "notification_outbox", "staff_users"})
            assertThat(database.queryForObject("SELECT count(*) FROM cane." + table, Integer.class))
                    .isZero();
    }
}
