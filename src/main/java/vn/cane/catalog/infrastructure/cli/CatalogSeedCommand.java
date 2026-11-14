package vn.cane.catalog.infrastructure.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.bootstrap.cli.OperationalDatabase;
import vn.cane.catalog.application.CatalogSeedService;
import vn.cane.catalog.infrastructure.JdbcCatalogSeedRepository;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;

/** Explicit entry point: no Spring Boot startup, HTTP, Redis, scheduler or component scan. */
public final class CatalogSeedCommand {
    private CatalogSeedCommand() {}

    public static void main(String[] arguments) {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.OFF);
        System.exit(run(arguments, System.getenv(), new PrintWriter(System.out, true)));
    }

    public static int run(String[] arguments, Map<String, String> environment, PrintWriter output) {
        try {
            if (arguments.length != 4 || !arguments[0].equals("--manifest") || !arguments[2].equals("--environment")) {
                throw new IllegalArgumentException("Manifest and environment are required");
            }
            var manifest = CatalogManifestReader.read(Path.of(arguments[1]), arguments[3]);
            try (var dataSource = OperationalDatabase.open(environment);
                    var context = new AnnotationConfigApplicationContext()) {
                OperationalDatabase.requireCurrentSchema(dataSource);
                Boolean allowed = new JdbcTemplate(dataSource).queryForObject("""
                        SELECT has_table_privilege(current_user,'cane.products','SELECT')
                          AND has_table_privilege(current_user,'cane.products','INSERT')
                          AND has_any_column_privilege(current_user,'cane.products','UPDATE')
                        """, Boolean.class);
                if (!Boolean.TRUE.equals(allowed))
                    throw new IllegalStateException("Use a separate operational credential");
                context.registerBean(DataSource.class, () -> dataSource);
                context.registerBean(
                        NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource));
                context.register(
                        TransactionConfiguration.class,
                        Transactions.class,
                        AdvisoryLocks.class,
                        CatalogSeedService.class,
                        JdbcCatalogSeedRepository.class);
                context.refresh();
                var result = context.getBean(CatalogSeedService.class).seed(manifest);
                output.printf(
                        "Catalog seed complete: inserted=%d preserved=%d stock initialized only for new rows.%n",
                        result.inserted(), result.preserved());
                output.flush();
                return 0;
            }
        } catch (Exception failure) {
            output.println(
                    "Catalog seed failed. Check manifest, target database, schema and operational permissions; existing data was not overwritten.");
            output.flush();
            return 1;
        }
    }
}
