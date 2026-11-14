package vn.cane.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.bootstrap.cli.OperationalDatabase;
import vn.cane.catalog.application.CatalogSeedManifest.ProductSeed;
import vn.cane.catalog.infrastructure.JdbcCatalogSeedRepository;
import vn.cane.catalog.infrastructure.cli.CatalogManifestReader;
import vn.cane.catalog.infrastructure.cli.CatalogSeedCommand;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.RequestInProgressException;
import vn.cane.support.transaction.Transactions;

@Testcontainers
class CatalogSeedIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @TempDir
    Path directory;

    private com.zaxxer.hikari.HikariDataSource source;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate owner;
    private CatalogSeedService service;
    private CatalogSeedManifest manifest;
    private Map<String, String> environment;

    @BeforeEach
    void prepare() throws Exception {
        String database = "cane_seed_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        environment = Map.of(
                "CANE_OPERATIONS_DB_URL",
                url,
                "CANE_OPERATIONS_DB_USER",
                "cane_migrator",
                "CANE_OPERATIONS_DB_PASSWORD",
                TestInfrastructure.MIGRATION_PASSWORD);
        owner = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api,cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        source = OperationalDatabase.open(environment);
        source.setMaximumPoolSize(3);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(source));
        context.register(
                TransactionConfiguration.class,
                Transactions.class,
                AdvisoryLocks.class,
                JdbcCatalogSeedRepository.class,
                CatalogSeedService.class);
        context.refresh();
        service = context.getBean(CatalogSeedService.class);
        manifest = CatalogManifestReader.read(fixture(), "test");
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (source != null) source.close();
    }

    @Test
    void createsExactlyNineAtZeroAndRerunPreservesEveryExistingColumnAndSetting() {
        String settingsBefore = snapshot("settings");
        assertThat(service.seed(manifest)).isEqualTo(new CatalogSeedService.Result(9, 0));
        assertThat(owner.queryForList("SELECT stock_qty FROM cane.products", Integer.class))
                .containsOnly(0)
                .hasSize(9);
        assertThat(snapshot("settings")).isEqualTo(settingsBefore);
        owner.update(
                "UPDATE cane.products SET price_vnd=43210,stock_qty=7,is_active=false,delivery_scope='near',name='Updated name',unit_label='Updated unit',updated_at='2026-10-01T01:02:03.123Z' WHERE sku='cane-tea'");
        owner.update("UPDATE cane.settings SET value='31000' WHERE key='shipping_fee_vnd'");
        String productsBefore = snapshot("products");
        settingsBefore = snapshot("settings");
        assertThat(service.seed(manifest)).isEqualTo(new CatalogSeedService.Result(0, 9));
        assertThat(snapshot("products")).isEqualTo(productsBefore);
        assertThat(snapshot("settings")).isEqualTo(settingsBefore);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.stock_movements", Integer.class))
                .isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.business_events", Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void failureRollsBackNewRowsAndRerunCompletesAFormerPartialSeed(int initialRows) {
        var transactions = context.getBean(Transactions.class);
        var repository = context.getBean(JdbcCatalogSeedRepository.class);
        transactions.write(() -> {
            for (int n = 0; n < initialRows; n++)
                repository.insertMissing(manifest.products().get(n));
            return null;
        });
        String before = snapshot("products");
        var calls = new AtomicInteger();
        var fault = new CatalogSeedService(
                decorate(product -> {
                    repository.insertMissing(product);
                    if (calls.incrementAndGet() == 4) throw new IllegalStateException("Injected seed interruption");
                }),
                transactions,
                context.getBean(AdvisoryLocks.class));
        assertThatThrownBy(() -> fault.seed(manifest)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot("products")).isEqualTo(before);
        assertThat(service.seed(manifest)).isEqualTo(new CatalogSeedService.Result(9 - initialRows, initialRows));
        assertThat(service.seed(manifest)).isEqualTo(new CatalogSeedService.Result(0, 9));
    }

    @Test
    void refusesUnexpectedExistingSkuWithoutDeletingOrChangingIt() {
        owner.update(
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope) VALUES ('OTHER','Existing','unit',1000,'city')");
        String before = snapshot("products");
        assertThatThrownBy(() -> service.seed(manifest)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot("products")).isEqualTo(before);
    }

    @Test
    void competingSeedsUseTheSameTransactionLockAndRetryDoesNotDuplicate() throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstInsert = new AtomicInteger();
        var repository = context.getBean(JdbcCatalogSeedRepository.class);
        var holding = new CatalogSeedService(
                decorate(product -> {
                    repository.insertMissing(product);
                    if (firstInsert.incrementAndGet() == 1) {
                        acquired.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Seed barrier timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                }),
                context.getBean(Transactions.class),
                context.getBean(AdvisoryLocks.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> holding.seed(manifest));
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.seed(manifest)).isInstanceOf(RequestInProgressException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new CatalogSeedService.Result(9, 0));
        }
        assertThat(service.seed(manifest)).isEqualTo(new CatalogSeedService.Result(0, 9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cane_api", "cane_worker"})
    void runtimeCredentialsCannotSeedEvenWhenAllRowsAlreadyExist(String role) throws Exception {
        service.seed(manifest);
        String before = snapshot("products");
        var runtime = new HashMap<>(environment);
        runtime.put("CANE_OPERATIONS_DB_USER", role);
        runtime.put(
                "CANE_OPERATIONS_DB_PASSWORD",
                role.equals("cane_api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD);
        assertThat(command(runtime)).isEqualTo(1);
        assertThat(snapshot("products")).isEqualTo(before);
    }

    @Test
    void schemaDriftStopsCommandBeforeAnySeedWrites() throws Exception {
        owner.update("UPDATE cane.flyway_schema_history SET checksum=checksum+1 WHERE version='001'");
        assertThat(command(environment)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.products", Integer.class))
                .isZero();
    }

    @Test
    void commandDoesNotCreateOrMigrateAnUnpreparedSchema() throws Exception {
        String database = "cane_unseeded_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        var variables = new HashMap<>(environment);
        variables.put(
                "CANE_OPERATIONS_DB_URL",
                POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        assertThat(command(variables)).isEqualTo(1);
        var unprepared = new JdbcTemplate(new DriverManagerDataSource(
                variables.get("CANE_OPERATIONS_DB_URL"), "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        assertThat(unprepared.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname='cane'", Integer.class))
                .isZero();
    }

    @Test
    void realWrapperAndPackagedEntryPointNeedNoApiWorkerOrRedisAndCanRunAgain() throws Exception {
        Path path = directory.resolve("fixture with spaces.json");
        Files.copy(fixture(), path);
        var first = wrapper(path);
        assertThat(first)
                .contains("inserted=9 preserved=0")
                .doesNotContain("Tomcat", "Redis", "PRIVATE", TestInfrastructure.MIGRATION_PASSWORD);
        String before = snapshot("products");
        assertThat(wrapper(path)).contains("inserted=0 preserved=9");
        assertThat(snapshot("products")).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.staff_users", Integer.class))
                .isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.settings", Integer.class))
                .isEqualTo(16);
    }

    private CatalogSeedRepository decorate(java.util.function.Consumer<ProductSeed> insert) {
        var repository = context.getBean(JdbcCatalogSeedRepository.class);
        return new CatalogSeedRepository() {
            @Override
            public List<ExistingProduct> lockExisting() {
                return repository.lockExisting();
            }

            @Override
            public boolean insertMissing(ProductSeed product) {
                insert.accept(product);
                return true;
            }
        };
    }

    private int command(Map<String, String> variables) throws Exception {
        var output = new StringWriter();
        int exit = CatalogSeedCommand.run(
                new String[] {"--manifest", fixture().toString(), "--environment", "test"},
                variables,
                new PrintWriter(output));
        assertThat(output.toString())
                .doesNotContain(
                        TestInfrastructure.MIGRATION_PASSWORD, TestInfrastructure.API_PASSWORD, "Exception", "jdbc:");
        return exit;
    }

    private String wrapper(Path manifestPath) throws Exception {
        var process = new ProcessBuilder(
                        "pwsh",
                        "-NoProfile",
                        "-File",
                        "scripts/catalog/Seed-Catalog.ps1",
                        "-Manifest",
                        manifestPath.toString(),
                        "-Environment",
                        "test")
                .redirectErrorStream(true);
        process.environment().putAll(environment);
        process.environment().put("CANE_DB_URL", "invalid-runtime-must-not-start");
        var child = process.start();
        if (!child.waitFor(25, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Seed CLI timed out");
        }
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(child.exitValue()).withFailMessage(output).isZero();
        return output;
    }

    private String snapshot(String table) {
        // Table names are fixed test literals, never operational input.
        return owner.queryForObject(
                "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM cane." + table
                        + " t",
                String.class);
    }

    private static Path fixture() {
        return Path.of("src/test/resources/catalog/nine-skus-fixture.json");
    }
}
