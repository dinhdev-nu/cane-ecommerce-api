package vn.cane.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.cane.admin.domain.StaffRole;
import vn.cane.admin.infrastructure.JdbcStaffCommandRepository;
import vn.cane.admin.infrastructure.cli.StaffCommand;
import vn.cane.bootstrap.PasswordConfiguration;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.bootstrap.cli.OperationalDatabase;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.RequestInProgressException;
import vn.cane.support.transaction.Transactions;

@Testcontainers
class StaffCommandIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static final String PASSWORD = "  mía ngọt 🍃 mật khẩu thử  ";
    private static final String NEW_PASSWORD = " another valid test password ";
    private com.zaxxer.hikari.HikariDataSource source;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private StaffCommandService service;
    private PasswordEncoder encoder;
    private Map<String, String> environment;

    @BeforeEach
    void prepare() throws Exception {
        String database = "cane_staff_" + UUID.randomUUID().toString().replace("-", "");
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
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        jdbc.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        jdbc.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api,cane_worker");
        jdbc.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        source = OperationalDatabase.open(environment);
        source.setMaximumPoolSize(3);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(source));
        context.register(
                TransactionConfiguration.class,
                PasswordConfiguration.class,
                Transactions.class,
                AdvisoryLocks.class,
                JdbcStaffCommandRepository.class,
                StaffCommandService.class);
        context.refresh();
        service = context.getBean(StaffCommandService.class);
        encoder = context.getBean(PasswordEncoder.class);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (source != null) source.close();
    }

    @Test
    void createsStableOwnerAndOperatorAndRerunsPreserveAllExistingFieldsAndSessions() {
        var owner = service.create("  OwNeR  ", PASSWORD, StaffRole.OWNER);
        var operator = service.create("operator", PASSWORD, StaffRole.OPERATOR);
        assertThat(owner.changed()).isTrue();
        assertThat(owner.authVersion()).isEqualTo(1);
        assertThat(owner.active()).isTrue();
        assertThat(owner.role()).isEqualTo(StaffRole.OWNER);
        assertThat(operator.staffId()).isNotEqualTo(owner.staffId());
        assertThat(jdbc.queryForObject(
                        "SELECT username FROM cane.staff_users WHERE id=?", String.class, owner.staffId()))
                .isEqualTo("owner");
        assertThat(encoder.matches(PASSWORD, hash(owner.staffId()))).isTrue();
        jdbc.update(
                "UPDATE cane.staff_users SET failed_count=3,locked_until=now()+interval '15 minutes' WHERE id=?",
                operator.staffId());
        session(owner.staffId());
        session(operator.staffId());
        String before = snapshot("staff_users");
        String sessions = snapshot("staff_sessions");
        assertThat(service.create("OWNER", NEW_PASSWORD, StaffRole.OPERATOR).changed())
                .isFalse();
        assertThat(service.create("  OPERATOR ", NEW_PASSWORD, StaffRole.OWNER).changed())
                .isFalse();
        assertThat(snapshot("staff_users")).isEqualTo(before);
        assertThat(snapshot("staff_sessions")).isEqualTo(sessions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cane.staff_users", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void deliberateChangesRevokeOnlyTargetSessionsAndNoopAccessPreservesVersion() {
        var owner = service.create("owner", PASSWORD, StaffRole.OWNER);
        var operator = service.create("operator", PASSWORD, StaffRole.OPERATOR);
        session(owner.staffId());
        session(operator.staffId());
        assertThat(service.changeRole("operator", StaffRole.OPERATOR).changed()).isFalse();
        assertThat(service.changeActive("operator", true).changed()).isFalse();
        assertThat(sessionCount(operator.staffId())).isEqualTo(1);
        var reset = service.resetPassword("operator", NEW_PASSWORD);
        assertThat(reset.staffId()).isEqualTo(operator.staffId());
        assertThat(reset.authVersion()).isEqualTo(2);
        assertThat(encoder.matches(PASSWORD, hash(operator.staffId()))).isFalse();
        assertThat(encoder.matches(NEW_PASSWORD, hash(operator.staffId()))).isTrue();
        assertThat(sessionCount(operator.staffId())).isZero();
        assertThat(sessionCount(owner.staffId())).isEqualTo(1);
        session(operator.staffId());
        assertThat(service.changeRole("operator", StaffRole.OWNER).authVersion())
                .isEqualTo(3);
        assertThat(sessionCount(operator.staffId())).isZero();
        session(operator.staffId());
        assertThat(service.changeActive("operator", false).authVersion()).isEqualTo(4);
        assertThat(sessionCount(operator.staffId())).isZero();
        assertThat(service.changeActive("operator", true).authVersion()).isEqualTo(5);
        // An explicit reset, even to the same raw password, deliberately revokes existing sessions.
        session(operator.staffId());
        assertThat(service.resetPassword("operator", NEW_PASSWORD).authVersion())
                .isEqualTo(6);
        assertThat(sessionCount(operator.staffId())).isZero();
    }

    @Test
    void lastActiveOwnerCannotBeDisabledOrDemotedAndMissingStaffDoesNotChangeData() {
        var owner = service.create("owner", PASSWORD, StaffRole.OWNER);
        session(owner.staffId());
        String before = snapshot("staff_users");
        String sessions = snapshot("staff_sessions");
        assertThatThrownBy(() -> service.changeActive("owner", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.changeRole("owner", StaffRole.OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.resetPassword("missing", NEW_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot("staff_users")).isEqualTo(before);
        assertThat(snapshot("staff_sessions")).isEqualTo(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "demote"})
    void concurrentCommandsShareGuardAndRetriedLoserCannotRemoveLastOwner(String firstChange) throws Exception {
        service.create("owner-a", PASSWORD, StaffRole.OWNER);
        service.create("owner-b", PASSWORD, StaffRole.OWNER);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holding = decoratedService(id -> {
            locked.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Barrier interrupted");
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> firstChange.equals("disable")
                    ? holding.changeActive("owner-a", false)
                    : holding.changeRole("owner-a", StaffRole.OPERATOR));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.changeActive("owner-b", false))
                        .isInstanceOf(RequestInProgressException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).changed()).isTrue();
        }
        assertThatThrownBy(() -> service.changeRole("owner-b", StaffRole.OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cane.staff_users WHERE role='owner' AND is_active", Integer.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "role", "active"})
    void failureAfterSessionDeletionRollsBackBothAccountAndAllSessions(String change) {
        service.create("owner", PASSWORD, StaffRole.OWNER);
        var operator = service.create("operator", PASSWORD, StaffRole.OPERATOR);
        session(operator.staffId());
        String before = snapshot("staff_users");
        String sessions = snapshot("staff_sessions");
        var fault = decoratedService(id -> {
            throw new IllegalStateException("Controlled rollback fault");
        });
        assertThatThrownBy(() -> {
                    switch (change) {
                        case "password" -> fault.resetPassword("operator", NEW_PASSWORD);
                        case "role" -> fault.changeRole("operator", StaffRole.OWNER);
                        default -> fault.changeActive("operator", false);
                    }
                })
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshot("staff_users")).isEqualTo(before);
        assertThat(snapshot("staff_sessions")).isEqualTo(sessions);
    }

    @Test
    void cliUsesSharedServiceClearsPasswordBufferAndDoesNotExposeSecrets() {
        char[] raw = PASSWORD.toCharArray();
        assertThat(command(new String[] {"--action", "create-owner", "--username", "owner"}, environment, () -> raw))
                .isZero();
        assertThat(raw).containsOnly('\0');
        String before = snapshot("staff_users");
        assertThat(command(
                        new String[] {"--action", "create-operator", "--username", "OWNER"},
                        environment,
                        () -> NEW_PASSWORD.toCharArray()))
                .isZero();
        assertThat(snapshot("staff_users")).isEqualTo(before);
        char[] invalid = "too short".toCharArray();
        assertThat(command(
                        new String[] {"--action", "reset-password", "--username", "owner"}, environment, () -> invalid))
                .isEqualTo(1);
        assertThat(invalid).containsOnly('\0');
        assertThat(snapshot("staff_users")).isEqualTo(before);
        assertThat(command(
                        new String[] {"--action", "set-role", "--username", "owner", "--role", "operator"},
                        environment,
                        () -> {
                            throw new AssertionError("Access changes must not prompt");
                        }))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cane_api", "cane_worker"})
    void runtimeCredentialsCannotManageStaffAndAreRejectedBeforePasswordPrompt(String role) {
        var runtime = new HashMap<>(environment);
        runtime.put("CANE_OPERATIONS_DB_USER", role);
        runtime.put(
                "CANE_OPERATIONS_DB_PASSWORD",
                role.equals("cane_api") ? TestInfrastructure.API_PASSWORD : TestInfrastructure.WORKER_PASSWORD);
        assertThat(command(new String[] {"--action", "create-owner", "--username", "owner"}, runtime, () -> {
                    throw new AssertionError("Runtime must be refused before reading a password");
                }))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cane.staff_users", Integer.class))
                .isZero();
    }

    @Test
    void cliRejectsPasswordArgumentsUnknownActionsAndMalformedActiveBeforeConnecting() {
        for (String[] args : new String[][] {
            {"--action", "create-owner", "--username", "owner", "--password", PASSWORD},
            {"--action", "delete", "--username", "owner"},
            {"--action", "set-active", "--username", "owner", "--active", "yes"},
            {"--action", "set-role", "--username", "owner", "--role", "admin"}
        }) {
            assertThat(command(args, Map.of(), () -> {
                        throw new AssertionError("Invalid arguments must not prompt");
                    }))
                    .isEqualTo(1);
        }
    }

    @Test
    void wrapperUsesPackagedCommandAndRedirectedInputCannotSupplyPassword() throws Exception {
        service.create("owner", PASSWORD, StaffRole.OWNER);
        var operator = service.create("operator", PASSWORD, StaffRole.OPERATOR);
        assertThat(wrapper("set-active", "operator", "-Active", "false", 0)).contains("active=false", "authVersion=2");
        String before = snapshot("staff_users");
        assertThat(wrapper("reset-password", "operator", null, null, 1)).contains("interactive");
        assertThat(snapshot("staff_users")).isEqualTo(before);
        assertThat(hash(operator.staffId())).doesNotContain(PASSWORD);
    }

    private int command(String[] args, Map<String, String> variables, StaffCommand.PasswordPrompt prompt) {
        var output = new StringWriter();
        int exit = StaffCommand.run(args, variables, prompt, new PrintWriter(output));
        assertThat(output.toString())
                .doesNotContain(
                        PASSWORD,
                        NEW_PASSWORD,
                        TestInfrastructure.MIGRATION_PASSWORD,
                        TestInfrastructure.API_PASSWORD,
                        TestInfrastructure.WORKER_PASSWORD,
                        "Exception",
                        "jdbc:",
                        "$argon2");
        return exit;
    }

    private String wrapper(String action, String username, String option, String value, int expectedExit)
            throws Exception {
        var arguments = new java.util.ArrayList<>(java.util.List.of(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/staff/Manage-Staff.ps1",
                "-Action",
                action,
                "-Username",
                username));
        if (option != null) arguments.addAll(java.util.List.of(option, value));
        var builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        builder.environment().putAll(environment);
        builder.environment().put("CANE_DB_URL", "invalid-runtime-must-not-start");
        var child = builder.start();
        child.getOutputStream().write((PASSWORD + "\n" + PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
        child.getOutputStream().close();
        if (!child.waitFor(25, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Staff CLI timed out");
        }
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.exitValue()).withFailMessage(output).isEqualTo(expectedExit);
        assertThat(output)
                .doesNotContain(
                        PASSWORD,
                        TestInfrastructure.MIGRATION_PASSWORD,
                        "jdbc:",
                        "$argon2",
                        "Tomcat",
                        "Worker runtime started");
        return output;
    }

    private StaffCommandService decoratedService(Consumer<UUID> afterRevoke) {
        var delegate = context.getBean(JdbcStaffCommandRepository.class);
        var repository = new StaffCommandRepository() {
            public Optional<Staff> lockByUsername(String username) {
                return delegate.lockByUsername(username);
            }

            public Staff insert(String username, String hash, StaffRole role) {
                return delegate.insert(username, hash, role);
            }

            public long countActiveOwners() {
                return delegate.countActiveOwners();
            }

            public void changePassword(UUID id, String hash) {
                delegate.changePassword(id, hash);
            }

            public void changeAccess(UUID id, StaffRole role, boolean active) {
                delegate.changeAccess(id, role, active);
            }

            public void revokeSessions(UUID id) {
                delegate.revokeSessions(id);
                afterRevoke.accept(id);
            }
        };
        return new StaffCommandService(
                repository, encoder, context.getBean(Transactions.class), context.getBean(AdvisoryLocks.class));
    }

    private void session(UUID staffId) {
        byte[] tokenHash = new byte[32];
        new java.security.SecureRandom().nextBytes(tokenHash);
        jdbc.update(
                "INSERT INTO cane.staff_sessions(token_hash,staff_id,auth_version) SELECT ?,id,auth_version FROM cane.staff_users WHERE id=?",
                tokenHash,
                staffId);
        Arrays.fill(tokenHash, (byte) 0);
    }

    private int sessionCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM cane.staff_sessions WHERE staff_id=?", Integer.class, id);
    }

    private String hash(UUID id) {
        return jdbc.queryForObject("SELECT password_hash FROM cane.staff_users WHERE id=?", String.class, id);
    }

    private String snapshot(String table) {
        return jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM cane." + table
                        + " t",
                String.class);
    }
}
