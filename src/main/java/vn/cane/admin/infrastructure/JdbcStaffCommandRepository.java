package vn.cane.admin.infrastructure;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.admin.application.StaffCommandRepository;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcStaffCommandRepository implements StaffCommandRepository {
    private static final RowMapper<Staff> STAFF = (row, number) -> new Staff(
            row.getObject("id", UUID.class), StaffRole.parse(row.getString("role")),
            row.getBoolean("is_active"), row.getInt("auth_version"));
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStaffCommandRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Staff> lockByUsername(String username) {
        TransactionAccess.requireWrite();
        return jdbc.query("""
                SELECT id,role,is_active,auth_version FROM cane.staff_users
                WHERE lower(username)=lower(:username) FOR UPDATE
                """, Map.of("username", username), STAFF).stream().findFirst();
    }

    @Override
    public Staff insert(String username, String passwordHash, StaffRole role) {
        TransactionAccess.requireWrite();
        return jdbc.queryForObject(
                """
                INSERT INTO cane.staff_users(username,password_hash,role,is_active,auth_version)
                VALUES (:username,:passwordHash,:role,true,1)
                RETURNING id,role,is_active,auth_version
                """, Map.of("username", username, "passwordHash", passwordHash, "role", role.databaseValue()), STAFF);
    }

    @Override
    public long countActiveOwners() {
        TransactionAccess.requireWrite();
        return jdbc.queryForObject(
                "SELECT count(*) FROM cane.staff_users WHERE role='owner' AND is_active", Map.of(), Long.class);
    }

    @Override
    public void changePassword(UUID staffId, String passwordHash) {
        TransactionAccess.requireWrite();
        requireOne(jdbc.update("""
                UPDATE cane.staff_users SET password_hash=:passwordHash,auth_version=auth_version+1 WHERE id=:id
                """, Map.of("id", staffId, "passwordHash", passwordHash)));
    }

    @Override
    public void changeAccess(UUID staffId, StaffRole role, boolean active) {
        TransactionAccess.requireWrite();
        requireOne(jdbc.update("""
                UPDATE cane.staff_users SET role=:role,is_active=:active,auth_version=auth_version+1 WHERE id=:id
                """, Map.of("id", staffId, "role", role.databaseValue(), "active", active)));
    }

    @Override
    public void revokeSessions(UUID staffId) {
        TransactionAccess.requireWrite();
        jdbc.update("DELETE FROM cane.staff_sessions WHERE staff_id=:id", Map.of("id", staffId));
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("Staff update did not affect exactly one account");
    }
}
