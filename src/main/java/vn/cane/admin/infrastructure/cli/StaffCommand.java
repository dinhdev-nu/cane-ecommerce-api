package vn.cane.admin.infrastructure.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.cane.admin.application.StaffCommandService;
import vn.cane.admin.domain.StaffRole;
import vn.cane.admin.infrastructure.JdbcStaffCommandRepository;
import vn.cane.bootstrap.PasswordConfiguration;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.bootstrap.cli.OperationalDatabase;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputNormalizer;

/** Explicit staff operations only; never starts HTTP, worker jobs or Redis. */
public final class StaffCommand {
    private StaffCommand() {}

    public static void main(String[] arguments) {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.OFF);
        System.exit(run(arguments, System.getenv(), new ConsolePasswordPrompt(), new PrintWriter(System.out, true)));
    }

    public static int run(
            String[] arguments, Map<String, String> environment, PasswordPrompt prompt, PrintWriter output) {
        char[] password = null;
        try {
            var command = Arguments.parse(arguments);
            try (var dataSource = OperationalDatabase.open(environment);
                    var context = new AnnotationConfigApplicationContext()) {
                OperationalDatabase.requireCurrentSchema(dataSource);
                Boolean allowed = new JdbcTemplate(dataSource).queryForObject("""
                        SELECT has_table_privilege(current_user,'cane.staff_users','SELECT')
                          AND has_table_privilege(current_user,'cane.staff_users','INSERT')
                          AND has_table_privilege(current_user,'cane.staff_users','UPDATE')
                          AND has_table_privilege(current_user,'cane.staff_sessions','DELETE')
                        """, Boolean.class);
                if (!Boolean.TRUE.equals(allowed)) throw new IllegalStateException("Use an operational credential");
                context.registerBean(DataSource.class, () -> dataSource);
                context.registerBean(
                        NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource));
                context.register(
                        TransactionConfiguration.class,
                        PasswordConfiguration.class,
                        Transactions.class,
                        AdvisoryLocks.class,
                        StaffCommandService.class,
                        JdbcStaffCommandRepository.class);
                context.refresh();
                var service = context.getBean(StaffCommandService.class);
                StaffCommandService.Result result;
                if (Set.of("create-owner", "create-operator", "reset-password").contains(command.action())) {
                    password = prompt.readConfirmedPassword();
                    InputNormalizer.validateCreationPassword(password == null ? null : CharBuffer.wrap(password));
                    var raw = CharBuffer.wrap(password);
                    result = switch (command.action()) {
                        case "create-owner" -> service.create(command.username(), raw, StaffRole.OWNER);
                        case "create-operator" -> service.create(command.username(), raw, StaffRole.OPERATOR);
                        default -> service.resetPassword(command.username(), raw);
                    };
                } else if (command.action().equals("set-role")) {
                    result = service.changeRole(command.username(), command.role());
                } else {
                    result = service.changeActive(command.username(), command.active());
                }
                output.printf(
                        "Staff command complete: staffId=%s role=%s active=%s authVersion=%d changed=%s.%n",
                        result.staffId(),
                        result.role().databaseValue(),
                        result.active(),
                        result.authVersion(),
                        result.changed());
                output.flush();
                return 0;
            }
        } catch (Exception failure) {
            output.println(
                    "Staff command failed. Check arguments, interactive password confirmation, target database, schema, operational permissions and the last active Owner. Retry only after review.");
            output.flush();
            return 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    @FunctionalInterface
    public interface PasswordPrompt {
        /** The caller clears the returned buffer after use. */
        char[] readConfirmedPassword();
    }

    private record Arguments(String action, String username, StaffRole role, Boolean active) {
        static Arguments parse(String[] args) {
            if (args.length < 4 || !args[0].equals("--action") || !args[2].equals("--username")) {
                throw new IllegalArgumentException("Action and username are required");
            }
            String action = args[1];
            String username = InputNormalizer.username(args[3]);
            return switch (action) {
                case "create-owner", "create-operator", "reset-password" -> {
                    if (args.length != 4) throw new IllegalArgumentException("Unexpected command arguments");
                    yield new Arguments(action, username, null, null);
                }
                case "set-role" -> {
                    if (args.length != 6 || !args[4].equals("--role"))
                        throw new IllegalArgumentException("Role is required");
                    yield new Arguments(action, username, StaffRole.parse(args[5]), null);
                }
                case "set-active" -> {
                    if (args.length != 6
                            || !args[4].equals("--active")
                            || !Set.of("true", "false").contains(args[5])) {
                        throw new IllegalArgumentException("Explicit active flag is required");
                    }
                    yield new Arguments(action, username, null, Boolean.valueOf(args[5]));
                }
                default -> throw new IllegalArgumentException("Unknown staff command");
            };
        }
    }
}
