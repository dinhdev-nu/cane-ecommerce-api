package vn.cane.bootstrap.cli;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

/** One-shot operations use a separate credential; API/worker grants remain unchanged. */
public final class OperationalDatabase {
    private OperationalDatabase() {}

    public static HikariDataSource open(Map<String, String> environment) {
        String url = configured(environment, "CANE_OPERATIONS_DB_URL");
        String user = configured(environment, "CANE_OPERATIONS_DB_USER");
        String password = configured(environment, "CANE_OPERATIONS_DB_PASSWORD");
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("PostgreSQL JDBC is required");
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(1);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(3000);
        dataSource.setValidationTimeout(1000);
        dataSource.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        dataSource.addDataSourceProperty(
                "options", "-c search_path=cane,pg_catalog -c statement_timeout=5000 -c lock_timeout=2000");
        dataSource.addDataSourceProperty("connectTimeout", "3");
        dataSource.addDataSourceProperty("socketTimeout", "6");
        dataSource.addDataSourceProperty("cancelSignalTimeout", "1");
        return dataSource;
    }

    public static void requireCurrentSchema(HikariDataSource dataSource) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("cane")
                .schemas("cane")
                .locations("classpath:db/migration/core")
                .failOnMissingLocations(true)
                .createSchemas(false)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .ignoreMigrationPatterns(new String[0])
                .skipDefaultCallbacks(true)
                .cleanDisabled(true);
        configuration
                .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
        var result = configuration.load().validateWithResult();
        if (!result.validationSuccessful) throw new IllegalStateException("Core schema validation failed");
    }

    private static String configured(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank() || value.contains("<") || value.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("Operational database configuration is missing");
        }
        return value;
    }
}
