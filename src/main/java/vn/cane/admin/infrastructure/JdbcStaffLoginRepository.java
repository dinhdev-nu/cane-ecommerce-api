package vn.cane.admin.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.admin.application.StaffLoginRepository;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.TransactionAccess;

@Repository
@Profile("api & !worker")
public class JdbcStaffLoginRepository implements StaffLoginRepository {
    private static final String COLUMNS =
            "id,username,password_hash,role,is_active,auth_version,failed_count,locked_until";
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStaffLoginRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Credentials> findByUsername(String username) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM cane.staff_users WHERE lower(username)=:username",
                        Map.of("username", username),
                        JdbcStaffLoginRepository::credentials)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Credentials> lock(UUID staffId) {
        TransactionAccess.requireWrite();
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM cane.staff_users WHERE id=:id FOR UPDATE",
                        Map.of("id", staffId),
                        JdbcStaffLoginRepository::credentials)
                .stream()
                .findFirst();
    }

    @Override
    public Instant currentTime() {
        TransactionAccess.requireWrite();
        // Read after acquiring the staff lock, including any time spent waiting for it.
        return jdbc.query(
                        "SELECT clock_timestamp() AS checked_at",
                        Map.of(),
                        (row, number) -> row.getObject("checked_at", OffsetDateTime.class)
                                .toInstant())
                .getFirst();
    }

    @Override
    public void recordAttempt(UUID staffId, int failedCount, Instant lockedUntil) {
        TransactionAccess.requireWrite();
        var parameters = new MapSqlParameterSource()
                .addValue("id", staffId)
                .addValue("count", failedCount)
                .addValue(
                        "until",
                        lockedUntil == null ? null : lockedUntil.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE);
        if (jdbc.update("UPDATE cane.staff_users SET failed_count=:count,locked_until=:until WHERE id=:id", parameters)
                != 1) {
            throw new IllegalStateException("Locked staff row disappeared");
        }
    }

    @Override
    public boolean operatorCanConfirmBank() {
        var values = jdbc.query(
                "SELECT jsonb_typeof(value) AS type,value #>> '{}' AS flag FROM cane.settings WHERE key='operator_can_confirm_bank'",
                Map.of(),
                (row, number) -> {
                    if (!"boolean".equals(row.getString("type")))
                        throw new IllegalStateException("Current bank permission setting is invalid");
                    return Boolean.parseBoolean(row.getString("flag"));
                });
        if (values.size() != 1) throw new IllegalStateException("Current bank permission setting is missing");
        return values.getFirst();
    }

    private static Credentials credentials(ResultSet row, int number) throws SQLException {
        var lockedUntil = row.getObject("locked_until", OffsetDateTime.class);
        return new Credentials(
                row.getObject("id", UUID.class),
                row.getString("username"),
                row.getString("password_hash"),
                StaffRole.parse(row.getString("role")),
                row.getBoolean("is_active"),
                row.getInt("auth_version"),
                row.getInt("failed_count"),
                lockedUntil == null ? null : lockedUntil.toInstant());
    }
}
