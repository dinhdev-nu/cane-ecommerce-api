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
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.StaffCommandService;
import vn.cane.admin.infrastructure.JdbcStaffCommandRepository;
import vn.cane.bootstrap.PasswordConfiguration;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.bootstrap.cli.OperationalDatabase;
import vn.cane.catalog.application.InitialStockService;
import vn.cane.catalog.infrastructure.JdbcInitialStockRepository;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.OperationReceipts;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;

/** One-shot operational process; never starts HTTP, Redis or worker jobs. */
public final class InitialStockCli {
    private InitialStockCli() {}

    public static void main(String[] arguments) {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.OFF);
        System.exit(run(arguments, System.getenv(), new PrintWriter(System.out, true)));
    }

    public static int run(String[] arguments, Map<String, String> environment, PrintWriter output) {
        try {
            if (arguments.length != 4 || !arguments[0].equals("--manifest") || !arguments[2].equals("--environment"))
                throw new IllegalArgumentException("Manifest and environment are required");
            var command = InitialStockManifestReader.read(Path.of(arguments[1]), arguments[3]);
            try (var source = OperationalDatabase.open(environment);
                    var context = new AnnotationConfigApplicationContext()) {
                OperationalDatabase.requireCurrentSchema(source);
                if (!Boolean.TRUE.equals(new JdbcTemplate(source).queryForObject("""
                        SELECT current_user NOT IN ('cane_api','cane_worker')
                          AND has_table_privilege(current_user,'cane.staff_users','SELECT')
                          AND has_table_privilege(current_user,'cane.products','SELECT')
                          AND has_column_privilege(current_user,'cane.products','stock_qty','UPDATE')
                          AND has_column_privilege(current_user,'cane.products','updated_by','UPDATE')
                          AND has_column_privilege(current_user,'cane.products','updated_at','UPDATE')
                          AND has_table_privilege(current_user,'cane.business_events','SELECT')
                          AND has_table_privilege(current_user,'cane.business_events','INSERT')
                          AND has_table_privilege(current_user,'cane.stock_movements','INSERT')
                        """, Boolean.class)))
                    throw new IllegalStateException("Operational permissions are required");
                context.registerBean(DataSource.class, () -> source);
                context.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(source));
                context.registerBean(
                        JsonMapper.class, () -> JsonMapper.builder().build());
                context.register(
                        TransactionConfiguration.class,
                        PasswordConfiguration.class,
                        Transactions.class,
                        AdvisoryLocks.class,
                        StaffCommandService.class,
                        JdbcStaffCommandRepository.class,
                        InitialStockService.class,
                        JdbcInitialStockRepository.class,
                        BusinessEventWriter.class,
                        OperationReceipts.class,
                        IdempotentOperations.class);
                context.refresh();
                var result = context.getBean(InitialStockService.class).initialize(command);
                output.printf(
                        "Initial stock complete: operationId=%s replayed=%s sku=%s stockAfter=%d.%n",
                        result.operationId(),
                        result.replayed(),
                        result.result().path("sku").asString(),
                        result.result().path("stockAfter").asInt());
                output.flush();
                return 0;
            }
        } catch (Exception failure) {
            output.println(
                    "Initial stock failed or commit outcome is unknown. Check inputs, schema, active Owner and operational access. Retry with exactly the same Owner, key and normalized business data; never generate a replacement key for a timeout.");
            output.flush();
            return 1;
        }
    }
}
