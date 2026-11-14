package vn.cane.catalog.infrastructure.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.domain.CatalogSkus;

class CatalogManifestReaderTest {
    static final Path FIXTURE = Path.of("src/test/resources/catalog/nine-skus-fixture.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path directory;

    @Test
    void acceptsTheApprovedSetAndNormalizesCreationText() throws Exception {
        String fixture = Files.readString(FIXTURE).replace("fixture unit", "  fixture unit  ");
        var manifest = CatalogManifestReader.read(write(fixture), "test");
        assertThat(manifest.products()).hasSize(9);
        assertThat(manifest.products())
                .extracting(row -> row.sku())
                .containsExactlyInAnyOrderElementsOf(CatalogSkus.ALL);
        assertThat(manifest.products())
                .allSatisfy(row -> assertThat(row.unitLabel()).isEqualTo("fixture unit"));
        assertThatThrownBy(() -> manifest.products().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "production", "unknown"})
    void refusesFixturesOutsideLocalAndTest(String environment) {
        assertThatThrownBy(() -> CatalogManifestReader.read(FIXTURE, environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionTemplateCannotBeUsedUntilRealDataIsFilled() {
        assertThatThrownBy(() ->
                        CatalogManifestReader.read(Path.of("scripts/catalog/catalog-template.json"), "production"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void rejectsMalformedIncompleteDuplicateOrUnsafeData(String document) throws Exception {
        assertThatThrownBy(() -> CatalogManifestReader.read(write(document), "test"))
                .isInstanceOf(RuntimeException.class);
    }

    static Stream<String> invalidDocuments() throws Exception {
        String valid = Files.readString(FIXTURE);
        var fewer = JSON.readTree(valid).deepCopy();
        ((tools.jackson.databind.node.ArrayNode) fewer.get("products")).remove(0);
        return Stream.of(
                valid.replace("\"dataKind\": \"fixture\"", "\"dataKind\": \"fixture\", \"dataKind\": \"release\""),
                valid + " {}",
                valid.replace("\"fixture\"", "\"other\""),
                fewer.toString(),
                valid.replace("\"sku\": \"cane-juice-bottle\"", "\"sku\": \"cane-tea\""),
                valid.replace("\"sku\": \"cane-tea\"", "\"sku\": \"CANE-TEA\""),
                valid.replace("\"priceVnd\": 25000", "\"priceVnd\": 25000.5"),
                valid.replace("\"priceVnd\": 25000", "\"priceVnd\": \"25000\""),
                valid.replace("\"priceVnd\": 25000", "\"priceVnd\": 0"),
                valid.replace("\"priceVnd\": 25000", "\"priceVnd\": 9007199254740992"),
                valid.replace("\"isActive\": true", "\"isActive\": null"),
                valid.replace("\"isActive\": true", "\"isActive\": true, \"stockQty\": 10"),
                valid.replace("\"near\"", "\"city\""),
                valid.replace("fixture unit", " "),
                valid.replace("fixture unit", "x".repeat(101)),
                " ".repeat(65_537));
    }

    private Path write(String document) throws Exception {
        Path path = directory.resolve("manifest.json");
        Files.writeString(path, document);
        return path;
    }
}
