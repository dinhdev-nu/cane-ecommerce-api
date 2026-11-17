package vn.cane.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.fixture.AdminHttpFixture;

class AdminSettingsIT extends AdminHttpFixture {
    @Test
    void ownerReadsExactlySixteenKeysAndWritesOnlyAllowedAtomicPatches() throws Exception {
        var values = ok(get("admin/settings", owner)).path("values");
        assertThat(values.size()).isEqualTo(16);
        error(get("admin/settings", operator), 403, "FORBIDDEN");
        error(put(patch("low_stock_threshold", JSON.valueToTree(5)), false), 403, "FORBIDDEN");
        var bad = JSON.createObjectNode().put("low_stock_threshold", 5).put("bank_auto_confirm", true);
        long events = eventCount();
        var before = setting("low_stock_threshold");
        error(put(wrap(bad), true), 422, "INVALID_INPUT");
        assertThat(setting("low_stock_threshold")).isEqualTo(before);
        assertThat(eventCount()).isEqualTo(events);
        error(put(patch("telegram_token", JSON.stringNode("MOCK not a setting")), true), 400, "INVALID_INPUT");
        error(put(wrap(JSON.createObjectNode()), true), 422, "INVALID_INPUT");
        var updated = ok(put(patch("low_stock_threshold", JSON.valueToTree(8)), true));
        assertThat(updated.path("values").size()).isEqualTo(16);
        assertThat(updated.path("values").path("low_stock_threshold").asInt()).isEqualTo(8);
        long after = eventCount();
        ok(put(patch("low_stock_threshold", JSON.valueToTree(8)), true));
        assertThat(eventCount()).isEqualTo(after);
        String facts = sql.queryForObject(
                "SELECT data::text FROM cane.business_events WHERE kind='settings_updated' ORDER BY created_at DESC,id DESC LIMIT 1",
                String.class);
        assertThat(JSON.readTree(facts).path("changedKeys").get(0).asString()).isEqualTo("low_stock_threshold");
        assertThat(facts).doesNotContain("email", "accountNo", "phone", "MOCK");
    }

    @Test
    void savesDifferentGroupsWithoutLosingChangesAndValidatesMergedCrossConfiguration() throws Exception {
        var a = patch("bank_hold_min", JSON.valueToTree(70));
        var b = patch("stale_alert_hours", JSON.valueToTree(30));
        var result = race(a, b);
        result.forEach(AdminHttpFixture::ok);
        assertThat(setting("bank_hold_min").asInt()).isEqualTo(70);
        assertThat(setting("stale_alert_hours").asInt()).isEqualTo(30);
        setting("enabled_payment_methods", JSON.createArrayNode().add("cod"));
        var enable = patch(
                "enabled_payment_methods", JSON.createArrayNode().add("cod").add("bank_transfer"));
        var remove = patch("bank_details", JSON.nullNode());
        var conflicting = race(enable, remove);
        assertThat(conflicting.stream()
                        .map(java.net.http.HttpResponse::statusCode)
                        .toList())
                .containsExactlyInAnyOrder(200, 422);
        var methods = setting("enabled_payment_methods");
        boolean bank = false;
        for (var method : methods) bank |= method.asString().equals("bank_transfer");
        assertThat(bank && setting("bank_details").isNull()).isFalse();
    }

    @Test
    void lowStockCacheInvalidatesOnlyAfterCommitAndAuditFailureRollsBackAllKeys() throws Exception {
        var redis = context.getBean(StringRedisTemplate.class);
        String cache = "cane:test:cache:prices:v1";
        ok(get("prices", null));
        assertThat(redis.opsForValue().get(cache)).isNotNull();
        var before = setting("low_stock_threshold");
        sql.execute(
                "CREATE FUNCTION cane.wp06_fail_settings() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.kind='settings_updated' THEN RAISE EXCEPTION 'MOCK audit failure'; END IF; RETURN NEW; END $$");
        sql.execute(
                "CREATE TRIGGER wp06_fail_settings BEFORE INSERT ON cane.business_events FOR EACH ROW EXECUTE FUNCTION cane.wp06_fail_settings()");
        try {
            var values = JSON.createObjectNode().put("low_stock_threshold", 100).put("accepting_orders", false);
            error(put(wrap(values), true), 503, "SERVICE_UNAVAILABLE");
            assertThat(setting("low_stock_threshold")).isEqualTo(before);
            assertThat(setting("accepting_orders").asBoolean()).isTrue();
            assertThat(redis.opsForValue().get(cache)).isNotNull();
        } finally {
            sql.execute("DROP TRIGGER wp06_fail_settings ON cane.business_events");
            sql.execute("DROP FUNCTION cane.wp06_fail_settings()");
        }
        ok(put(patch("low_stock_threshold", JSON.valueToTree(101)), true));
        assertThat(redis.opsForValue().get(cache)).isNull();
    }

    private java.net.http.HttpResponse<String> put(JsonNode body, boolean isOwner) throws Exception {
        return send("PUT", "admin/settings", body, null, isOwner ? owner : operator, true);
    }

    private static ObjectNode patch(String key, JsonNode value) {
        var values = JSON.createObjectNode();
        values.set(key, value);
        return wrap(values);
    }

    private static ObjectNode wrap(ObjectNode values) {
        var root = JSON.createObjectNode();
        root.set("values", values);
        return root;
    }

    private long eventCount() {
        return sql.queryForObject(
                "SELECT count(*) FROM cane.business_events WHERE kind='settings_updated'", Long.class);
    }

    private List<java.net.http.HttpResponse<String>> race(JsonNode a, JsonNode b) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(a, b).stream()
                    .map(body -> pool.submit(() -> {
                        ready.countDown();
                        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Settings race timeout");
                        return put(body, true);
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
