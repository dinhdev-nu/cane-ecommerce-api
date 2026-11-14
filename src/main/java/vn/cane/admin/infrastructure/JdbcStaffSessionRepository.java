package vn.cane.admin.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.admin.application.StaffSessionRepository;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.TransactionAccess;

@Repository
@Profile("api & !worker")
public class JdbcStaffSessionRepository implements StaffSessionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStaffSessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Snapshot> find(byte[] tokenHash) {
        return jdbc.query("""
                SELECT u.id,u.username,u.role,u.is_active,u.auth_version AS staff_version,
                       s.auth_version AS session_version,s.expires_at,statement_timestamp() AS checked_at,
                       jsonb_typeof(setting.value) AS setting_type,setting.value #>> '{}' AS bank_permission
                FROM cane.staff_sessions s JOIN cane.staff_users u ON u.id=s.staff_id
                LEFT JOIN cane.settings setting ON setting.key='operator_can_confirm_bank'
                WHERE s.token_hash=:hash
                """, Map.of("hash", tokenHash), JdbcStaffSessionRepository::snapshot).stream()
                .findFirst();
    }

    @Override
    public Lifetime insert(byte[] tokenHash, UUID staffId, int authVersion) {
        TransactionAccess.requireWrite();
        var rows = jdbc.query(
                """
                INSERT INTO cane.staff_sessions(token_hash,staff_id,auth_version,created_at,expires_at)
                SELECT :hash,u.id,u.auth_version,statement_timestamp(),statement_timestamp()+interval '8 hours'
                FROM cane.staff_users u WHERE u.id=:id AND u.is_active AND u.auth_version=:version
                FOR UPDATE OF u
                RETURNING created_at,expires_at
                """,
                Map.of("hash", tokenHash, "id", staffId, "version", authVersion),
                (row, number) -> new Lifetime(
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()));
        if (rows.size() != 1) throw new IllegalStateException("Verified staff changed before session creation");
        return rows.getFirst();
    }

    @Override
    public void delete(byte[] tokenHash) {
        TransactionAccess.requireWrite();
        jdbc.update("DELETE FROM cane.staff_sessions WHERE token_hash=:hash", Map.of("hash", tokenHash));
    }

    private static Snapshot snapshot(ResultSet row, int number) throws SQLException {
        if (!"boolean".equals(row.getString("setting_type")))
            throw new IllegalStateException("Current bank permission setting is invalid");
        return new Snapshot(
                row.getObject("id", UUID.class),
                row.getString("username"),
                StaffRole.parse(row.getString("role")),
                row.getBoolean("is_active"),
                row.getInt("staff_version"),
                row.getInt("session_version"),
                Boolean.parseBoolean(row.getString("bank_permission")),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("checked_at", OffsetDateTime.class).toInstant());
    }
}
