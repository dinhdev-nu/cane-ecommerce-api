package vn.cane.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CanonicalOperationTest {
    private static final String ROUTE = "/api/v1/admin/stock-adjustments";
    private static final String KEY = "fixture-operation-0001";
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void recursivelyOrderedUtf8JsonHasAStableHashIndependentOfKeyOrderAndIdempotencyKey() {
        var first =
                post(ROUTE, "{\"expected\":\"preparing\",\"nested\":{\"b\":null,\"a\":\"mía\"},\"rotate\":false}", KEY);
        var second = post(
                ROUTE,
                "{\"rotate\":false,\"nested\":{\"a\":\"mía\",\"b\":null},\"expected\":\"preparing\"}",
                "fixture-operation-0002");
        // Independent Python hashlib/json UTF-8 + big-endian length framing reference.
        assertThat(HexFormat.of().formatHex(first.hash()))
                .isEqualTo("92abe5e44c3acc225653979da1ce4da69f49b196f60a88e826cbc3da0ca2e1fd");
        assertThat(second.hash()).isEqualTo(first.hash());
        assertThat(CanonicalOperation.adminPost("post", ROUTE, json.readTree("{}"), KEY)
                        .hash())
                .isEqualTo(post(ROUTE, "{}", KEY).hash());
    }

    @Test
    void everyExpectedKindRotateAndNestedBodyFieldParticipates() {
        var first = post(
                ROUTE,
                "{\"expected\":\"preparing\",\"kind\":\"adjust\",\"rotate\":false,\"items\":[{\"sku\":\"A\",\"quantity\":0}]}",
                KEY);
        for (String changed : List.of(
                "{\"expected\":\"shipping\",\"kind\":\"adjust\",\"rotate\":false,\"items\":[{\"sku\":\"A\",\"quantity\":0}]}",
                "{\"expected\":\"preparing\",\"kind\":\"restock\",\"rotate\":false,\"items\":[{\"sku\":\"A\",\"quantity\":0}]}",
                "{\"expected\":\"preparing\",\"kind\":\"adjust\",\"rotate\":true,\"items\":[{\"sku\":\"A\",\"quantity\":0}]}",
                "{\"expected\":\"preparing\",\"kind\":\"adjust\",\"rotate\":false,\"items\":[{\"sku\":\"A\",\"quantity\":1}]}"))
            assertThat(post(ROUTE, changed, KEY).hash()).isNotEqualTo(first.hash());
    }

    @Test
    void routeAndTargetBelongToTheHashAndArrayOrderIsNotSilentlyChanged() {
        var first = post("/api/v1/admin/orders/" + UUID.randomUUID() + "/cancel", "{}", KEY);
        var second = post("/api/v1/admin/orders/" + UUID.randomUUID() + "/cancel", "{}", KEY);
        assertThat(second.hash()).isNotEqualTo(first.hash());
        assertThat(post("/api/v1/admin/payments", "{}", KEY).hash())
                .isNotEqualTo(post(ROUTE, "{}", KEY).hash());
        assertThat(post(ROUTE, "{\"items\":[\"A\",\"B\"]}", KEY).hash())
                .isNotEqualTo(post(ROUTE, "{\"items\":[\"B\",\"A\"]}", KEY).hash());
    }

    @Test
    void frameworkDoesNotApplyToAuthPublicOrdersPatchOrSettings() {
        for (String route : List.of(
                "/api/v1/admin/auth/login",
                "/api/v1/admin/auth/logout",
                "/api/v1/orders",
                "/api/v1/admin/settings",
                ROUTE + "?token=secret"))
            assertThatThrownBy(() -> post(route, "{}", KEY)).isInstanceOf(IllegalArgumentException.class);
        for (String method : List.of("PATCH", "PUT", "GET"))
            assertThatThrownBy(() -> CanonicalOperation.adminPost(method, ROUTE, json.createObjectNode(), KEY))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> post(ROUTE, "{\"value\":1.5}", KEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noMutableRequestOrHashAndNoResultDataEscapesThroughToString() {
        var body = json.createObjectNode().put("reason", "private-text");
        var command = CanonicalOperation.adminPost("POST", ROUTE, body, KEY);
        byte[] original = command.hash();
        body.put("reason", "changed");
        command.hash()[0] ^= 1;
        assertThat(command.hash()).isEqualTo(original);
        assertThat(command.toString()).doesNotContain("private-text", KEY);
        var result = json.createObjectNode().put("status", "confirmed");
        var receipt = new OperationReceipt(UUID.randomUUID(), false, result);
        result.put("status", "changed");
        receipt.result().put("status", "changed");
        assertThat(receipt.result().path("status").asString()).isEqualTo("confirmed");
        assertThat(receipt.toString()).doesNotContain("confirmed");
    }

    private CanonicalOperation post(String route, String body, String key) {
        return CanonicalOperation.adminPost("POST", route, json.readTree(body), key);
    }

    @Test
    void cliFramingIsSeparateAndIncludesCommandAndEntireNormalizedBody() {
        var body = json.createObjectNode().put("quantity", 1);
        var first = CanonicalOperation.commandLine("catalog/initial-stock", body, KEY);
        assertThat(first.hash())
                .isNotEqualTo(
                        CanonicalOperation.adminPost("POST", ROUTE, body, KEY).hash());
        assertThat(first.hash())
                .isNotEqualTo(CanonicalOperation.commandLine("catalog/adjust-stock", body, KEY)
                        .hash());
        assertThat(first.hash())
                .isEqualTo(CanonicalOperation.commandLine("catalog/initial-stock", body, "fixture-operation-0002")
                        .hash());
        body.put("quantity", 2);
        assertThat(first.hash())
                .isNotEqualTo(CanonicalOperation.commandLine("catalog/initial-stock", body, KEY)
                        .hash());
        for (String invalid : List.of(
                "catalog/../stock", "catalog/stock?secret", "/catalog/stock", "catalog//stock", "catalog/stock/"))
            assertThatThrownBy(() -> CanonicalOperation.commandLine(invalid, body, KEY))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
