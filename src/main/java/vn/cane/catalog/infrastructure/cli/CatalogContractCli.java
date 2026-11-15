package vn.cane.catalog.infrastructure.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.domain.CatalogSkus;

/** Exports the compiled SKU source and approved seed metadata without a database or Boot startup. */
public final class CatalogContractCli {
    private CatalogContractCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[1].matches("[a-f0-9]{40}"))
            throw new IllegalArgumentException("Template, revision and output required");
        var json = JsonMapper.builder().build();
        var template = json.readTree(Files.readString(Path.of(args[0])));
        var items = json.createArrayNode();
        var seen = new HashSet<String>();
        for (var product : template.get("products")) {
            String sku = product.get("sku").asString();
            if (!CatalogSkus.ALL.contains(sku) || !seen.add(sku))
                throw new IllegalArgumentException("Invalid seed SKU");
            var name = product.get("name");
            var unit = product.get("unitLabel");
            if (!name.isString()
                    || name.asString().isBlank()
                    || !unit.isString()
                    || unit.asString().isBlank()) throw new IllegalArgumentException("Approved metadata required");
            items.addObject().put("sku", sku).put("name", name.asString()).put("unitLabel", unit.asString());
        }
        if (!Set.copyOf(seen).equals(CatalogSkus.ALL)) throw new IllegalArgumentException("Missing seed SKU");
        var result = json.createObjectNode().put("schemaVersion", 1).put("backendRevision", args[1]);
        result.set("items", items);
        Files.writeString(
                Path.of(args[2]), json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
    }
}
