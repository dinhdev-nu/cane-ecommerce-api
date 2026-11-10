package vn.cane.bootstrap;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.cane.admin.application.SettingsValidator;

/** Read-only readiness; migration and grants remain separate operator commands. */
@Component
public final class DatabaseReadiness {
    private static final Set<String> TABLES = Set.of(
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
    private final DataSource dataSource;
    private final String mode;

    public DatabaseReadiness(DataSource dataSource, @Value("${cane.runtime.mode}") String mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    public Status inspect() {
        try (var connection = dataSource.getConnection()) {
            if (!correctConnectionSettings(connection)) return Status.CONFIGURATION_UNSAFE;
            if (!safeRole(connection)) return Status.ROLE_UNSAFE;
            if (!coreObjectsPresent(connection) || !requiredAccess(connection)) return Status.SCHEMA_UNAVAILABLE;
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            return state == null || state.startsWith("08") || state.startsWith("28")
                    ? Status.DATABASE_UNAVAILABLE
                    : Status.SCHEMA_UNAVAILABLE;
        }
        // Close our probe before Flyway borrows a connection. No migration callbacks or writes.
        try {
            var configuration = Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema("cane")
                    .schemas("cane")
                    .locations("classpath:db/migration/core")
                    .failOnMissingLocations(true)
                    .createSchemas(false)
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .cleanDisabled(true)
                    .skipDefaultCallbacks(true)
                    .ignoreMigrationPatterns(new String[0]);
            // Flyway's PostgreSQL transactional-lock mode borrows a second connection even for
            // validation. This read-only validator uses one connection; operator migrations keep
            // their separate, unchanged lock configuration.
            configuration
                    .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                    .setTransactionalLock(false);
            var result = configuration.load().validateWithResult();
            return result.validationSuccessful ? Status.READY : Status.SCHEMA_UNAVAILABLE;
        } catch (RuntimeException failure) {
            return Status.SCHEMA_UNAVAILABLE;
        }
    }

    private static boolean correctConnectionSettings(Connection connection) throws SQLException {
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) return false;
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                SELECT current_setting('search_path'), current_setting('statement_timeout'), current_setting('lock_timeout')
                """)) {
            return result.next()
                    && result.getString(1).replace(" ", "").equals("cane,pg_catalog")
                    && !result.getString(2).equals("0")
                    && !result.getString(3).equals("0");
        }
    }

    private static boolean safeRole(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                SELECT NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                  AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                  AND NOT pg_has_role(current_user,d.datdba,'MEMBER')
                  AND NOT has_database_privilege(current_user,current_database(),'CREATE,TEMP')
                  AND NOT EXISTS (SELECT 1 FROM pg_namespace n
                    WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
                      AND (n.nspowner=r.oid OR has_schema_privilege(current_user,n.oid,'CREATE')))
                  AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relowner=r.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                    WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND p.proowner=r.oid)
                FROM pg_roles r JOIN pg_database d ON d.datname=current_database() WHERE r.rolname=current_user
                """)) {
            return result.next() && result.getBoolean(1);
        }
    }

    private static boolean coreObjectsPresent(Connection connection) throws SQLException {
        var tables = new HashSet<String>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='cane' AND c.relkind='r'
                """)) {
            while (result.next()) tables.add(result.getString(1));
        }
        if (!tables.containsAll(TABLES) || !tables.contains("flyway_schema_history")) return false;
        var settings = new HashSet<String>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT key FROM cane.settings")) {
            while (result.next()) settings.add(result.getString(1));
        }
        return settings.equals(SettingsValidator.KEYS);
    }

    private boolean requiredAccess(Connection connection) throws SQLException {
        if (!tableAccess(connection, "flyway_schema_history", "SELECT")) return false;
        for (String table : TABLES) {
            if (mode.equals("worker") && Set.of("staff_users", "staff_sessions").contains(table)) continue;
            if (!tableAccess(connection, table, "SELECT")) return false;
        }
        List<String> inserted = mode.equals("api")
                ? List.of(
                        "staff_sessions",
                        "orders",
                        "order_items",
                        "payments",
                        "business_events",
                        "stock_movements",
                        "notification_outbox")
                : List.of("business_events", "notification_outbox");
        for (String table : inserted) if (!tableAccess(connection, table, "INSERT")) return false;
        if (!tableAccess(connection, "staff_sessions", "DELETE")) return false;
        // Column grants are deliberate. Full grant/audit matrix is enforced by Grant-Runtime.sql.
        String[][] updated = mode.equals("api")
                ? new String[][] {
                    {"staff_users", "failed_count"},
                    {"products", "stock_qty"},
                    {"settings", "value"},
                    {"orders", "status"},
                    {"payments", "status"}
                }
                : new String[][] {{"orders", "updated_at"}, {"payments", "id"}, {"notification_outbox", "status"}};
        for (String[] entry : updated) if (!columnAccess(connection, entry[0], entry[1], "UPDATE")) return false;
        return !mode.equals("worker") || columnAccess(connection, "staff_sessions", "expires_at", "SELECT");
    }

    private static boolean tableAccess(Connection connection, String table, String privilege) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT has_table_privilege(current_user, ?, ?)")) {
            statement.setString(1, "cane." + table);
            statement.setString(2, privilege);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static boolean columnAccess(Connection connection, String table, String column, String privilege)
            throws SQLException {
        try (var statement = connection.prepareStatement("SELECT has_column_privilege(current_user, ?, ?, ?)")) {
            statement.setString(1, "cane." + table);
            statement.setString(2, column);
            statement.setString(3, privilege);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    public enum Status {
        READY,
        CONFIGURATION_UNSAFE,
        DATABASE_UNAVAILABLE,
        ROLE_UNSAFE,
        SCHEMA_UNAVAILABLE
    }
}
