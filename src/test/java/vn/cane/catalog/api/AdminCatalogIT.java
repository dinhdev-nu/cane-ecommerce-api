package vn.cane.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.fixture.AdminHttpFixture;

class AdminCatalogIT extends AdminHttpFixture {
    @Test
    void setAndCheckoutWaitOnTheSameSkuAndRecheckExpectedAfterTheLock() throws Exception {
        String sku = sql.queryForObject(
                "SELECT sku FROM cane.products WHERE delivery_scope='city' AND is_active ORDER BY sku LIMIT 1",
                String.class);
        int before = stock(sku);
        try (var blocker = sql.getDataSource().getConnection();
                var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT sku FROM cane.products WHERE sku=? FOR UPDATE")) {
                statement.setString(1, sku);
                statement.executeQuery().close();
            }
            var set = pool.submit(() -> post(
                    "admin/stock-adjustments",
                    adjust(sku, "adjust", "set", 100).put("expectedStockQty", before),
                    key(),
                    owner));
            var checkout = pool.submit(() -> place("pickup", "cod", java.util.Map.of(sku, 2)));
            try {
                long end = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
                int waiters = 0;
                while (System.nanoTime() < end) {
                    waiters = new org.springframework.jdbc.core.JdbcTemplate(
                                    context.getBean(javax.sql.DataSource.class))
                            .queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='cane_api' AND wait_event_type='Lock' AND query ILIKE '%products%'",
                                    Integer.class);
                    if (waiters >= 2) break;
                    Thread.sleep(10);
                }
                assertThat(waiters)
                        .as("Both commands reached the held SKU lock")
                        .isGreaterThanOrEqualTo(2);
            } finally {
                blocker.commit();
            }
            var order = checkout.get(20, java.util.concurrent.TimeUnit.SECONDS);
            var result = set.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.statusCode()).isIn(200, 409);
            assertThat(stock(sku)).isEqualTo(result.statusCode() == 200 ? 98 : before - 2);
            if (result.statusCode() == 409) error(result, 409, "STOCK_CONFLICT");
            assertThat(sql.queryForObject(
                            "SELECT count(*) FROM cane.stock_movements WHERE order_id=? AND reason='order'",
                            Long.class,
                            order.id()))
                    .isEqualTo(1);
        }
    }

    @Test
    void stockCommandsReplayBeforeExpectedAndAdjustmentZeroHasAudit() throws Exception {
        String sku = sku();
        int before = stock(sku);
        String key = key();
        var restock = adjust(sku, "restock", "delta", 5);
        var first = ok(post("admin/stock-adjustments", restock, key, owner));
        assertThat(first.path("result").path("stockBefore").asInt()).isEqualTo(before);
        assertThat(first.path("result").path("stockAfter").asInt()).isEqualTo(before + 5);
        ok(post("admin/stock-adjustments", restock, key, owner));
        assertThat(stock(sku)).isEqualTo(before + 5);
        error(post("admin/stock-adjustments", restock, key, operator), 403, "FORBIDDEN");
        var set = adjust(sku, "adjust", "set", 7).put("expectedStockQty", before + 5);
        String setKey = key();
        var applied = ok(post("admin/stock-adjustments", set, setKey, owner));
        ok(post("admin/stock-adjustments", adjust(sku, "restock", "delta", 2), key(), owner));
        var replay = ok(post("admin/stock-adjustments", set, setKey, owner));
        assertThat(replay.path("result")).isEqualTo(applied.path("result"));
        assertThat(stock(sku)).isEqualTo(9);
        var conflict = post("admin/stock-adjustments", set, key(), owner);
        error(conflict, 409, "STOCK_CONFLICT");
        assertThat(JSON.readTree(conflict.body())
                        .path("error")
                        .path("current")
                        .path("stockQty")
                        .asInt())
                .isEqualTo(9);
        var zero = ok(post("admin/stock-adjustments", adjust(sku, "adjust", "delta", 0), key(), owner));
        UUID movement = UUID.fromString(zero.path("result").path("movementId").asString());
        assertThat(sql.queryForObject("SELECT delta FROM cane.stock_movements WHERE id=?", Integer.class, movement))
                .isZero();
        var movements = ok(get("admin/stock-movements?sku=" + sku + "&reason=adjust&pageSize=1", operator));
        assertThat(movements.path("total").asLong()).isEqualTo(2);
        assertThat(movements.path("items").size()).isEqualTo(1);
        assertThat(movements.path("items").get(0).path("delta").asInt()).isZero();
    }

    @Test
    void patchIsAbsoluteNoOpDoesNotAuditAndOrderKeepsSnapshot() throws Exception {
        var order = place("delivery", "cod");
        String sku = order.sku();
        long original = sql.queryForObject(
                "SELECT unit_price_vnd FROM cane.order_items WHERE order_id=?", Long.class, order.id());
        var body = JSON.createObjectNode().put("priceVnd", original + 1000).put("isActive", false);
        error(send("PATCH", "admin/skus/" + sku, body, null, operator, true), 403, "FORBIDDEN");
        var response = ok(send("PATCH", "admin/skus/" + sku, body, null, owner, true));
        assertThat(response.path("priceVnd").asLong()).isEqualTo(original + 1000);
        assertThat(response.path("isActive").asBoolean()).isFalse();
        long events = sql.queryForObject(
                "SELECT count(*) FROM cane.business_events WHERE kind='product_updated'", Long.class);
        ok(send("PATCH", "admin/skus/" + sku, body, null, owner, true));
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events WHERE kind='product_updated'", Long.class))
                .isEqualTo(events);
        assertThat(sql.queryForObject(
                        "SELECT unit_price_vnd FROM cane.order_items WHERE order_id=?", Long.class, order.id()))
                .isEqualTo(original);
        var all = ok(get("admin/skus", operator));
        assertThat(all.path("items").size()).isEqualTo(9);
        assertThat(all.toString()).doesNotContain("operation_key", "requestHash");
        // Restore availability for later cases without erasing append-only history.
        ok(send(
                "PATCH",
                "admin/skus/" + sku,
                JSON.createObjectNode().put("priceVnd", original).put("isActive", true),
                null,
                owner,
                true));
    }

    @Test
    void rejectsInvalidKindsNegativeStockAndUnapprovedProductFields() throws Exception {
        String sku = sku();
        int before = stock(sku);
        error(post("admin/stock-adjustments", adjust(sku, "restock", "delta", 0), key(), owner), 422, "INVALID_INPUT");
        error(
                post("admin/stock-adjustments", adjust(sku, "adjust", "delta", Integer.MIN_VALUE), key(), owner),
                422,
                "INVALID_INPUT");
        error(post("admin/stock-adjustments", adjust(sku, "cancel", "delta", 1), key(), owner), 422, "INVALID_INPUT");
        error(
                post(
                        "admin/stock-adjustments",
                        adjust(sku, "restock", "set", 1).put("expectedStockQty", before),
                        key(),
                        owner),
                422,
                "INVALID_INPUT");
        error(post("admin/stock-adjustments", adjust(sku, "adjust", "set", 1), key(), owner), 400, "INVALID_INPUT");
        error(
                send("PATCH", "admin/skus/" + sku, JSON.createObjectNode().put("stockQty", 999), null, owner, true),
                400,
                "INVALID_INPUT");
        error(
                send(
                        "PATCH",
                        "admin/skus/missing-sku",
                        JSON.createObjectNode().put("isActive", false),
                        null,
                        owner,
                        true),
                404,
                "NOT_FOUND");
        assertThat(stock(sku)).isEqualTo(before);
        error(get("admin/stock-movements?reason=invalid", owner), 422, "INVALID_INPUT");
    }

    private String sku() {
        return sql.queryForObject("SELECT sku FROM cane.products ORDER BY sku LIMIT 1", String.class);
    }

    private int stock(String sku) {
        return sql.queryForObject("SELECT stock_qty FROM cane.products WHERE sku=?", Integer.class, sku);
    }

    private static ObjectNode adjust(String sku, String kind, String mode, int value) {
        return JSON.createObjectNode()
                .put("sku", sku)
                .put("kind", kind)
                .put("mode", mode)
                .put("value", value)
                .put("reason", "MOCK inventory counted");
    }
}
