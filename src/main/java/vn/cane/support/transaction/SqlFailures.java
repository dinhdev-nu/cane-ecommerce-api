package vn.cane.support.transaction;

import java.sql.SQLException;
import java.util.Optional;
import org.postgresql.util.PSQLException;

/** Structured SQLSTATE/constraint inspection; never parse or expose driver messages. */
public final class SqlFailures {
    private SqlFailures() {}

    public static boolean isDeadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    public static Optional<String> uniqueConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sql && "23505".equals(sql.getSQLState())) {
                var details = sql.getServerErrorMessage();
                return details == null ? Optional.empty() : Optional.ofNullable(details.getConstraint());
            }
        }
        return Optional.empty();
    }
}
