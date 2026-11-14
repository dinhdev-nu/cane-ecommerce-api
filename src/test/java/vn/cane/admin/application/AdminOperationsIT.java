package vn.cane.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.infrastructure.JdbcStaffSessionRepository;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.CanonicalOperation;
import vn.cane.support.idempotency.IdempotencyMismatchException;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.idempotency.OperationReceipts;
import vn.cane.support.idempotency.OperationStamp;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.RequestInProgressException;
import vn.cane.support.transaction.SqlFailures;
import vn.cane.support.transaction.Transactions;

/** Internal A-TX harness. Uses real current staff/session/setting checks; no test POST controller. */
@Testcontainers
class AdminOperationsIT {
    private static final String KEY = "fixture-operation-0001";
    private static final String ROUTE = "/api/v1/admin/stock-adjustments";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private final JsonMapper json = JsonMapper.builder().build();
    private AnnotationConfigApplicationContext context;
    private HikariDataSource source;
    private JdbcTemplate owner;
    private JdbcTemplate jdbc;
    private Transactions transactions;
    private StaffSessionService sessions;
    private StaffAuthorization authorization;
    private StaffPrincipal principal;
    private BusinessEventWriter events;
    private OperationReceipts receipts;
    private IdempotentOperations operations;

    @BeforeEach
    void prepare() throws Exception {
        String database = "cane_idem_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        owner = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api, cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        owner.update(
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope) VALUES ('IDEM-A','Fixture','unit',25000,'city')");
        source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername("cane_api");
        source.setPassword(TestInfrastructure.API_PASSWORD);
        source.setMaximumPoolSize(4);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(3000);
        source.addDataSourceProperty(
                "options", "-c search_path=cane,pg_catalog -c statement_timeout=5000 -c lock_timeout=2000");
        source.addDataSourceProperty("connectTimeout", "3");
        source.addDataSourceProperty("socketTimeout", "6");
        jdbc = new JdbcTemplate(source);
        var named = new NamedParameterJdbcTemplate(jdbc);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(TransactionConfiguration.class, Transactions.class);
        context.refresh();
        transactions = context.getBean(Transactions.class);
        sessions = new StaffSessionService(new JdbcStaffSessionRepository(named), transactions);
        authorization = new StaffAuthorization(sessions);
        events = new BusinessEventWriter(named, json);
        receipts = new OperationReceipts(named, json);
        operations = new IdempotentOperations(transactions, new AdvisoryLocks(named), receipts);
        principal = staff("owner");
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (source != null) source.close();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
    }

    @Test
    void replayKeepsOriginalResultBeforeChangedStockAndExpectedGuard() {
        var request = command(KEY, 1, 0);
        var first = operations.execute(ownerPermission(), request, stock(1, 0));
        assertThat(first.replayed()).isFalse();
        owner.update("UPDATE cane.products SET stock_qty=9 WHERE sku='IDEM-A'");
        var replay = operations.execute(ownerPermission(), request, forbiddenWork());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(replay.result().path("stockAfter").asInt()).isEqualTo(1);
        assertThat(stockQty()).isEqualTo(9);
        assertThatThrownBy(() ->
                        operations.execute(ownerPermission(), command("fixture-operation-0002", 1, 0), stock(1, 0)))
                .isInstanceOf(FixtureConflict.class);
        assertCounts(1, 1);
    }

    @Test
    void changedBodyOrRouteNeverReplaysAndDoesNotRunTheWrite() {
        operations.execute(ownerPermission(), command(KEY, 1, null), stock(1, null));
        assertThatThrownBy(() -> operations.execute(ownerPermission(), command(KEY, 2, null), forbiddenWork()))
                .isInstanceOf(IdempotencyMismatchException.class);
        var otherRoute = CanonicalOperation.adminPost("POST", "/api/v1/admin/payments", json.createObjectNode(), KEY);
        assertThatThrownBy(() -> operations.execute(ownerPermission(), otherRoute, forbiddenWork()))
                .isInstanceOf(IdempotencyMismatchException.class);
        assertThat(stockQty()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void sameKeyOnDifferentAuthorizedActorsIsANewOperation() {
        var second = staff("owner");
        var firstResult = operations.execute(ownerPermission(), command(KEY, 1, null), stock(1, null));
        var secondResult = operations.execute(
                () -> authorization.requireOwner(second).id(), command(KEY, 1, null), stock(1, null));
        assertThat(secondResult.replayed()).isFalse();
        assertThat(secondResult.operationId()).isNotEqualTo(firstResult.operationId());
        assertThat(stockQty()).isEqualTo(2);
        assertCounts(2, 2);
    }

    @Test
    void newValidNoopGetsOneAnchorAndReplayingItAddsNothing() {
        var request = command(KEY, 0, null);
        var result = operations.execute(
                ownerPermission(),
                request,
                scope -> events.append(
                        new BusinessEvent(
                                UUID.randomUUID(),
                                null,
                                null,
                                "operation_noop",
                                null,
                                null,
                                scope.actor().toString(),
                                null,
                                null,
                                json.createObjectNode().put("sku", "IDEM-A"),
                                null),
                        scope.stamp(json.createObjectNode().put("sku", "IDEM-A").put("stockAfter", 0))));
        assertThat(result.replayed()).isFalse();
        assertThat(operations
                        .execute(ownerPermission(), request, forbiddenWork())
                        .replayed())
                .isTrue();
        assertCounts(1, 0);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.notification_outbox", Long.class))
                .isZero();
    }

    @Test
    void pendingKeyReturnsBusyForSameOrDifferentHashThenBecomesReplayOrMismatch() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var request = command(KEY, 1, null);
        try (var executor = Executors.newFixedThreadPool(1)) {
            var first = executor.submit(() -> operations.execute(ownerPermission(), request, scope -> {
                entered.countDown();
                await(finish);
                stock(1, null).accept(scope);
            }));
            try {
                await(entered);
                assertThatThrownBy(() -> operations.execute(ownerPermission(), request, forbiddenWork()))
                        .isInstanceOf(RequestInProgressException.class);
                assertThatThrownBy(() -> operations.execute(ownerPermission(), command(KEY, 2, null), forbiddenWork()))
                        .isInstanceOf(RequestInProgressException.class);
            } finally {
                finish.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).replayed()).isFalse();
        }
        assertThat(operations
                        .execute(ownerPermission(), request, forbiddenWork())
                        .replayed())
                .isTrue();
        assertThatThrownBy(() -> operations.execute(ownerPermission(), command(KEY, 2, null), forbiddenWork()))
                .isInstanceOf(IdempotencyMismatchException.class);
        assertThat(stockQty()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void transactionRechecksReceiptAfterAnotherRequestCommitsInTheFastReadGap() throws Exception {
        var gap = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var checks = new AtomicInteger();
        Supplier<UUID> delayed = () -> {
            UUID actor = authorization.requireOwner(principal).id();
            if (checks.incrementAndGet() == 2) {
                gap.countDown();
                await(resume);
            }
            return actor;
        };
        var request = command(KEY, 1, null);
        try (var executor = Executors.newFixedThreadPool(1)) {
            var late = executor.submit(() -> operations.execute(delayed, request, forbiddenWork()));
            OperationReceipt winner;
            try {
                await(gap);
                winner = operations.execute(ownerPermission(), request, stock(1, null));
            } finally {
                resume.countDown();
            }
            var replay = late.get(10, TimeUnit.SECONDS);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.operationId()).isEqualTo(winner.operationId());
        }
        assertCounts(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "version", "expired", "deleted"})
    void currentSessionRevocationIsCheckedBeforeFastReplayOrHashMismatch(String change) {
        operations.execute(ownerPermission(), command(KEY, 1, null), stock(1, null));
        switch (change) {
            case "inactive" ->
                owner.update(
                        "UPDATE cane.staff_users SET is_active=false WHERE id=?",
                        principal.actor().id());
            case "version" ->
                owner.update(
                        "UPDATE cane.staff_users SET auth_version=auth_version+1 WHERE id=?",
                        principal.actor().id());
            case "expired" ->
                owner.update(
                        "UPDATE cane.staff_sessions SET created_at=now()-interval '9 hours',expires_at=now()-interval '1 hour' WHERE staff_id=?",
                        principal.actor().id());
            case "deleted" ->
                owner.update(
                        "DELETE FROM cane.staff_sessions WHERE staff_id=?",
                        principal.actor().id());
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(() -> operations.execute(ownerPermission(), command(KEY, 2, null), forbiddenWork()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertCounts(1, 1);
    }

    @Test
    void revokedBankPermissionAndOperatorCannotUseAnOwnerReceipt() {
        var operator = staff("operator");
        owner.update("UPDATE cane.settings SET value='true'::jsonb WHERE key='operator_can_confirm_bank'");
        Supplier<UUID> permission =
                () -> authorization.requireBankConfirmation(operator).id();
        operations.execute(permission, command(KEY, 1, null), stock(1, null));
        owner.update("UPDATE cane.settings SET value='false'::jsonb WHERE key='operator_can_confirm_bank'");
        assertThatThrownBy(() -> operations.execute(permission, command(KEY, 1, null), forbiddenWork()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> operations.execute(
                        () -> authorization.requireOwner(operator).id(), command(KEY, 1, null), forbiddenWork()))
                .isInstanceOf(AccessDeniedException.class);
        assertCounts(1, 1);
    }

    @Test
    void permissionIsRecheckedInsideTheTransactionBeforeAnyWrite() {
        var checks = new AtomicInteger();
        Supplier<UUID> permission = () -> {
            if (checks.incrementAndGet() == 2)
                owner.update(
                        "DELETE FROM cane.staff_sessions WHERE staff_id=?",
                        principal.actor().id());
            return authorization.requireOwner(principal).id();
        };
        assertThatThrownBy(() -> operations.execute(permission, command(KEY, 1, null), forbiddenWork()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertCounts(0, 0);
    }

    @Test
    void failureAfterWritesLeavesNoAnchorAndRetrySameCommandExecutesOnce() {
        var request = command(KEY, 1, null);
        assertThatThrownBy(() -> operations.execute(ownerPermission(), request, scope -> {
                    stock(1, null).accept(scope);
                    throw new InjectedFailure();
                }))
                .isInstanceOf(InjectedFailure.class);
        assertThat(stockQty()).isZero();
        assertCounts(0, 0);
        assertThat(operations
                        .execute(ownerPermission(), request, stock(1, null))
                        .replayed())
                .isFalse();
        assertThat(operations
                        .execute(ownerPermission(), request, forbiddenWork())
                        .replayed())
                .isTrue();
        assertCounts(1, 1);
    }

    @Test
    void missingOrWrongHashAnchorRollsBackTheWholeOperation() {
        var request = command(KEY, 1, null);
        assertThatThrownBy(() -> operations.execute(
                        ownerPermission(),
                        request,
                        scope -> jdbc.update("UPDATE cane.products SET stock_qty=5 WHERE sku='IDEM-A'")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stockQty()).isZero();
        assertThatThrownBy(() -> operations.execute(ownerPermission(), request, scope -> {
                    jdbc.update("UPDATE cane.products SET stock_qty=5 WHERE sku='IDEM-A'");
                    events.append(
                            new BusinessEvent(
                                    UUID.randomUUID(),
                                    null,
                                    null,
                                    "operation_noop",
                                    null,
                                    null,
                                    scope.actor().toString(),
                                    null,
                                    null,
                                    json.createObjectNode(),
                                    null),
                            new OperationStamp(KEY, new byte[32], json.createObjectNode()));
                }))
                .isInstanceOf(IdempotencyMismatchException.class);
        assertThat(stockQty()).isZero();
        assertThatThrownBy(() -> operations.execute(ownerPermission(), request, scope -> {
                    stock(1, null).accept(scope);
                    // The operation was correctly stamped above; append a second anchor under the same actor/key.
                    stock(1, null).accept(scope);
                }))
                .isInstanceOf(DataAccessException.class);
        assertThat(stockQty()).isZero();
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyActorKeyUniqueCanReadTheWinnerAfterConfirmedRollback(boolean mismatch) {
        var request = command(KEY, 1, null);
        UUID winnerId = UUID.randomUUID();
        Supplier<OperationReceipt> invoke = () -> operations.execute(ownerPermission(), request, scope -> {
            jdbc.update("UPDATE cane.products SET stock_qty=7 WHERE sku='IDEM-A'");
            byte[] hash = request.hash();
            if (mismatch) hash[0] ^= 1;
            // Independent committed writer deliberately ignores advisory locking to force the UNIQUE fallback.
            owner.update(
                    "INSERT INTO cane.business_events(id,kind,actor,operation_key,request_hash,operation_result) VALUES (?,'operation_noop',?,?,?,'{\"sku\":\"IDEM-A\",\"stockAfter\":0}')",
                    winnerId,
                    scope.actor().toString(),
                    KEY,
                    hash);
            stock(1, null).accept(scope);
        });
        if (mismatch) assertThatThrownBy(invoke::get).isInstanceOf(IdempotencyMismatchException.class);
        else {
            var replay = invoke.get();
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.operationId()).isEqualTo(winnerId);
        }
        assertThat(stockQty()).isZero();
        assertCounts(1, 0);
    }

    @Test
    void unrelatedUniqueIsNotTreatedAsOperationReplay() {
        owner.update(
                "INSERT INTO cane.business_events(kind,actor,source_key) VALUES ('owner_alert','system','fixture-source')");
        Throwable failure = catchThrowable(() -> operations.execute(ownerPermission(), command(KEY, 1, null), scope -> {
            jdbc.update("UPDATE cane.products SET stock_qty=7 WHERE sku='IDEM-A'");
            events.append(
                    new BusinessEvent(
                            UUID.randomUUID(),
                            null,
                            null,
                            "stock_adjusted",
                            null,
                            null,
                            scope.actor().toString(),
                            null,
                            null,
                            json.createObjectNode(),
                            "fixture-source"),
                    scope.stamp(json.createObjectNode()));
        }));
        assertThat(SqlFailures.uniqueConstraint(failure)).contains("business_events_source_unique");
        assertThat(stockQty()).isZero();
        assertThat(receipts.find(principal.actor().id(), KEY)).isEmpty();
        assertCounts(1, 0);
    }

    @Test
    void deadlockProtocolRetryKeepsTheOriginalHashKeyAndOnlyOneEffect() {
        var tries = new AtomicInteger();
        var request = command(KEY, 1, null);
        var result = operations.execute(ownerPermission(), request, scope -> {
            stock(1, null).accept(scope);
            if (tries.incrementAndGet() < 3)
                jdbc.execute(
                        "DO $$ BEGIN RAISE EXCEPTION 'controlled deadlock protocol fixture' USING ERRCODE='40P01'; END $$");
        });
        assertThat(tries.get()).isEqualTo(3);
        assertThat(result.replayed()).isFalse();
        assertThat(receipts.find(principal.actor().id(), KEY))
                .hasValueSatisfying(stored -> assertThat(stored.requestHash()).isEqualTo(request.hash()));
        assertThat(stockQty()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void postCommitFailureAndLostResponseDoNotRepeatTheEffect() {
        var request = command(KEY, 1, null);
        var first = operations.execute(ownerPermission(), request, scope -> {
            stock(1, null).accept(scope);
            transactions.afterCommit(() -> {
                throw new InjectedFailure();
            });
        });
        var retry = operations.execute(ownerPermission(), request, forbiddenWork());
        assertThat(retry.operationId()).isEqualTo(first.operationId());
        assertThat(retry.replayed()).isTrue();
        assertThat(stockQty()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void nestingInsideAnotherTransactionIsRejectedBeforeAuthorization() {
        assertThatThrownBy(() -> transactions.write(() -> operations.execute(
                        () -> {
                            throw new AssertionError("Authorization must not run");
                        },
                        command(KEY, 1, null),
                        forbiddenWork())))
                .isInstanceOf(IllegalStateException.class);
        assertCounts(0, 0);
    }

    private Supplier<UUID> ownerPermission() {
        return () -> authorization.requireOwner(principal).id();
    }

    private CanonicalOperation command(String key, int delta, Integer expected) {
        var body = json.createObjectNode()
                .put("sku", "IDEM-A")
                .put("kind", "restock")
                .put("mode", "delta")
                .put("value", delta);
        if (expected == null) body.putNull("expectedStockQty");
        else body.put("expectedStockQty", expected);
        return CanonicalOperation.adminPost("POST", ROUTE, body, key);
    }

    private Consumer<IdempotentOperations.Operation> stock(int delta, Integer expected) {
        return scope -> {
            int before = jdbc.queryForObject(
                    "SELECT stock_qty FROM cane.products WHERE sku='IDEM-A' FOR UPDATE", Integer.class);
            if (expected != null && before != expected) throw new FixtureConflict();
            int after = Math.addExact(before, delta);
            jdbc.update("UPDATE cane.products SET stock_qty=?,updated_by=? WHERE sku='IDEM-A'", after, scope.actor());
            UUID eventId = UUID.randomUUID();
            UUID movement = UUID.randomUUID();
            var result = json.createObjectNode()
                    .put("sku", "IDEM-A")
                    .put("kind", "restock")
                    .put("stockBefore", before)
                    .put("stockAfter", after)
                    .put("delta", delta)
                    .put("movementId", movement.toString());
            events.append(
                    new BusinessEvent(
                            eventId,
                            null,
                            null,
                            "stock_adjusted",
                            null,
                            null,
                            scope.actor().toString(),
                            null,
                            null,
                            result,
                            null),
                    scope.stamp(result));
            jdbc.update(
                    "INSERT INTO cane.stock_movements(id,sku,delta,reason,event_id,actor) VALUES (?,'IDEM-A',?,'restock',?,?)",
                    movement,
                    delta,
                    eventId,
                    scope.actor().toString());
        };
    }

    private StaffPrincipal staff(String role) {
        UUID id = UUID.randomUUID();
        owner.update(
                "INSERT INTO cane.staff_users(id,username,password_hash,role) VALUES (?,?,'fixture-hash',?)",
                id,
                "idem-" + id,
                role);
        var issued = transactions.write(() -> sessions.issue(id, 1, null));
        return sessions.authenticate(issued.token().value()).orElseThrow();
    }

    private int stockQty() {
        return owner.queryForObject("SELECT stock_qty FROM cane.products WHERE sku='IDEM-A'", Integer.class);
    }

    private void assertCounts(long eventCount, long movementCount) {
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.business_events", Long.class))
                .isEqualTo(eventCount);
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.stock_movements", Long.class))
                .isEqualTo(movementCount);
    }

    private static Consumer<IdempotentOperations.Operation> forbiddenWork() {
        return ignored -> {
            throw new AssertionError("Replay/denial must not execute business work");
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class FixtureConflict extends RuntimeException {}

    private static final class InjectedFailure extends RuntimeException {}
}
