package vn.cane.catalog.infrastructure.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.application.InitialStockCommand;
import vn.cane.support.validation.InputNormalizer;

public final class InitialStockManifestReader {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> FIELDS = Set.of("dataKind", "ownerId", "sku", "quantity", "reason", "key");

    private InitialStockManifestReader() {}

    public static InitialStockCommand read(Path path, String environment) throws IOException {
        if (!Set.of("local", "test", "staging", "production").contains(environment))
            throw new IllegalArgumentException("Explicit environment is required");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(65_537);
        }
        if (bytes.length > 65_536) throw new IllegalArgumentException("Manifest exceeds 64 KiB");
        var root = JSON.readTree(bytes);
        if (root == null || !root.isObject() || !root.propertyNames().equals(FIELDS))
            throw new IllegalArgumentException("Missing or unknown initial-stock fields");
        for (String field : FIELDS)
            if (!field.equals("quantity") && !root.get(field).isString())
                throw new IllegalArgumentException("Initial-stock text fields must be explicit strings");
        String kind = root.get("dataKind").asString();
        if (!Set.of("fixture", "release").contains(kind)
                || (kind.equals("fixture") && !Set.of("local", "test").contains(environment)))
            throw new IllegalArgumentException("Fixture data is restricted to local/test");
        var quantity = root.get("quantity");
        if (!quantity.isIntegralNumber() || !quantity.canConvertToInt())
            throw new IllegalArgumentException("Initial quantity must fit an integer");
        return new InitialStockCommand(
                InputNormalizer.uuid(root.get("ownerId").asString(), "ownerId"),
                root.get("sku").asString(),
                quantity.asInt(),
                root.get("reason").asString(),
                root.get("key").asString());
    }
}
