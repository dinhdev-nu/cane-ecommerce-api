package vn.cane.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import vn.cane.fixture.TestInfrastructure;

/** WP01-02: trigger errors and runtime permission errors are independent contracts. */
@Testcontainers
class DataProtectionIT {
    private static final List<String> TABLES = List.of(
            "staff_users",
            "staff_sessions",
            "products",
            "settings",
            "orders",
            "order_items",
            "payments",
            "business_events",
            "stock_movements",
            "notification_outbox");
    private static final String ORDER_A = "00000000-0000-0000-0000-000000000001";
    private static final String ORDER_B = "00000000-0000-0000-0000-000000000002";
    private static final String EVENT = "00000000-0000-0000-0000-000000000003";
    private static final String STAFF = "00000000-0000-0000-0000-000000000004";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private String database;
    private String jdbcUrl;
    private JdbcTemplate owner;

    @BeforeEach
    void migrateDisposableDatabase() throws Exception {
        database = "cane_protection_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        jdbcUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        owner = jdbc("cane_migrator", TestInfrastructure.MIGRATION_PASSWORD);
        // Infrastructure bootstrap, before schema grants. The runtime receives CONNECT only.
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api, cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        Flyway.configure()
                .dataSource(jdbcUrl, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD)
                .defaultSchema("cane")
                .schemas("cane")
                .locations("classpath:db/migration/core")
                .ignoreMigrationPatterns(new String[0])
                .cleanDisabled(true)
                .load()
                .migrate();
        POSTGRES.copyFileToContainer(
                MountableFile.forHostPath(
                        Path.of("scripts/database/Grant-Runtime.sql").toAbsolutePath()),
                "/tmp/cane-runtime-grants.sql");
        seedHistory();
    }

    @Test
    void rejectsEveryHistoryMutationAsTriggerErrorsWithDmlPrivileges() {
        for (String table : List.of("order_items", "business_events", "stock_movements")) {
            String before = rows(table);
            // The schema owner has DML/TRUNCATE: 55000 proves the trigger ran.
            rejected(owner, "UPDATE cane." + table + " SET id=gen_random_uuid()", "55000");
            rejected(owner, "DELETE FROM cane." + table, "55000");
            rejected(owner, "TRUNCATE cane." + table + " CASCADE", "55000");
            assertThat(rows(table)).isEqualTo(before);
        }
        rejected(owner, "TRUNCATE cane.payments CASCADE", "55000");
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.business_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void protectsAllOriginalReceiptFactsAndDeletion() {
        UUID receipt = receipt("bank", null, false);
        String before = rows("payments");
        for (String change : List.of(
                "id=gen_random_uuid()",
                "method='cash'",
                "amount_vnd=40001",
                "provider_ref='changed'",
                "bank_receipt='{}'::jsonb",
                "confirmed_by='another'",
                "confirmed_at=confirmed_at+interval '1 second'")) {
            rejected(owner, "UPDATE cane.payments SET " + change + " WHERE id='" + receipt + "'", "55000");
            assertThat(rows("payments")).isEqualTo(before);
        }
        rejected(owner, "DELETE FROM cane.payments WHERE id='" + receipt + "'", "55000");
        assertThat(rows("payments")).isEqualTo(before);
    }

    @Test
    void preservesCashPrincipalAndRefundedLinksWithoutBlockingBankReconciliation() {
        UUID cash = receipt("cash", ORDER_A, false);
        UUID principal = receipt("bank", ORDER_A, true);
        UUID refunded = receipt("bank", ORDER_A, false);
        refund(refunded);
        for (UUID fixed : List.of(cash, principal, refunded)) {
            String before = rows("payments");
            rejected(owner, "UPDATE cane.payments SET order_id='" + ORDER_B + "' WHERE id='" + fixed + "'", "55000");
            rejected(owner, "UPDATE cane.payments SET order_link_verified=false WHERE id='" + fixed + "'", "55000");
            assertThat(rows("payments")).isEqualTo(before);
        }
        rejected(owner, "UPDATE cane.payments SET applied_to_order=false WHERE id='" + principal + "'", "55000");
        UUID supplemental = receipt("bank", null, false);
        rejected(
                owner,
                "UPDATE cane.payments SET order_id='" + ORDER_A + "', order_link_verified=false WHERE id='"
                        + supplemental + "'",
                "55000");
        owner.update(
                "UPDATE cane.payments SET order_id=?::uuid, order_link_verified=true WHERE id=?",
                ORDER_A,
                supplemental);
        owner.update(
                "UPDATE cane.payments SET order_id=?::uuid, order_link_verified=true WHERE id=?",
                ORDER_B,
                supplemental);
        owner.update("UPDATE cane.payments SET order_id=null, order_link_verified=false WHERE id=?", supplemental);
        owner.update(
                "UPDATE cane.payments SET order_id=?::uuid, order_link_verified=true, applied_to_order=true WHERE id=?",
                ORDER_B,
                supplemental);
        assertThat(owner.queryForObject(
                        "SELECT applied_to_order FROM cane.payments WHERE id=?", Boolean.class, supplemental))
                .isTrue();
    }

    @Test
    void allowsCompleteRefundsButNeverReversesOrRewritesTheirEvidence() {
        for (String method : List.of("cash", "bank")) {
            UUID receipt = receipt(method, ORDER_A, false);
            owner.update(
                    "UPDATE cane.payments SET needs_review=true, reviewed_by='test-reviewer', reviewed_at=now(), note='review' WHERE id=?",
                    receipt);
            refund(receipt);
            String before = rows("payments");
            for (String change : List.of(
                    "status='succeeded', refunded_at=null, refund_reference=null",
                    "refunded_at=refunded_at+interval '1 second'",
                    "refund_reference='changed'")) {
                rejected(owner, "UPDATE cane.payments SET " + change + " WHERE id='" + receipt + "'", "55000");
                assertThat(rows("payments")).isEqualTo(before);
            }
            rejected(owner, "UPDATE cane.payments SET applied_to_order=true WHERE id='" + receipt + "'", "55000");
            owner.update(
                    "UPDATE cane.payments SET note='reviewed after refund',reviewed_by='test-reviewer',reviewed_at=now() WHERE id=?",
                    receipt);
            assertThat(owner.queryForObject("SELECT status FROM cane.payments WHERE id=?", String.class, receipt))
                    .isEqualTo("refunded");
        }
        UUID principal = receipt("bank", ORDER_B, true);
        refund(principal);
        assertThat(owner.queryForObject(
                        "SELECT applied_to_order FROM cane.payments WHERE id=?", Boolean.class, principal))
                .isTrue();
        UUID unknownOrder = receipt("bank", null, false);
        refund(unknownOrder);
        assertThat(owner.queryForObject("SELECT status FROM cane.payments WHERE id=?", String.class, unknownOrder))
                .isEqualTo("refunded");
    }

    @Test
    void touchesThreeTimestampsWithoutInventingActorsOrHistory() {
        for (String table : List.of("products", "settings", "orders")) {
            // An explicitly supplied stale timestamp must be replaced by the enabled trigger.
            owner.update("UPDATE cane." + table + " SET updated_at='2000-01-01T00:00:00Z'");
        }
        for (String table : List.of("products", "settings", "orders")) {
            assertThat(owner.queryForObject(
                            "SELECT bool_and(updated_at >= now()-interval '1 minute') FROM cane." + table,
                            Boolean.class))
                    .isTrue();
        }
        assertThat(owner.queryForObject("SELECT updated_by FROM cane.products WHERE sku='TEST-A'", UUID.class))
                .isEqualTo(UUID.fromString(STAFF));
        assertThat(owner.queryForObject(
                        "SELECT updated_by IS NULL FROM cane.settings WHERE key='accepting_orders'", Boolean.class))
                .isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM cane.business_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void reconcilesRuntimePrivilegesAndDoesNotGrantFutureObjects() throws Exception {
        applyGrants();
        owner.execute("GRANT UPDATE (password_hash) ON cane.staff_users TO cane_api");
        owner.execute("GRANT DELETE ON cane.business_events TO cane_worker");
        applyGrants();
        for (String role : List.of("cane_api", "cane_worker")) {
            for (String table : TABLES) {
                assertThat(privilege(role, table, "TRUNCATE,TRIGGER,REFERENCES,MAINTAIN"))
                        .as("%s cannot truncate/alter trigger/refer/maintain %s", role, table)
                        .isFalse();
            }
            assertThat(privilege(role, "flyway_schema_history", "SELECT")).isTrue();
            assertThat(privilege(role, "flyway_schema_history", "INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER"))
                    .isFalse();
            assertThat(owner.queryForObject("SELECT has_schema_privilege(?, 'cane', 'CREATE')", Boolean.class, role))
                    .isFalse();
            assertThat(owner.queryForObject(
                            "SELECT has_function_privilege(?, 'cane.touch_updated_at()', 'EXECUTE')",
                            Boolean.class,
                            role))
                    .isFalse();
        }
        assertThat(columnPrivilege("cane_api", "staff_users", "password_hash", "UPDATE"))
                .isFalse();
        assertThat(privilege("cane_worker", "business_events", "DELETE")).isFalse();
        owner.execute("CREATE TABLE cane.future_table(id integer)");
        owner.execute("CREATE SEQUENCE cane.future_sequence");
        owner.execute("CREATE FUNCTION cane.future_function() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
        for (String role : List.of("cane_api", "cane_worker")) {
            assertThat(privilege(role, "future_table", "SELECT,INSERT,UPDATE,DELETE"))
                    .isFalse();
            assertThat(owner.queryForObject(
                            "SELECT has_sequence_privilege(?, 'cane.future_sequence', 'USAGE,SELECT,UPDATE')",
                            Boolean.class,
                            role))
                    .isFalse();
            assertThat(owner.queryForObject(
                            "SELECT has_function_privilege(?, 'cane.future_function()', 'EXECUTE')",
                            Boolean.class,
                            role))
                    .isFalse();
        }
    }

    @Test
    void deniesDdlTruncateHistoryMutationAndSensitiveColumnsAtRuntime() throws Exception {
        applyGrants();
        for (JdbcTemplate runtime : List.of(api(), worker())) {
            for (String table : TABLES) {
                rejected(runtime, "TRUNCATE cane." + table + " CASCADE", "42501");
                rejected(runtime, "ALTER TABLE cane." + table + " DISABLE TRIGGER ALL", "42501");
            }
            for (String sql : List.of(
                    "CREATE TABLE cane.forbidden(id integer)",
                    "CREATE TEMP TABLE forbidden(id integer)",
                    "CREATE SCHEMA forbidden",
                    "DROP TABLE cane.products CASCADE",
                    "SET session_replication_role=replica",
                    "SET ROLE cane_migrator",
                    "DELETE FROM cane.payments",
                    "UPDATE cane.flyway_schema_history SET checksum=0",
                    "DELETE FROM cane.flyway_schema_history",
                    "DELETE FROM cane.business_events",
                    "UPDATE cane.order_items SET quantity=2",
                    "UPDATE cane.stock_movements SET delta=2",
                    "UPDATE cane.business_events SET operation_result='{}'::jsonb")) {
                rejected(runtime, sql, "42501");
            }
        }
        for (String sql : List.of(
                "UPDATE cane.staff_users SET password_hash='changed'",
                "UPDATE cane.staff_users SET role='owner'",
                "UPDATE cane.staff_users SET is_active=false",
                "UPDATE cane.staff_users SET auth_version=2",
                "INSERT INTO cane.staff_users(username,password_hash,role) VALUES ('new','not-a-password','owner')",
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope) VALUES ('NEW','Test','unit',1,'city')",
                "UPDATE cane.orders SET subtotal_vnd=1",
                "UPDATE cane.orders SET recipient_email='changed@example.invalid'",
                "UPDATE cane.payments SET amount_vnd=1",
                "UPDATE cane.notification_outbox SET status='skipped'",
                "UPDATE cane.staff_sessions SET expires_at=now()")) {
            rejected(api(), sql, "42501");
        }
        for (String sql : List.of(
                "SELECT password_hash FROM cane.staff_users",
                "SELECT token_hash FROM cane.staff_sessions",
                "UPDATE cane.products SET stock_qty=1",
                "UPDATE cane.settings SET value='null'::jsonb",
                "UPDATE cane.orders SET status='cancelled'",
                "UPDATE cane.payments SET status='refunded'",
                "UPDATE cane.notification_outbox SET recipient='changed@example.invalid'")) {
            rejected(worker(), sql, "42501");
        }
    }

    @Test
    void allowsApiWritesWorkerLeasesLocksAndSessionCleanupWithTriggersEnabled() throws Exception {
        applyGrants();
        JdbcTemplate api = api();
        api.update(
                "UPDATE cane.staff_users SET failed_count=1, locked_until=now()+interval '15 minutes' WHERE id=?::uuid",
                STAFF);
        api.update(
                "INSERT INTO cane.staff_sessions(token_hash,staff_id,auth_version) VALUES (decode(repeat('01',32),'hex'),?::uuid,1)",
                STAFF);
        api.update(
                "UPDATE cane.products SET stock_qty=2, price_vnd=25001, updated_by=?::uuid WHERE sku='TEST-A'", STAFF);
        api.update(
                "UPDATE cane.settings SET value='false'::jsonb, updated_by=?::uuid WHERE key='accepting_orders'",
                STAFF);
        api.update(
                "UPDATE cane.orders SET cancel_requested_at=now(), cancel_request_note='test' WHERE id=?::uuid",
                ORDER_A);
        String thirdOrder = insertOrder(api, 3);
        api.update(
                "INSERT INTO cane.order_items(order_id,sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,delivery_scope_snapshot) VALUES (?::uuid,'TEST-A','Test','unit',25000,1,'city')",
                thirdOrder);
        UUID bank = receipt("bank", null, false, api);
        api.update(
                "UPDATE cane.payments SET order_id=?::uuid,order_link_verified=true,needs_review=false WHERE id=?",
                ORDER_A,
                bank);
        api.update(
                "UPDATE cane.payments SET status='refunded',refunded_at=now(),refund_reference='test-refund' WHERE id=?",
                bank);
        api.update("INSERT INTO cane.business_events(kind,actor) VALUES ('runtime_test',? )", STAFF);
        api.update(
                "INSERT INTO cane.stock_movements(sku,delta,reason,event_id,actor) VALUES ('TEST-A',1,'restock',?::uuid,?)",
                EVENT,
                STAFF);
        api.update(
                "INSERT INTO cane.notification_outbox(kind,order_id,event_id,channel,recipient) VALUES ('order_placed',?::uuid,?::uuid,'email','test@example.invalid')",
                ORDER_A,
                EVENT);
        JdbcTemplate worker = worker();
        assertThat(worker.queryForList("SELECT id FROM cane.orders FOR UPDATE", UUID.class))
                .hasSize(3);
        assertThat(worker.queryForList("SELECT id FROM cane.payments FOR UPDATE", UUID.class))
                .hasSize(1);
        rejected(worker, "UPDATE cane.payments SET id=gen_random_uuid()", "55000");
        worker.update(
                "INSERT INTO cane.business_events(kind,actor,source_key) VALUES ('owner_alert','system','test:alert')");
        worker.update(
                "INSERT INTO cane.notification_outbox(kind,event_id,channel,recipient) SELECT 'owner_alert',id,'email','owner@example.invalid' FROM cane.business_events WHERE source_key='test:alert'");
        worker.update("UPDATE cane.notification_outbox SET status='processing',attempts=1,locked_at=now()");
        worker.update("UPDATE cane.notification_outbox SET status='sent',locked_at=null,sent_at=now()");
        api.update(
                "INSERT INTO cane.staff_sessions(token_hash,staff_id,auth_version,created_at,expires_at) VALUES (decode(repeat('02',32),'hex'),?::uuid,1,now()-interval '9 hours',now()-interval '1 hour')",
                STAFF);
        assertThat(worker.update("DELETE FROM cane.staff_sessions WHERE expires_at<=now()"))
                .isEqualTo(1);
        assertThat(api.update("DELETE FROM cane.staff_sessions WHERE token_hash=decode(repeat('01',32),'hex')"))
                .isEqualTo(1);
        assertThat(owner.queryForObject("SELECT bool_and(status='sent') FROM cane.notification_outbox", Boolean.class))
                .isTrue();
    }

    @Test
    void refusesUnsafeRolesBeforeChangingAnyExistingGrant() throws Exception {
        applyGrants();
        // Membership is cluster-wide: restore it even when an assertion fails.
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("GRANT cane_migrator TO cane_worker");
            try {
                ExecResult result = grants("cane_api", "cane_worker");
                assertThat(result.getExitCode()).isNotZero();
                assertThat(result.getStderr()).contains("isolated login roles");
                assertThat(privilege("cane_api", "orders", "INSERT")).isTrue();
            } finally {
                statement.execute("REVOKE cane_migrator FROM cane_worker");
            }
        }
        assertThat(grants("cane_api", "cane_api").getExitCode()).isNotZero();
        assertThat(grants("missing_runtime_role", "cane_worker").getExitCode()).isNotZero();
        assertThat(privilege("cane_api", "orders", "INSERT")).isTrue();
    }

    private void seedHistory() {
        owner.update(
                "INSERT INTO cane.staff_users(id,username,password_hash,role) VALUES (?::uuid,'test-owner','test-hash-only','owner')",
                STAFF);
        owner.update(
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope,updated_by) VALUES ('TEST-A','Test','unit',25000,'city',?::uuid)",
                STAFF);
        for (int n = 1; n <= 2; n++) {
            insertOrder(owner, n);
        }
        owner.update(
                "INSERT INTO cane.order_items(order_id,sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,delivery_scope_snapshot) VALUES (?::uuid,'TEST-A','Test','unit',25000,1,'city')",
                ORDER_A);
        owner.update("""
                INSERT INTO cane.business_events(id,kind,actor,operation_key,request_hash,operation_result)
                VALUES (?::uuid,'initial_stock',?,'test-operation-key',decode(repeat('01',32),'hex'),'{"ok":true}')
                """, EVENT, STAFF);
        owner.update(
                "INSERT INTO cane.stock_movements(sku,delta,reason,event_id,actor) VALUES ('TEST-A',1,'restock',?::uuid,?)",
                EVENT,
                STAFF);
    }

    private String insertOrder(JdbcTemplate writer, int number) {
        String id = number == 1
                ? ORDER_A
                : number == 2 ? ORDER_B : UUID.randomUUID().toString();
        writer.update("""
                INSERT INTO cane.orders(id,order_number,status,payment_method,fulfillment_method,
                    recipient_name,recipient_phone,recipient_email,address,subtotal_vnd,shipping_fee_vnd,
                    link_expires_at,idempotency_key,request_hash)
                VALUES (?::uuid,?,'confirmed','cod','delivery','Test','0900000000','test@example.invalid',
                    '{"wardCode":"TEST","line1":"Test"}',25000,15000,now()+interval '90 days',?,decode(repeat('01',32),'hex'))
                """, id, "CANETEST0" + number, "test-order-key-000" + number);
        return id;
    }

    private UUID receipt(String method, String orderId, boolean principal) {
        return receipt(method, orderId, principal, owner);
    }

    private UUID receipt(String method, String orderId, boolean principal, JdbcTemplate writer) {
        return writer.queryForObject(
                """
                INSERT INTO cane.payments(order_id,method,amount_vnd,provider_ref,bank_receipt,
                    order_link_verified,applied_to_order,confirmed_by)
                VALUES (?::uuid,?,40000,?,?::jsonb,?,?,'test-actor') RETURNING id
                """,
                UUID.class,
                orderId,
                method,
                method.equals("bank") ? UUID.randomUUID().toString() : null,
                method.equals("bank")
                        ? "{\"bankCode\":\"TEST\",\"accountNo\":\"TEST\",\"transferContent\":\"test\",\"transactionAt\":\"2026-11-10T00:00:00Z\"}"
                        : null,
                orderId != null,
                principal);
    }

    private void refund(UUID receipt) {
        owner.update(
                "UPDATE cane.payments SET status='refunded',refunded_at=now(),refund_reference='test-proof' WHERE id=?",
                receipt);
    }

    private void applyGrants() throws Exception {
        ExecResult result = grants("cane_api", "cane_worker");
        assertThat(result.getExitCode())
                .withFailMessage(result.getStdout() + result.getStderr())
                .isZero();
    }

    private ExecResult grants(String apiRole, String workerRole) throws Exception {
        return POSTGRES.execInContainer(
                "psql",
                "-X",
                "--no-password",
                "-U",
                "cane_migrator",
                "-d",
                database,
                "-v",
                "cane_api_role=" + apiRole,
                "-v",
                "cane_worker_role=" + workerRole,
                "-f",
                "/tmp/cane-runtime-grants.sql");
    }

    private JdbcTemplate api() {
        return jdbc("cane_api", TestInfrastructure.API_PASSWORD);
    }

    private JdbcTemplate worker() {
        return jdbc("cane_worker", TestInfrastructure.WORKER_PASSWORD);
    }

    private JdbcTemplate jdbc(String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, username, password));
    }

    private boolean privilege(String role, String table, String privileges) {
        return Boolean.TRUE.equals(owner.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)", Boolean.class, role, "cane." + table, privileges));
    }

    private boolean columnPrivilege(String role, String table, String column, String privilege) {
        return Boolean.TRUE.equals(owner.queryForObject(
                "SELECT has_column_privilege(?, ?, ?, ?)", Boolean.class, role, "cane." + table, column, privilege));
    }

    private String rows(String table) {
        return owner.queryForObject(
                "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM cane." + table + " t", String.class);
    }

    private static void rejected(JdbcTemplate jdbc, String sql, String state) {
        DataAccessException error = assertThrows(DataAccessException.class, () -> jdbc.execute(sql), sql);
        assertThat(error.getMostSpecificCause()).isInstanceOf(SQLException.class);
        assertThat(((SQLException) error.getMostSpecificCause()).getSQLState())
                .as(sql)
                .isEqualTo(state);
    }
}
