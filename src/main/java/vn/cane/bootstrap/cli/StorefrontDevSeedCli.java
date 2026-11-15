package vn.cane.bootstrap.cli;

import jakarta.validation.Validation;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.SettingsValidator;
import vn.cane.admin.infrastructure.JdbcSettingsLocks;
import vn.cane.admin.infrastructure.JdbcSettingsReader;
import vn.cane.catalog.application.PricesInvalidation;
import vn.cane.catalog.domain.CatalogSkus;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputNormalizer;

/** Fixture loader only. Never an operational settings/stock writer or a release import path. */
public final class StorefrontDevSeedCli {
    private StorefrontDevSeedCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), new PrintWriter(System.out, true)));
    }

    public static int run(String[] args, Map<String, String> environment, PrintWriter output) {
        try {
            if (args.length != 6
                    || !args[0].equals("--settings")
                    || !args[2].equals("--stock")
                    || !args[4].equals("--environment")
                    || !Set.of("local", "test").contains(args[5])
                    || !args[5].equals(environment.get("CANE_ENVIRONMENT")))
                throw new IllegalArgumentException("Explicit matching local/test environment required");
            var json = JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
            var settings = fixture(json, Path.of(args[1]), Set.of("dataKind", "values"));
            if (!settings.get("values").isObject()
                    || !settings.get("values").propertyNames().equals(SettingsValidator.KEYS))
                throw new IllegalArgumentException("All sixteen fixture settings required");
            var stock = fixture(json, Path.of(args[3]), Set.of("dataKind", "lowStockThreshold", "items"));
            var seen = new HashSet<String>();
            if (!stock.get("items").isArray()) throw new IllegalArgumentException("Fixture items required");
            for (var item : stock.get("items")) {
                if (!item.propertyNames().equals(Set.of("sku", "stockQty"))
                        || !item.get("sku").isString()
                        || !CatalogSkus.ALL.contains(item.get("sku").asString())
                        || !seen.add(item.get("sku").asString())
                        || !item.get("stockQty").isIntegralNumber()
                        || !item.get("stockQty").canConvertToInt()
                        || item.get("stockQty").asInt() < 0)
                    throw new IllegalArgumentException("Invalid fixture stock");
            }
            if (!seen.equals(CatalogSkus.ALL)) throw new IllegalArgumentException("All fixture SKUs required");
            if (!stock.get("lowStockThreshold").equals(settings.at("/values/low_stock_threshold")))
                throw new IllegalArgumentException("Fixture thresholds differ");
            try (var source = OperationalDatabase.open(environment);
                    var factory = Validation.buildDefaultValidatorFactory()) {
                OperationalDatabase.requireCurrentSchema(source);
                var jdbc = new NamedParameterJdbcTemplate(source);
                String apiRole = environment.get("CANE_API_DB_USER");
                String workerRole = environment.get("CANE_WORKER_DB_USER");
                if (apiRole == null
                        || workerRole == null
                        || !Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT current_user NOT IN (:api,:worker) AND session_user NOT IN (:api,:worker)",
                                Map.of("api", apiRole, "worker", workerRole),
                                Boolean.class)))
                    throw new IllegalArgumentException("Separate fixture credential required");
                var transactions = new Transactions(new JdbcTransactionManager(source));
                var invalidation = new PricesInvalidation(
                        transactions, () -> OperationalPricesInvalidation.evict(environment, args[5]));
                var validator =
                        new SettingsValidator(json, new InputNormalizer(factory.getValidator()), Clock.systemUTC());
                // Settings and product locks belong to separate transactions, as in production.
                transactions.write(() -> {
                    new JdbcSettingsLocks(jdbc).lockAll();
                    JsonNode current = json.valueToTree(new JdbcSettingsReader(jdbc, json).read());
                    var normalized = validator.validateAndMerge(current, settings.get("values"));
                    normalized
                            .properties()
                            .forEach(entry -> jdbc.update(
                                    "UPDATE cane.settings SET value=CAST(:value AS jsonb),updated_at=now() WHERE key=:key",
                                    Map.of(
                                            "key",
                                            entry.getKey(),
                                            "value",
                                            entry.getValue().toString())));
                    invalidation.afterCommit();
                    return null;
                });
                transactions.write(() -> {
                    var rows = jdbc.queryForList(
                            "SELECT sku FROM cane.products ORDER BY sku FOR UPDATE", Map.of(), String.class);
                    if (!Set.copyOf(rows).equals(CatalogSkus.ALL))
                        throw new IllegalStateException("Seed the nine fixture products first");
                    for (var item : stock.get("items"))
                        jdbc.update(
                                "UPDATE cane.products SET stock_qty=:quantity,updated_at=now() WHERE sku=:sku",
                                Map.of(
                                        "sku",
                                        item.get("sku").asString(),
                                        "quantity",
                                        item.get("stockQty").asInt()));
                    invalidation.afterCommit();
                    return null;
                });
            }
            output.println(
                    "MOCK local/test settings and stock loaded. Do not transfer money or use these fixtures for go-live.");
            output.flush();
            return 0;
        } catch (Exception failure) {
            output.println(
                    "MOCK setup failed. Review local/test fixture files, schema and separate operational access; no secrets are printed.");
            output.flush();
            return 1;
        }
    }

    private static JsonNode fixture(JsonMapper json, Path path, Set<String> fields) throws Exception {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(65537);
        }
        if (bytes.length > 65536) throw new IllegalArgumentException("Fixture exceeds size limit");
        var value = json.readTree(bytes);
        if (value == null
                || !value.isObject()
                || !value.propertyNames().equals(fields)
                || !value.get("dataKind").isString()
                || !value.get("dataKind").asString().equals("fixture"))
            throw new IllegalArgumentException("Explicit fixture document required");
        return value;
    }
}
