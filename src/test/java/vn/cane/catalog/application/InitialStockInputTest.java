package vn.cane.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.infrastructure.cli.InitialStockManifestReader;

class InitialStockInputTest {
    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void normalizesReasonKeepsStableKeyAndRejectsInvalidDomain() {
        var actor = UUID.randomUUID();
        String key = "initial-stock-input-0001";
        var command = new InitialStockCommand(actor, "cane-tea", 1, "  Nhập tồn đầu  ", key);
        assertThat(command.reason()).isEqualTo("Nhập tồn đầu");
        assertThat(command.key()).isEqualTo(key);
        assertThat(command.toString()).doesNotContain(key, command.reason(), actor.toString());
        assertThatThrownBy(() -> new InitialStockCommand(actor, "UNKNOWN", 1, "reason", key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InitialStockCommand(actor, "cane-tea", -1, "reason", key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InitialStockCommand(actor, "cane-tea", 1, " ", key))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new InitialStockCommand(actor, "cane-tea", 1, "reason", "short"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void manifestIsStrictBoundedAndFixturesAreRestricted() throws Exception {
        String valid = json.writeValueAsString(json.createObjectNode()
                .put("dataKind", "fixture")
                .put("ownerId", UUID.randomUUID().toString())
                .put("sku", "cane-tea")
                .put("quantity", 0)
                .put("reason", "Fixture")
                .put("key", "initial-stock-input-0001"));
        var file = directory.resolve("initial.json");
        Files.writeString(file, valid);
        assertThat(InitialStockManifestReader.read(file, "test").quantity()).isZero();
        assertThatThrownBy(() -> InitialStockManifestReader.read(file, "staging"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String invalid : List.of(
                valid.replace("\"quantity\":0", "\"quantity\":\"0\""),
                valid.replace("\"quantity\":0", "\"quantity\":2147483648"),
                valid.replace("\"quantity\":0", "\"quantity\":1.5"),
                valid.replace("\"quantity\":0", "\"quantity\":null"),
                valid.replace("\"quantity\":0", "\"quantity\":0,\"quantity\":1"),
                valid.substring(0, valid.length() - 1) + ",\"unknown\":true}",
                valid + " {}",
                " ".repeat(65_537))) {
            Files.writeString(file, invalid);
            assertThatThrownBy(() -> InitialStockManifestReader.read(file, "test"))
                    .isInstanceOf(Exception.class);
        }
        Files.writeString(file, valid.replace("\"fixture\"", "\"release\""));
        assertThat(InitialStockManifestReader.read(file, "production").quantity())
                .isZero();
    }
}
