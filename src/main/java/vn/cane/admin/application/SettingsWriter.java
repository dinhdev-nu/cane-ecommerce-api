package vn.cane.admin.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface SettingsWriter {
    void update(String key, JsonNode value, UUID actor);

    Instant serverTime();
}
