package vn.cane.admin.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.admin.application.ExpiredSessions;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcExpiredSessions implements ExpiredSessions {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExpiredSessions(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int deleteThrough(Instant now) {
        TransactionAccess.requireWrite();
        return jdbc.update(
                "DELETE FROM cane.staff_sessions WHERE expires_at<=:now",
                Map.of("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
    }
}
