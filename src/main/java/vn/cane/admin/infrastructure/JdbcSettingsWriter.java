package vn.cane.admin.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import vn.cane.admin.application.SettingsValidator;
import vn.cane.admin.application.SettingsWriter;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcSettingsWriter implements SettingsWriter {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSettingsWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void update(String key, JsonNode value, UUID actor) {
        TransactionAccess.requireWrite();
        if (!SettingsValidator.KEYS.contains(key)) throw new IllegalArgumentException("Unknown setting");
        if (jdbc.update(
                        "UPDATE cane.settings SET value=CAST(:value AS jsonb),updated_by=:actor WHERE key=:key",
                        Map.of("key", key, "value", value.toString(), "actor", actor))
                != 1) throw new IllegalStateException("Settings are incomplete");
    }

    public Instant serverTime() {
        return jdbc.queryForObject("SELECT transaction_timestamp()", Map.of(), OffsetDateTime.class)
                .toInstant();
    }
}
