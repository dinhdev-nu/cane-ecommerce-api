package vn.cane.admin.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.SettingsReader;

@Repository
public class JdbcSettingsReader implements SettingsReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcSettingsReader(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Map<String, JsonNode> read() {
        return jdbc.query("SELECT key, value::text FROM cane.settings", Map.of(), rows -> {
            var values = new LinkedHashMap<String, JsonNode>();
            while (rows.next()) values.put(rows.getString(1), json.readTree(rows.getString(2)));
            return Map.copyOf(values);
        });
    }
}
