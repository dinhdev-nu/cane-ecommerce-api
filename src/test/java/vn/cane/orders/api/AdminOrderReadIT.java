package vn.cane.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.cane.fixture.AdminHttpFixture;

class AdminOrderReadIT extends AdminHttpFixture {
    @Test
    void listFiltersPagesAndDoesNotExposeDetailOrOperationSecrets() throws Exception {
        var order = place("delivery", "cod");
        var result = ok(get(
                "admin/orders?search=" + order.number()
                        + "&status=confirmed&paymentStatus=unpaid&fulfillmentMethod=delivery&paymentMethod=cod&deliveryIssue=false&cancelRequested=false&pageSize=1",
                owner));
        assertThat(result.path("total").asLong()).isEqualTo(1);
        assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("id").asString())
                .isEqualTo(order.id().toString());
        assertThat(result.path("items").toString())
                .doesNotContain("phone", "email", "address", "viewUrl", "token", "requestHash", "operationKey");
        assertThat(result.path("items").get(0).path("recipient").size()).isEqualTo(1);
        assertThat(ok(get("admin/orders?search=" + order.number() + "&pageSize=1&page=2", owner))
                        .path("items")
                        .size())
                .isZero();
        assertThat(ok(get("admin/orders?search=0338825982", operator))
                        .path("total")
                        .asLong())
                .isPositive();
        assertThat(ok(get("admin/orders?search=%25", owner)).path("total").asLong())
                .isZero();
        var empty = ok(get("admin/orders?search=" + order.number() + "&updatedSince=2099-01-01T00:00:00Z", owner));
        assertThat(empty.path("total").asLong()).isZero();
        assertThat(result.path("serverTime").asString()).endsWith("Z");
    }

    @Test
    void detailUsesSnapshotFieldsAndCurrentPermissionsAndHidesExtrasForOperator() throws Exception {
        var order = place("pickup", "cod");
        var paid = ok(post(
                "admin/orders/" + order.id() + "/confirm-payment", cash("confirmed", order.total()), key(), owner));
        UUID payment = UUID.fromString(paid.path("result").path("paymentId").asString());
        var intake = cash("confirmed", 5000);
        intake.remove("expectedStatus");
        intake.put("orderId", order.id().toString());
        intake.put("note", "MOCK cash received outside normal confirmation");
        ok(post("admin/payments", intake, key(), owner));
        var own = ok(get("admin/orders/" + order.id(), owner));
        assertThat(own.path("extraPayments").size()).isEqualTo(1);
        assertThat(own.path("principalPayment").path("id").asString()).isEqualTo(payment.toString());
        assertThat(own.path("permissions").path("canCancel").asBoolean()).isTrue();
        assertThat(own.path("order").path("recipient").path("phone").asString()).isEqualTo("0338825982");
        assertThat(own.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(own.path("timeline").size()).isGreaterThanOrEqualTo(2);
        assertThat(own.toString())
                .doesNotContain(
                        "operation_key",
                        "operationResult",
                        "requestHash",
                        "bank_snapshot",
                        "idempotency",
                        "viewUrl",
                        "token");
        var staff = ok(get("admin/orders/" + order.id(), operator));
        assertThat(staff.has("extraPayments")).isFalse();
        assertThat(staff.path("permissions").path("canCancel").asBoolean()).isFalse();
        assertThat(staff.path("permissions").path("canResendLink").asBoolean()).isFalse();
    }

    @Test
    void bankInstructionsAndPermissionFollowCurrentStateAndFlag() throws Exception {
        var order = place("delivery", "bank_transfer");
        setting("operator_can_confirm_bank", JSON.booleanNode(false));
        var detail = ok(get("admin/orders/" + order.id(), operator));
        assertThat(detail.path("permissions").path("canConfirmBank").asBoolean())
                .isFalse();
        assertThat(detail.path("order").path("paymentReminderAt").isString()).isTrue();
        assertThat(detail.path("order").path("expiresAt").isNull()).isTrue();
        assertThat(detail.path("order")
                        .path("bankInstructions")
                        .path("transferContent")
                        .asString())
                .isEqualTo(order.number());
        setting("operator_can_confirm_bank", JSON.booleanNode(true));
        assertThat(ok(get("admin/orders/" + order.id(), operator))
                        .path("permissions")
                        .path("canConfirmBank")
                        .asBoolean())
                .isTrue();
        var bank = setting("bank_details");
        var confirm = JSON.createObjectNode()
                .put("expectedStatus", "awaiting_payment")
                .put("method", "bank")
                .put("amountVnd", order.total())
                .put("reference", "MOCK-" + key());
        confirm.putObject("bankReceipt")
                .put("bankCode", bank.path("bankCode").asString())
                .put("accountNo", bank.path("accountNo").asString())
                .put("transferContent", order.number())
                .put("transactionAt", "2026-11-17T03:00:00Z");
        ok(post("admin/orders/" + order.id() + "/confirm-payment", confirm, key(), owner));
        var completed = ok(get("admin/orders/" + order.id(), owner)).path("order");
        assertThat(completed.path("bankInstructions").isNull()).isTrue();
        assertThat(completed.path("paymentReminderAt").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidFiltersMissingOrderAndRevokedSessions() throws Exception {
        error(get("admin/orders", null), 401, "AUTH_REQUIRED");
        for (String query : new String[] {
            "status[]=confirmed",
            "status=confirmed&status=shipping",
            "status=unknown",
            "pageSize=101",
            "page=0",
            "deliveryIssue=1",
            "updatedSince=bad",
            "today=true"
        }) error(get("admin/orders?" + query.replace("[", "%5B").replace("]", "%5D"), owner), 422, "INVALID_INPUT");
        error(get("admin/orders/" + UUID.randomUUID(), owner), 404, "NOT_FOUND");
        sql.update(
                "UPDATE cane.staff_users SET is_active=false,auth_version=auth_version+1 WHERE id=?", staff(operator));
        error(get("admin/orders", operator), 401, "AUTH_REQUIRED");
    }

    @Test
    void failureSummaryIsGlobalBoundedAndOwnerOnly() throws Exception {
        var order = place("delivery", "cod");
        UUID source = sql.queryForObject(
                "SELECT id FROM cane.business_events WHERE order_id=? AND kind='order_placed'", UUID.class, order.id());
        for (int i = 0; i < 23; i++) {
            UUID outbox = UUID.randomUUID();
            sql.update(
                    "INSERT INTO cane.notification_outbox(id,order_id,event_id,kind,channel,recipient,status,attempts,last_error) VALUES (?,?,?,'owner_alert','email',?,'exhausted',8,?)",
                    outbox,
                    order.id(),
                    source,
                    "mock-" + i + "@example.invalid",
                    "sensitive@example.invalid");
            sql.update(
                    "INSERT INTO cane.business_events(order_id,kind,actor,data,source_key) VALUES (?,'notification_exhausted','system',?::jsonb,?)",
                    order.id(),
                    JSON.createObjectNode()
                            .put("failedOutboxId", outbox.toString())
                            .toString(),
                    "outbox:" + outbox + ":exhausted");
        }
        var failures = ok(get("admin/orders?search=doesnotmatch", owner));
        assertThat(failures.path("total").asLong()).isZero();
        assertThat(failures.path("notificationFailures").path("total").asLong()).isGreaterThanOrEqualTo(23);
        assertThat(failures.path("notificationFailures").path("items").size()).isEqualTo(20);
        assertThat(failures.toString()).doesNotContain("sensitive@example.invalid", "recipient", "payload");
        assertThat(ok(get("admin/orders", operator)).has("notificationFailures"))
                .isFalse();
    }
}
