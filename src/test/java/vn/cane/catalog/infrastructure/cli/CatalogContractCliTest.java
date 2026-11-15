package vn.cane.catalog.infrastructure.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.domain.CatalogSkus;

class CatalogContractCliTest {
    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void exportedItemsComeFromCompiledSkusAndApprovedSeedTemplate() throws Exception {
        var output = directory.resolve("contract.json");
        CatalogContractCli.main(
                new String[] {"scripts/catalog/catalog-template.json", "a".repeat(40), output.toString()});
        var contract = json.readTree(Files.readString(output));
        assertThat(contract.get("backendRevision").asString()).isEqualTo("a".repeat(40));
        assertThat(contract.get("items")
                        .valueStream()
                        .map(item -> item.get("sku").asString())
                        .collect(Collectors.toSet()))
                .isEqualTo(CatalogSkus.ALL);
        assertThat(contract.get("items")).hasSize(9);
        assertThat(contract.toString()).doesNotContain("priceVnd", "stockQty");
        var template = json.readTree(Files.readString(Path.of("scripts/catalog/catalog-template.json")));
        ((tools.jackson.databind.node.ArrayNode) template.get("products")).remove(0);
        var broken = directory.resolve("broken.json");
        Files.writeString(broken, template.toString());
        assertThatThrownBy(() ->
                        CatalogContractCli.main(new String[] {broken.toString(), "a".repeat(40), output.toString()}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
