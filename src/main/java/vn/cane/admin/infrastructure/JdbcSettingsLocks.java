package vn.cane.admin.infrastructure;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.admin.application.SettingsLocks;
import vn.cane.admin.application.SettingsValidator;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcSettingsLocks implements SettingsLocks {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSettingsLocks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> lockAll() {
        TransactionAccess.requireWrite();
        var keys = jdbc.queryForList(
                "SELECT key FROM cane.settings WHERE key IN (:keys) ORDER BY key FOR UPDATE",
                Map.of("keys", SettingsValidator.KEYS),
                String.class);
        if (keys.size() != SettingsValidator.KEYS.size()) {
            throw new DataIntegrityViolationException("Core settings are incomplete");
        }
        return List.copyOf(keys);
    }
}
