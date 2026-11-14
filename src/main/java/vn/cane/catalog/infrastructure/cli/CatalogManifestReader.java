package vn.cane.catalog.infrastructure.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.application.CatalogSeedManifest;
import vn.cane.catalog.application.CatalogSeedManifest.ProductSeed;

/** A seed file is explicit operational input, never an automatic startup fixture. */
public final class CatalogManifestReader {
    private static final int MAX_BYTES = 65_536;
    private static final Set<String> ROW_FIELDS =
            Set.of("sku", "name", "unitLabel", "priceVnd", "isActive", "deliveryScope");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private CatalogManifestReader() {}

    public static CatalogSeedManifest read(Path path, String environment) throws IOException {
        if (!Set.of("local", "test", "staging", "production").contains(environment)) {
            throw new IllegalArgumentException("Select an explicit environment");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Manifest exceeds 64 KiB");
        JsonNode root = JSON.readTree(bytes);
        fields(root, Set.of("dataKind", "products"));
        String kind = text(root, "dataKind");
        if (!Set.of("fixture", "release").contains(kind))
            throw new IllegalArgumentException("Invalid manifest data kind");
        if (kind.equals("fixture") && !Set.of("local", "test").contains(environment)) {
            throw new IllegalArgumentException("Fixtures are restricted to local/test");
        }
        JsonNode rows = root.get("products");
        if (!rows.isArray() || rows.size() != 9)
            throw new IllegalArgumentException("Exactly nine products are required");
        var products = new ArrayList<ProductSeed>();
        for (JsonNode row : rows) {
            fields(row, ROW_FIELDS);
            var price = row.get("priceVnd");
            var active = row.get("isActive");
            if (!price.isIntegralNumber() || !price.canConvertToLong() || !active.isBoolean()) {
                throw new IllegalArgumentException("Price and active flag need explicit typed values");
            }
            products.add(new ProductSeed(
                    text(row, "sku"),
                    text(row, "name"),
                    text(row, "unitLabel"),
                    price.asLong(),
                    active.asBoolean(),
                    text(row, "deliveryScope")));
        }
        return new CatalogSeedManifest(products);
    }

    private static void fields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject() || !value.propertyNames().equals(expected)) {
            throw new IllegalArgumentException("Missing or unknown manifest fields");
        }
    }

    private static String text(JsonNode parent, String field) {
        var value = parent.get(field);
        if (!value.isString()) throw new IllegalArgumentException("Manifest text field is missing");
        return value.asString();
    }
}
