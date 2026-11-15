package vn.cane.admin.application;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** A single database statement supplies the settings used by one operation. */
public interface SettingsReader {
    Map<String, JsonNode> read();
}
