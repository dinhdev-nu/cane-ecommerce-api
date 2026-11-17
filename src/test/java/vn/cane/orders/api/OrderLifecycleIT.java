package vn.cane.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.fixture.AdminHttpFixture;
import vn.cane.support.transaction.Transactions;

class OrderLifecycleIT extends AdminHttpFixture {
    @Test
    void bankOrderProgressesOnlyAfterReceiptAndCloseReturnsEveryPurchasedSkuExplicitly() throws Exception {
        // The shared validation fixture contains inactive SKUs and a max-safe price.
        // This workflow scenario needs all nine available with an ordinary finite total.
        sql.update("UPDATE cane.products SET is_active=true,price_vnd=10000");
        var quantities = new java.util.LinkedHashMap<String, Integer>();
        sql.queryForList("SELECT sku FROM cane.products ORDER BY sku", String.class)
                .forEach(sku -> quantities.put(sku, 2));
        var order = place("delivery", "bank_transfer", quantities);
        error(post(route(order, "prepare"), command("confirmed"), key(), operator), 409, "STATE_CONFLICT");
        var bank = JSON.readTree(
                sql.queryForObject("SELECT bank_snapshot::text FROM cane.orders WHERE id=?", String.class, order.id()));
        var payment = command("awaiting_payment")
                .put("method", "bank")
                .put("amountVnd", order.total())
                .put("reference", "MOCK-" + key());
        payment.putObject("bankReceipt")
                .put("bankCode", bank.path("bankCode").asString())
                .put("accountNo", bank.path("accountNo").asString())
                .put("transferContent", "MOCK transfer " + order.number())
                .put("transactionAt", java.time.Instant.now().minusSeconds(60).toString());
        ok(post(route(order, "confirm-payment"), payment, key(), owner));
        shippingIssue(order);
        var close = command("shipping").put("goodsResolved", true).put("reason", "MOCK goods checked");
        var lines = close.putArray("restockItems");
        int index = 0;
        for (String sku : quantities.keySet())
            lines.addObject().put("sku", sku).put("quantity", index++ % 3).put("note", "MOCK condition inspected");
        var incomplete = close.deepCopy();
        incomplete.withArray("restockItems").remove(8);
        error(post(route(order, "close-delivery-issue"), incomplete, key(), owner), 422, "INVALID_INPUT");
        var before = sql.queryForList("SELECT sku,stock_qty FROM cane.products ORDER BY sku");
        ok(post(route(order, "close-delivery-issue"), close, key(), owner));
        for (int i = 0; i < before.size(); i++)
            assertThat(sql.queryForObject(
                            "SELECT stock_qty FROM cane.products WHERE sku=?",
                            Integer.class,
                            before.get(i).get("sku")))
                    .isEqualTo(((Number) before.get(i).get("stock_qty")).intValue() + i % 3);
        assertThat(count(order, "stock_movements", "reason='delivery_return'")).isEqualTo(6);
        assertThat(sql.queryForObject(
                        "SELECT jsonb_array_length(data->'restockItems') FROM cane.business_events WHERE order_id=? AND kind='delivery_failed_closed'",
                        Integer.class,
                        order.id()))
                .isEqualTo(9);
        assertThat(sql.queryForObject("SELECT payment_status FROM cane.orders WHERE id=?", String.class, order.id()))
                .isEqualTo("refund_pending");
        assertThat(count(order, "notification_outbox", "kind='delivery_failed_closed'"))
                .isEqualTo(1);
        assertThat(count(order, "notification_outbox", "kind='cancelled'")).isZero();
    }

    @Test
    void deliveryProgressDoesNotChargeOrChangeStockAndCompletesWithUnpaidCod() throws Exception {
        var order = place("delivery", "cod");
        int stock = stock(order);
        step(order, "prepare", "confirmed");
        assertThat(count(order, "notification_outbox", "kind='shipped'")).isZero();
        error(post(route(order, "ready-for-pickup"), command("preparing"), key(), operator), 409, "STATE_CONFLICT");
        step(order, "ship", "preparing");
        var issue = command("shipping").put("clear", false).put("reason", "MOCK customer unreachable");
        var marked = ok(post(route(order, "delivery-issue"), issue, key(), operator));
        assertThat(marked.path("result").path("deliveryIssue").asBoolean()).isTrue();
        assertThat(count(order, "notification_outbox", "kind='shipped'")).isEqualTo(1);
        assertThat(count(order, "notification_outbox", "kind='completed'")).isZero();
        step(order, "deliver", "shipping");
        var detail = ok(get("admin/orders/" + order.id(), operator)).path("order");
        assertThat(detail.path("status").asString()).isEqualTo("completed");
        assertThat(detail.path("paymentStatus").asString()).isEqualTo("unpaid");
        assertThat(detail.path("deliveryIssue").asBoolean()).isFalse();
        assertThat(detail.path("deliveryIssueReason").isNull()).isTrue();
        assertThat(count(order, "payments", "true")).isZero();
        assertThat(stock(order)).isEqualTo(stock);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pickupCollectsOnlyWhenUnpaidAndReplayDoesNotChargeAgain(boolean paid) throws Exception {
        var order = place("pickup", "cod");
        int stock = stock(order);
        if (paid) ok(post(route(order, "confirm-payment"), cash("confirmed", order.total()), key(), operator));
        step(order, "prepare", "confirmed");
        step(order, "ready-for-pickup", "preparing");
        var body = command("ready_for_pickup");
        if (!paid) {
            error(post(route(order, "hand-over"), body, key(), operator), 409, "PAYMENT_CONFLICT");
            body.put("cashCollectedVnd", order.total());
        } else {
            error(
                    post(
                            route(order, "hand-over"),
                            body.deepCopy().put("cashCollectedVnd", order.total()),
                            key(),
                            operator),
                    422,
                    "INVALID_INPUT");
        }
        String key = key();
        var first = ok(post(route(order, "hand-over"), body, key, operator));
        assertThat(first.path("result").path("paymentStatus").asString()).isEqualTo("paid");
        assertThat(first.path("result").path("status").asString()).isEqualTo("completed");
        assertThat(ok(post(route(order, "hand-over"), body, key, operator))
                        .path("replayed")
                        .asBoolean())
                .isTrue();
        error(post(route(order, "hand-over"), body, key(), operator), 409, "STATE_CONFLICT");
        assertThat(count(order, "payments", "applied_to_order")).isEqualTo(1);
        assertThat(count(order, "business_events", "kind='order_completed'")).isEqualTo(1);
        assertThat(count(order, "business_events", "kind='payment_received'")).isEqualTo(paid ? 1 : 0);
        assertThat(count(order, "notification_outbox", "kind='completed'")).isEqualTo(1);
        assertThat(stock(order)).isEqualTo(stock);
    }

    @Test
    void cancellationReturnsAllOnceAndReplayPrecedesStateAndStillChecksCurrentRole() throws Exception {
        var order = place("delivery", "cod");
        int before = stock(order);
        var body = command("confirmed").put("reason", "MOCK request cancelled");
        String key = key();
        var first = ok(post(route(order, "cancel"), body, key, operator));
        assertThat(first.path("result").path("cancelKind").asString()).isEqualTo("before_handover");
        assertThat(stock(order)).isEqualTo(before + 2);
        assertThat(ok(post(route(order, "cancel"), body, key, operator)).path("result"))
                .isEqualTo(first.path("result"));
        error(
                post(route(order, "cancel"), body.deepCopy().put("reason", "changed"), key, operator),
                409,
                "IDEMPOTENCY_MISMATCH");
        error(post(route(order, "cancel"), body, key(), operator), 409, "STATE_CONFLICT");
        assertThat(count(order, "stock_movements", "reason='cancel'")).isEqualTo(1);
        assertThat(count(order, "notification_outbox", "kind='cancelled'")).isEqualTo(1);

        var paid = place("pickup", "cod");
        ok(post(route(paid, "confirm-payment"), cash("confirmed", paid.total()), key(), owner));
        error(post(route(paid, "cancel"), body, key(), operator), 403, "FORBIDDEN");
        String paidKey = key();
        ok(post(route(paid, "cancel"), body, paidKey, owner));
        assertThat(sql.queryForObject("SELECT payment_status FROM cane.orders WHERE id=?", String.class, paid.id()))
                .isEqualTo("refund_pending");
        UUID ownerId = staff(owner);
        sql.update("UPDATE cane.staff_users SET role='operator',auth_version=2 WHERE id=?", ownerId);
        var downgraded = context.getBean(Transactions.class)
                .write(() -> context.getBean(StaffSessionService.class).issue(ownerId, 2, null));
        error(post(route(paid, "cancel"), body, paidKey, downgraded), 403, "FORBIDDEN");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void closeDeliveryReturnsOnlySalableQtyAndRefundsFullPrincipalIncludingShipping(int returned) throws Exception {
        var order = place("delivery", "cod");
        ok(post(route(order, "confirm-payment"), cash("confirmed", order.total()), key(), owner));
        shippingIssue(order);
        int before = stock(order);
        var body = close(order, returned);
        String key = key();
        error(post(route(order, "close-delivery-issue"), body, key(), operator), 403, "FORBIDDEN");
        var closed = ok(post(route(order, "close-delivery-issue"), body, key, owner));
        assertThat(closed.path("result").path("paymentStatus").asString()).isEqualTo("refund_pending");
        assertThat(closed.path("result")
                        .path("restockItems")
                        .get(0)
                        .path("quantity")
                        .asInt())
                .isEqualTo(returned);
        assertThat(stock(order)).isEqualTo(before + returned);
        assertThat(count(order, "stock_movements", "reason='delivery_return'")).isEqualTo(returned == 0 ? 0 : 1);
        assertThat(count(order, "notification_outbox", "kind='delivery_failed_closed'"))
                .isEqualTo(1);
        assertThat(count(order, "notification_outbox", "kind='cancelled'")).isZero();
        ok(post(route(order, "close-delivery-issue"), body, key, owner));
        assertThat(stock(order)).isEqualTo(before + returned);
        error(post(route(order, "deliver"), command("shipping"), key(), owner), 409, "STATE_CONFLICT");
        UUID principal = sql.queryForObject(
                "SELECT id FROM cane.payments WHERE order_id=? AND applied_to_order", UUID.class, order.id());
        ok(post(
                "admin/payments/" + principal + "/confirm-refund",
                JSON.createObjectNode()
                        .put("expectedPaymentStatus", "succeeded")
                        .put("reference", "MOCK external full refund"),
                key(),
                owner));
        assertThat(sql.queryForObject("SELECT amount_vnd FROM cane.payments WHERE id=?", Long.class, principal))
                .isEqualTo(order.total());
        assertThat(stock(order)).isEqualTo(before + returned);
    }

    @Test
    void validatesAllRestockLinesAndRollsBackWhenOutboxFailsIncludingPickupReceipt() throws Exception {
        var order = place("delivery", "cod");
        shippingIssue(order);
        int before = stock(order);
        var bad = close(order, 1);
        ((ObjectNode) bad.path("restockItems").get(0)).putNull("note");
        error(post(route(order, "close-delivery-issue"), bad, key(), owner), 422, "INVALID_INPUT");
        error(post(route(order, "close-delivery-issue"), close(order, 3), key(), owner), 422, "INVALID_INPUT");
        error(
                post(route(order, "close-delivery-issue"), close(order, 1).put("goodsResolved", false), key(), owner),
                422,
                "INVALID_INPUT");
        var duplicate = close(order, 1);
        duplicate
                .withArray("restockItems")
                .add(duplicate.path("restockItems").get(0).deepCopy());
        error(post(route(order, "close-delivery-issue"), duplicate, key(), owner), 422, "INVALID_INPUT");
        assertThat(stock(order)).isEqualTo(before);
        assertThat(count(order, "stock_movements", "reason='delivery_return'")).isZero();

        var pickup = place("pickup", "cod");
        int beforeRollback = stock(order);
        step(pickup, "prepare", "confirmed");
        step(pickup, "ready-for-pickup", "preparing");
        String key = key();
        var handover = command("ready_for_pickup").put("cashCollectedVnd", pickup.total());
        sql.execute(
                "CREATE FUNCTION cane.wp06_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'MOCK outbox failure'; END $$");
        sql.execute(
                "CREATE TRIGGER wp06_fail_outbox BEFORE INSERT ON cane.notification_outbox FOR EACH ROW EXECUTE FUNCTION cane.wp06_fail_outbox()");
        try {
            error(post(route(pickup, "hand-over"), handover, key, operator), 503, "SERVICE_UNAVAILABLE");
            error(
                    post(route(order, "close-delivery-issue"), close(order, 1), key(), owner),
                    503,
                    "SERVICE_UNAVAILABLE");
            assertThat(count(pickup, "payments", "true")).isZero();
            assertThat(count(pickup, "business_events", "kind='order_completed'"))
                    .isZero();
            assertThat(count(order, "business_events", "kind='delivery_failed_closed'"))
                    .isZero();
            assertThat(count(order, "stock_movements", "reason='delivery_return'"))
                    .isZero();
            assertThat(stock(order)).isEqualTo(beforeRollback);
            assertThat(sql.queryForObject("SELECT status FROM cane.orders WHERE id=?", String.class, pickup.id()))
                    .isEqualTo("ready_for_pickup");
        } finally {
            sql.execute("DROP TRIGGER wp06_fail_outbox ON cane.notification_outbox");
            sql.execute("DROP FUNCTION cane.wp06_fail_outbox()");
        }
        ok(post(route(pickup, "hand-over"), handover, key, operator));
    }

    @Test
    void concurrentDeliverAndCloseChooseOneOutcomeWithoutDoubleReturningStock() throws Exception {
        var order = place("delivery", "cod");
        shippingIssue(order);
        int before = stock(order);
        var responses = race(
                () -> post(route(order, "deliver"), command("shipping"), key(), operator),
                () -> post(route(order, "close-delivery-issue"), close(order, 1), key(), owner));
        assertThat(responses.stream()
                        .map(java.net.http.HttpResponse::statusCode)
                        .toList())
                .containsExactlyInAnyOrder(200, 409);
        String status = sql.queryForObject("SELECT status FROM cane.orders WHERE id=?", String.class, order.id());
        assertThat(stock(order)).isEqualTo(before + (status.equals("cancelled") ? 1 : 0));
        assertThat(count(order, "business_events", "kind IN ('order_completed','delivery_failed_closed')"))
                .isEqualTo(1);
    }

    @Test
    void pickupHandoverAndCashConfirmationCannotCreateTwoPrincipals() throws Exception {
        var order = place("pickup", "cod");
        step(order, "prepare", "confirmed");
        step(order, "ready-for-pickup", "preparing");
        var responses = race(
                () -> post(
                        route(order, "hand-over"),
                        command("ready_for_pickup").put("cashCollectedVnd", order.total()),
                        key(),
                        operator),
                () -> post(route(order, "confirm-payment"), cash("ready_for_pickup", order.total()), key(), owner));
        assertThat(responses.stream().map(java.net.http.HttpResponse::statusCode))
                .allMatch(status -> List.of(200, 409, 422).contains(status));
        assertThat(responses.stream().filter(r -> r.statusCode() == 200).count())
                .isPositive();
        if (sql.queryForObject("SELECT status FROM cane.orders WHERE id=?", String.class, order.id())
                .equals("ready_for_pickup"))
            ok(post(route(order, "hand-over"), command("ready_for_pickup"), key(), operator));
        assertThat(count(order, "payments", "applied_to_order")).isEqualTo(1);
        assertThat(count(order, "payments", "true")).isEqualTo(1);
        assertThat(count(order, "notification_outbox", "kind='completed'")).isEqualTo(1);
        UUID principal = sql.queryForObject(
                "SELECT id FROM cane.payments WHERE order_id=? AND applied_to_order", UUID.class, order.id());
        assertThat(sql.queryForObject(
                        "SELECT payment_id FROM cane.business_events WHERE order_id=? AND kind='order_completed'",
                        UUID.class,
                        order.id()))
                .isEqualTo(principal);
    }

    @Test
    void cancellationAndCashConfirmationLeaveOneConsistentRefundObligation() throws Exception {
        var order = place("delivery", "cod");
        int before = stock(order);
        var responses = race(
                () -> post(
                        route(order, "cancel"), command("confirmed").put("reason", "MOCK cancellation"), key(), owner),
                () -> post(route(order, "confirm-payment"), cash("confirmed", order.total()), key(), operator));
        assertThat(responses.get(0).statusCode()).isEqualTo(200);
        assertThat(responses.get(1).statusCode()).isIn(200, 409);
        long principals = count(order, "payments", "applied_to_order");
        assertThat(principals).isIn(0L, 1L);
        assertThat(sql.queryForObject("SELECT payment_status FROM cane.orders WHERE id=?", String.class, order.id()))
                .isEqualTo(principals == 0 ? "unpaid" : "refund_pending");
        assertThat(stock(order)).isEqualTo(before + 2);
        assertThat(count(order, "stock_movements", "reason='cancel'")).isEqualTo(1);
        assertThat(count(order, "notification_outbox", "kind='cancelled'")).isEqualTo(1);
    }

    private void shippingIssue(Order order) throws Exception {
        step(order, "prepare", "confirmed");
        step(order, "ship", "preparing");
        ok(post(
                route(order, "delivery-issue"),
                command("shipping").put("clear", false).put("reason", "MOCK delivery issue"),
                key(),
                operator));
    }

    private void step(Order order, String action, String expected) throws Exception {
        ok(post(route(order, action), command(expected), key(), operator));
    }

    private static ObjectNode command(String expected) {
        return JSON.createObjectNode().put("expectedStatus", expected);
    }

    private static String route(Order order, String action) {
        return "admin/orders/" + order.id() + "/" + action;
    }

    private static ObjectNode close(Order order, int qty) {
        var command = command("shipping").put("goodsResolved", true).put("reason", "MOCK goods checked");
        command.putArray("restockItems")
                .addObject()
                .put("sku", order.sku())
                .put("quantity", qty)
                .put("note", qty < 2 ? "MOCK damaged/missing" : null);
        return command;
    }

    private int stock(Order order) {
        return sql.queryForObject("SELECT stock_qty FROM cane.products WHERE sku=?", Integer.class, order.sku());
    }

    private long count(Order order, String table, String filter) {
        return sql.queryForObject(
                "SELECT count(*) FROM cane." + table + " WHERE order_id=? AND " + filter, Long.class, order.id());
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream()
                    .map(task -> pool.submit(() -> {
                        ready.countDown();
                        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timeout");
                        return task.call();
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
