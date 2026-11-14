package vn.cane.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.StaffCommandService;
import vn.cane.admin.infrastructure.JdbcStaffCommandRepository;
import vn.cane.bootstrap.PasswordConfiguration;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.bootstrap.cli.OperationalDatabase;
import vn.cane.catalog.infrastructure.JdbcInitialStockRepository;
import vn.cane.catalog.infrastructure.cli.InitialStockCli;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.IdempotencyMismatchException;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.OperationReceipts;
import vn.cane.support.idempotency.OperationStamp;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.RequestInProgressException;
import vn.cane.support.transaction.Transactions;

@Testcontainers
class InitialStockIT {
    private static final String KEY = "initial-stock-fixture-0001";
    private static final String SKU = "cane-tea";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().build();
    private com.zaxxer.hikari.HikariDataSource source;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate owner;
    private NamedParameterJdbcTemplate named;
    private Map<String, String> environment;
    private Transactions transactions;
    private StaffCommandService staff;
    private JdbcInitialStockRepository repository;
    private BusinessEventWriter events;
    private IdempotentOperations operations;
    private InitialStockService service;
    private UUID actor;

    @BeforeEach
    void prepare() throws Exception {
        String database = "cane_initial_" + UUID.randomUUID().toString().replace("-", "");
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
                TestInfrastructure.MIGRATION_PASSWORD,
                "CANE_API_DB_USER",
                "cane_api",
                "CANE_WORKER_DB_USER",
                "cane_worker");
        owner = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api,cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        actor = UUID.randomUUID();
        owner.update(
                "INSERT INTO cane.staff_users(id,username,password_hash,role) VALUES (?,'fixture-owner','fixture-hash','owner')",
                actor);
        owner.update(
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope) VALUES (?,'Fixture','unit',25000,'city')",
                SKU);
        owner.update(
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope) VALUES ('cane-candy','Fixture','unit',10000,'city')");
        source = OperationalDatabase.open(Map.of(
                "CANE_OPERATIONS_DB_URL",
                url,
                "CANE_OPERATIONS_DB_USER",
                "cane_api",
                "CANE_OPERATIONS_DB_PASSWORD",
                TestInfrastructure.API_PASSWORD));
        source.setMaximumPoolSize(4);
        named = new NamedParameterJdbcTemplate(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(TransactionConfiguration.class, Transactions.class, PasswordConfiguration.class);
        context.refresh();
        transactions = context.getBean(Transactions.class);
        staff = new StaffCommandService(
                new JdbcStaffCommandRepository(named),
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class),
                transactions,
                new AdvisoryLocks(named));
        repository = new JdbcInitialStockRepository(named);
        events = new BusinessEventWriter(named, json);
        operations =
                new IdempotentOperations(transactions, new AdvisoryLocks(named), new OperationReceipts(named, json));
        service = build(repository, events, staff);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (source != null) source.close();
    }

    @Test
    void importsWithExactHistoryAndReplayReturnsOldReceiptAfterStockChanges() {
        var first = service.initialize(command(1, KEY));
        assertThat(first.replayed()).isFalse();
        assertThat(stock()).isEqualTo(1);
        assertCounts(1, 1);
        var row = owner.queryForMap(
                "SELECT s.delta,s.reason,s.actor,s.note,s.event_id,e.kind,e.request_hash,e.operation_result,p.updated_by FROM cane.stock_movements s JOIN cane.business_events e ON e.id=s.event_id JOIN cane.products p ON p.sku=s.sku");
        assertThat(row)
                .containsEntry("delta", 1)
                .containsEntry("reason", "restock")
                .containsEntry("actor", actor.toString())
                .containsEntry("note", "Initial fixture count")
                .containsEntry("kind", "stock_adjusted")
                .containsEntry("updated_by", actor);
        assertThat((byte[]) row.get("request_hash")).hasSize(32);
        assertThat(row.get("event_id")).isEqualTo(first.operationId());
        assertThat(first.result().path("movementId").asString())
                .isEqualTo(owner.queryForObject("SELECT id::text FROM cane.stock_movements", String.class));
        owner.update("UPDATE cane.products SET stock_qty=7 WHERE sku=?", SKU);
        var retry = service.initialize(command(1, KEY));
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.operationId()).isEqualTo(first.operationId());
        assertThat(retry.result()).isEqualTo(first.result());
        assertThat(stock()).isEqualTo(7);
        assertCounts(1, 1);
        assertThatThrownBy(() -> service.initialize(command(1, "initial-stock-fixture-0002")))
                .isInstanceOf(IllegalStateException.class);
        assertCounts(1, 1);
    }

    @Test
    void mismatchesQuantitySkuAndReasonBeforeAnyNewEffect() {
        service.initialize(command(1, KEY));
        for (var changed : java.util.List.of(
                command(2, KEY),
                new InitialStockCommand(actor, "cane-candy", 1, "Initial fixture count", KEY),
                new InitialStockCommand(actor, SKU, 1, "Changed reason", KEY)))
            assertThatThrownBy(() -> service.initialize(changed)).isInstanceOf(IdempotencyMismatchException.class);
        assertThat(stock()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void zeroWritesOnlyNoopAnchorWithoutTouchingProductOrLedgerAndNewKeyHasItsOwnAnchor() {
        String before =
                owner.queryForObject("SELECT row_to_json(p)::text FROM cane.products p WHERE sku=?", String.class, SKU);
        var first = service.initialize(command(0, KEY));
        assertThat(first.result().path("movementId").isNull()).isTrue();
        assertThat(service.initialize(command(0, KEY)).replayed()).isTrue();
        service.initialize(command(0, "initial-stock-fixture-0002"));
        assertCounts(2, 0);
        assertThat(owner.queryForList("SELECT kind FROM cane.business_events", String.class))
                .containsOnly("operation_noop");
        assertThat(owner.queryForObject(
                        "SELECT row_to_json(p)::text FROM cane.products p WHERE sku=?", String.class, SKU))
                .isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "operator", "missing"})
    void revokedOwnerCannotReplayOrInspectMismatch(String change) {
        service.initialize(command(1, KEY));
        if (change.equals("inactive")) owner.update("UPDATE cane.staff_users SET is_active=false WHERE id=?", actor);
        if (change.equals("operator")) owner.update("UPDATE cane.staff_users SET role='operator' WHERE id=?", actor);
        UUID selected = change.equals("missing") ? UUID.randomUUID() : actor;
        assertThatThrownBy(() ->
                        service.initialize(new InitialStockCommand(selected, SKU, 2, "Initial fixture count", KEY)))
                .isInstanceOf(AccessDeniedException.class);
        assertCounts(1, 1);
    }

    @Test
    void ownerIsCheckedAgainInsideTransaction() {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var repo = new JdbcStaffCommandRepository(named) {
            @Override
            public java.util.Optional<Staff> findById(UUID id) {
                if (checks.incrementAndGet() == 2)
                    owner.update("UPDATE cane.staff_users SET is_active=false WHERE id=?", id);
                return super.findById(id);
            }
        };
        var current = new StaffCommandService(
                repo,
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class),
                transactions,
                new AdvisoryLocks(named));
        assertThatThrownBy(() -> build(repository, events, current).initialize(command(1, KEY)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(stock()).isZero();
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stock", "event", "ledger", "commit"})
    void everyWriteAndBeforeCommitRollsBackThenSameCommandCanRetry(String point) {
        var repo = new JdbcInitialStockRepository(named) {
            @Override
            public void setStock(String sku, int quantity, UUID ownerId) {
                super.setStock(sku, quantity, ownerId);
                if (point.equals("stock")) throw new InjectedFailure();
                if (point.equals("commit"))
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    throw new InjectedFailure();
                                }
                            });
            }

            @Override
            public void appendRestock(UUID id, String sku, int quantity, UUID event, UUID ownerId, String reason) {
                super.appendRestock(id, sku, quantity, event, ownerId, reason);
                if (point.equals("ledger")) throw new InjectedFailure();
            }
        };
        var writer = new BusinessEventWriter(named, json) {
            @Override
            public Recorded append(BusinessEvent event, OperationStamp stamp) {
                var result = super.append(event, stamp);
                if (point.equals("event")) throw new InjectedFailure();
                return result;
            }
        };
        assertThatThrownBy(() -> build(repo, writer, staff).initialize(command(1, KEY)))
                .isInstanceOf(InjectedFailure.class);
        assertThat(stock()).isZero();
        assertCounts(0, 0);
        var committed = service.initialize(command(1, KEY));
        assertThat(service.initialize(command(1, KEY)).operationId()).isEqualTo(committed.operationId());
        assertThat(stock()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void sameKeyInFlightIsBusyThenReplayOrMismatchWithoutDoubleImport() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var repo = new JdbcInitialStockRepository(named) {
            @Override
            public void setStock(String sku, int quantity, UUID ownerId) {
                super.setStock(sku, quantity, ownerId);
                entered.countDown();
                await(release);
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> build(repo, events, staff).initialize(command(1, KEY)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.initialize(command(1, KEY)))
                        .isInstanceOf(RequestInProgressException.class);
                assertThatThrownBy(() -> service.initialize(command(2, KEY)))
                        .isInstanceOf(RequestInProgressException.class);
            } finally {
                release.countDown();
            }
            var result = first.get(10, TimeUnit.SECONDS);
            assertThat(service.initialize(command(1, KEY)).operationId()).isEqualTo(result.operationId());
            assertThatThrownBy(() -> service.initialize(command(2, KEY)))
                    .isInstanceOf(IdempotencyMismatchException.class);
        }
        assertThat(stock()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void integerMaximumImportsWithoutOverflowAndMissingProductHasNoAnchor() {
        service.initialize(command(Integer.MAX_VALUE, KEY));
        assertThat(stock()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> service.initialize(new InitialStockCommand(
                        actor, "cane-vinegar", 1, "Initial fixture count", "initial-stock-fixture-0002")))
                .isInstanceOf(IllegalStateException.class);
        assertCounts(1, 1);
    }

    @Test
    void operationalCliUsesRealServiceReplaysAndRejectsRuntimeCredentialAndFixturesInProduction() throws Exception {
        var file = cliManifest();
        var output = new StringWriter();
        String[] args = {"--manifest", file.toString(), "--environment", "test"};
        assertThat(InitialStockCli.run(args, environment, new PrintWriter(output)))
                .isZero();
        assertThat(InitialStockCli.run(args, environment, new PrintWriter(output)))
                .isZero();
        assertThat(output.toString())
                .contains("replayed=false", "replayed=true")
                .doesNotContain(KEY, "Initial fixture count", "fixture-hash");
        assertCounts(1, 1);
        assertThat(stock()).isEqualTo(1);
        var api = new java.util.HashMap<>(environment);
        api.put("CANE_OPERATIONS_DB_USER", "cane_api");
        api.put("CANE_OPERATIONS_DB_PASSWORD", TestInfrastructure.API_PASSWORD);
        assertThat(InitialStockCli.run(args, api, new PrintWriter(output))).isOne();
        assertThat(InitialStockCli.run(
                        new String[] {"--manifest", file.toString(), "--environment", "production"},
                        environment,
                        new PrintWriter(output)))
                .isOne();
        assertCounts(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "worker"})
    void cliRejectsConfiguredRuntimeRolesAfterRenamingWithoutWritingAnything(String mode) throws Exception {
        String original = "cane_" + mode;
        String renamed = "runtime_" + mode + "_" + UUID.randomUUID().toString().replace("-", "");
        var variables = new java.util.HashMap<>(environment);
        variables.put("CANE_OPERATIONS_DB_USER", renamed);
        variables.put(
                "CANE_OPERATIONS_DB_PASSWORD",
                mode.equals("api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD);
        variables.put(mode.equals("api") ? "CANE_API_DB_USER" : "CANE_WORKER_DB_USER", renamed);
        var file = cliManifest();
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("ALTER ROLE " + original + " RENAME TO " + renamed);
            try {
                assertThat(InitialStockCli.run(
                                new String[] {"--manifest", file.toString(), "--environment", "test"},
                                variables,
                                new PrintWriter(new StringWriter())))
                        .isOne();
                assertThat(stock()).isZero();
                assertCounts(0, 0);
            } finally {
                statement.execute("ALTER ROLE " + renamed + " RENAME TO " + original);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-api", "missing-worker", "unknown-api", "unknown-worker", "same-role"})
    void cliFailsClosedWhenRuntimeRoleConfigurationIsIncompleteOrInvalid(String fault) throws Exception {
        var variables = new java.util.HashMap<>(environment);
        switch (fault) {
            case "missing-api" -> variables.remove("CANE_API_DB_USER");
            case "missing-worker" -> variables.remove("CANE_WORKER_DB_USER");
            case "unknown-api" -> variables.put("CANE_API_DB_USER", "nonexistent_runtime_role");
            case "unknown-worker" -> variables.put("CANE_WORKER_DB_USER", "nonexistent_runtime_role");
            case "same-role" -> variables.put("CANE_WORKER_DB_USER", "cane_api");
            default -> throw new AssertionError("Unknown fault");
        }
        var file = cliManifest();
        assertThat(InitialStockCli.run(
                        new String[] {"--manifest", file.toString(), "--environment", "test"},
                        variables,
                        new PrintWriter(new StringWriter())))
                .isOne();
        assertThat(stock()).isZero();
        assertCounts(0, 0);
    }

    @Test
    void cliAcceptsSeparateLeastPrivilegeOperationalRoleAndReplays() throws Exception {
        String role = "stock_ops_" + UUID.randomUUID().toString().replace("-", "");
        String database = owner.queryForObject("SELECT current_database()", String.class);
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            // Password is an ephemeral hex fixture, never an operator credential.
            statement.execute(
                    "CREATE ROLE " + role + " LOGIN PASSWORD '" + TestInfrastructure.MIGRATION_PASSWORD + "'");
        }
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO " + role);
        owner.execute("GRANT USAGE ON SCHEMA cane TO " + role);
        owner.execute(
                "GRANT SELECT ON cane.flyway_schema_history,cane.staff_users,cane.products,cane.business_events TO "
                        + role);
        owner.execute("GRANT UPDATE (stock_qty,updated_by,updated_at) ON cane.products TO " + role);
        owner.execute("GRANT INSERT ON cane.business_events,cane.stock_movements TO " + role);
        var variables = new java.util.HashMap<>(environment);
        variables.put("CANE_OPERATIONS_DB_USER", role);
        var file = cliManifest();
        var output = new StringWriter();
        String[] arguments = {"--manifest", file.toString(), "--environment", "test"};
        assertThat(InitialStockCli.run(arguments, variables, new PrintWriter(output)))
                .isZero();
        assertThat(InitialStockCli.run(arguments, variables, new PrintWriter(output)))
                .isZero();
        assertThat(output.toString()).contains("replayed=false", "replayed=true");
        assertThat(stock()).isEqualTo(1);
        assertCounts(1, 1);
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("GRANT cane_api TO " + role);
            try {
                assertThat(InitialStockCli.run(arguments, variables, new PrintWriter(output)))
                        .isOne();
                assertThat(stock()).isEqualTo(1);
                assertCounts(1, 1);
            } finally {
                statement.execute("REVOKE cane_api FROM " + role);
            }
        }
    }

    private Path cliManifest() throws Exception {
        var file = directory.resolve("initial.json");
        Files.writeString(
                file,
                json.writeValueAsString(json.createObjectNode()
                        .put("dataKind", "fixture")
                        .put("ownerId", actor.toString())
                        .put("sku", SKU)
                        .put("quantity", 1)
                        .put("reason", "Initial fixture count")
                        .put("key", KEY)));
        return file;
    }

    private InitialStockService build(
            InitialStockRepository repo, BusinessEventWriter writer, StaffCommandService auth) {
        return new InitialStockService(repo, auth, operations, writer, json);
    }

    private InitialStockCommand command(int quantity, String key) {
        return new InitialStockCommand(actor, SKU, quantity, "Initial fixture count", key);
    }

    private int stock() {
        return owner.queryForObject("SELECT stock_qty FROM cane.products WHERE sku=?", Integer.class, SKU);
    }

    private void assertCounts(int eventCount, int movementCount) {
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.business_events", Integer.class))
                .isEqualTo(eventCount);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.stock_movements", Integer.class))
                .isEqualTo(movementCount);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.notification_outbox", Integer.class))
                .isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class InjectedFailure extends RuntimeException {}
}
