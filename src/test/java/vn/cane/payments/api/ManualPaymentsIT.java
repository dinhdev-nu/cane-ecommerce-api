package vn.cane.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.CaneApplication;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffCsrf;
import vn.cane.fixture.LinkTestKeys;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.orders.application.OrderMoneyService;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.Transactions;

/** Real HTTP/CSRF, runtime grants, PostgreSQL locks/immutable history and Redis. No provider calls. */
@Testcontainers
class ManualPaymentsIT {
    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate sql;
    private static HttpClient http;
    private static String base;
    private StaffSessionService.IssuedSession owner;
    private StaffSessionService.IssuedSession operator;
    private String csrf;

    @BeforeAll
    static void start() throws Exception {
        String database = "cane_money_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        sql = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        sql.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        sql.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api,cane_worker");
        sql.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        var app = new SpringApplication(CaneApplication.class);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(LinkTestKeys.with(Map.of(
                "CANE_DB_URL",
                url,
                "CANE_DB_USER",
                "cane_api",
                "CANE_DB_PASSWORD",
                TestInfrastructure.API_PASSWORD,
                "CANE_REDIS_HOST",
                REDIS.getHost(),
                "CANE_REDIS_PORT",
                REDIS.getMappedPort(6379).toString(),
                "CANE_REDIS_USER",
                "cane_runtime",
                "CANE_REDIS_PASSWORD",
                TestInfrastructure.REDIS_PASSWORD)));
        context = app.run("--spring.profiles.active=test,api", "--spring.main.keep-alive=false");
        base = "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort() + "/api/v1/";
        http = HttpClient.newHttpClient();
        for (var p : resource("/catalog/wp02/seed-fixture.json").get("products"))
            sql.update(
                    "INSERT INTO cane.products(sku,name,unit_label,price_vnd,stock_qty,is_active,delivery_scope) VALUES (?,?,?,?,1000,?,?)",
                    p.get("sku").asString(),
                    p.get("name").asString(),
                    p.get("unitLabel").asString(),
                    p.get("priceVnd").asLong(),
                    p.get("isActive").asBoolean(),
                    p.get("deliveryScope").asString());
    }

    @AfterAll
    static void stop() {
        if (http != null) http.close();
        if (context != null) context.close();
    }

    @BeforeEach
    void fixtures() throws Exception {
        // Independent keys/orders preserve append-only history; only mutable fixture configuration is reset.
        resource("/admin/wp02/settings-default.json").properties().forEach(e -> setting(e.getKey(), e.getValue()));
        for (var s : resource("/admin/wp02/settings-scenarios.json").get("scenarios"))
            if (s.get("name").asString().equals("complete"))
                s.get("patch").properties().forEach(e -> setting(e.getKey(), e.getValue()));
        setting("accepting_orders", JSON.booleanNode(true));
        String ip = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest("127.0.0.1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        context.getBean(StringRedisTemplate.class)
                .delete(List.of(
                        "cane:test:cache:prices:v1", "cane:test:limit:quote:" + ip, "cane:test:limit:place:" + ip));
        owner = issue("owner");
        operator = issue("operator");
        var bootstrap = send("GET", "admin/me", null, null, null, false);
        assertThat(bootstrap.statusCode()).isEqualTo(401);
        csrf = bootstrap.headers().firstValue(StaffCsrf.HEADER).orElseThrow();
    }

    @Test
    void bankConfirmUsesSnapshotAndReplayPrecedesChangedStateAndPlaceReplayIsCurrent() throws Exception {
        var order = place("delivery", "bank_transfer");
        var command = confirm(order, "bank");
        String key = key();
        setting("accepting_orders", JSON.booleanNode(false));
        setting("enabled_payment_methods", JSON.createArrayNode());
        setting("bank_details", JSON.nullNode());
        long stock = count("stock_movements");
        var result = ok(post(confirmRoute(order), command, key, owner));
        UUID payment = UUID.fromString(result.path("result").path("paymentId").asString());
        assertOrder(order, "confirmed", "paid");
        assertThat(sql.queryForObject(
                        "SELECT payment_due_at IS NULL FROM cane.orders WHERE id=?", Boolean.class, order.id()))
                .isTrue();
        var snapshot = counts();
        var replay = ok(post(confirmRoute(order), command, key, owner));
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("result")).isEqualTo(result.path("result"));
        assertThat(counts()).isEqualTo(snapshot);
        error(post(confirmRoute(order), command, key(), owner), 409, "STATE_CONFLICT");
        var placeReplay = send("POST", "orders", order.command(), order.key(), null, false);
        assertThat(placeReplay.statusCode()).isEqualTo(200);
        var preview = body(placeReplay).path("order");
        assertThat(preview.path("status").asString()).isEqualTo("confirmed");
        assertThat(preview.path("paymentStatus").asString()).isEqualTo("paid");
        assertThat(body(placeReplay).path("bankInstructions").isNull()).isTrue();
        assertThat(preview.path("paymentReminderAt").isNull()).isTrue();
        assertThat(preview.path("expiresAt").isNull()).isTrue();
        assertThat(counts()).isEqualTo(snapshot);
        assertThat(count("stock_movements")).isEqualTo(stock);
        var event = facts(payment, "payment_received");
        assertThat(event.path("reference").asString())
                .isEqualTo(command.path("reference").asString());
        assertThat(event.path("isPrincipal").asBoolean()).isTrue();
        assertThat(event.toString()).doesNotContain("bankReceipt", "accountNo", "viewUrl", "recipient");
        assertThat(notifications(payment, "payment_received")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT provider_ref FROM cane.payments WHERE id=?", String.class, payment))
                .startsWith("[");
    }

    @Test
    void cashConfirmsAllFiveStatesWithoutSeparateCashEmailsOrStockChanges() throws Exception {
        for (String state : List.of("confirmed", "preparing", "ready_for_pickup", "shipping", "completed")) {
            var order = place(state.equals("ready_for_pickup") ? "pickup" : "delivery", "cod");
            sql.update("UPDATE cane.orders SET status=? WHERE id=?", state, order.id());
            long stock = count("stock_movements");
            var command = confirm(order, "cash").put("expectedStatus", state);
            UUID payment = payment(ok(post(confirmRoute(order), command, key(), operator)));
            assertOrder(order, state, "paid");
            assertThat(notifications(payment, "payment_received")).isZero();
            assertThat(count("stock_movements")).isEqualTo(stock);
            error(post(confirmRoute(order), command, key(), operator), 409, "PAYMENT_CONFLICT");
        }
    }

    @Test
    void normalConfirmRejectsMethodAmountAccountAmbiguousMemoAndStaleExpectedWithoutRecordingMoney() throws Exception {
        var order = place("delivery", "bank_transfer");
        var baseline = counts();
        error(
                post(
                        confirmRoute(order),
                        confirm(order, "cash").put("expectedStatus", "awaiting_payment"),
                        key(),
                        owner),
                409,
                "PAYMENT_METHOD_MISMATCH");
        error(
                post(confirmRoute(order), confirm(order, "bank").put("amountVnd", order.total() - 1), key(), owner),
                409,
                "PAYMENT_CONFLICT");
        var wrongAccount = confirm(order, "bank");
        wrongAccount.withObject("bankReceipt").put("accountNo", "000wrong");
        error(post(confirmRoute(order), wrongAccount, key(), owner), 409, "PAYMENT_CONFLICT");
        for (String memo :
                List.of(order.number() + " " + order.number(), "X" + order.number(), order.number() + " CANEZZZ999")) {
            var wrongMemo = confirm(order, "bank");
            wrongMemo.withObject("bankReceipt").put("transferContent", memo);
            error(post(confirmRoute(order), wrongMemo, key(), owner), 409, "PAYMENT_CONFLICT");
        }
        var stale = post(confirmRoute(order), confirm(order, "bank").put("expectedStatus", "confirmed"), key(), owner);
        error(stale, 409, "STATE_CONFLICT");
        assertThat(body(stale).path("error").path("current").path("status").asString())
                .isEqualTo("awaiting_payment");
        assertThat(counts()).isEqualTo(baseline);
    }

    @Test
    void authorizationAndCsrfAreCurrentEvenForSuccessfulReplay() throws Exception {
        var order = place("pickup", "bank_transfer");
        var command = confirm(order, "bank");
        String key = key();
        setting("operator_can_confirm_bank", JSON.booleanNode(false));
        error(post(confirmRoute(order), command, key, operator), 403, "FORBIDDEN");
        setting("operator_can_confirm_bank", JSON.booleanNode(true));
        ok(post(confirmRoute(order), command, key, operator));
        setting("operator_can_confirm_bank", JSON.booleanNode(false));
        var snapshot = counts();
        error(post(confirmRoute(order), command, key, operator), 403, "FORBIDDEN");
        error(send("POST", confirmRoute(order), command, key, owner, false), 403, "FORBIDDEN");
        error(send("GET", "admin/payments?bad=1", null, null, operator, false), 403, "FORBIDDEN");
        error(post("admin/payments", JSON.createObjectNode(), key(), operator), 403, "FORBIDDEN");
        sql.update("UPDATE cane.staff_users SET auth_version=auth_version+1 WHERE id=?", staff(operator));
        error(post(confirmRoute(order), command, key, operator), 401, "AUTH_REQUIRED");
        assertThat(counts()).isEqualTo(snapshot);
    }

    @Test
    void bankIntakeDeduplicatesImmutableFactsWithoutOverwritingLinkAndListIsOwnerOnly() throws Exception {
        var firstOrder = place("delivery", "bank_transfer");
        var other = place("pickup", "cod");
        var command = intake(confirm(firstOrder, "bank"), firstOrder.id());
        String key = key();
        UUID payment = payment(ok(post("admin/payments", command, key, owner)));
        assertOrder(firstOrder, "awaiting_payment", "unpaid");
        assertThat(notifications(payment, "payment_received")).isZero();
        assertThat(notifications(payment, "owner_alert")).isPositive();
        var before = sql.queryForMap("SELECT * FROM cane.payments WHERE id=?", payment);
        var snapshot = counts();
        assertThat(ok(post("admin/payments", command, key, owner))
                        .path("replayed")
                        .asBoolean())
                .isTrue();
        assertThat(counts()).isEqualTo(snapshot);
        var duplicate =
                command.deepCopy().put("orderId", other.id().toString()).put("note", "new report, same transaction");
        ok(post("admin/payments", duplicate, key(), owner));
        assertThat(sql.queryForMap("SELECT * FROM cane.payments WHERE id=?", payment))
                .isEqualTo(before);
        assertThat(count("business_events")).isEqualTo(snapshot.events() + 1);
        assertThat(count("notification_outbox")).isEqualTo(snapshot.outbox());
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events WHERE payment_id=? AND kind='operation_noop'",
                        Long.class,
                        payment))
                .isEqualTo(1);
        error(
                post("admin/payments", command.deepCopy().put("amountVnd", firstOrder.total() + 1), key(), owner),
                409,
                "PAYMENT_CONFLICT");
        var altered = command.deepCopy();
        altered.withObject("bankReceipt").put("transferContent", "changed immutable memo");
        error(post("admin/payments", altered, key(), owner), 409, "PAYMENT_CONFLICT");
        error(
                post("admin/payments", command.deepCopy().put("note", "hash changed"), key, owner),
                409,
                "IDEMPOTENCY_MISMATCH");
        var list = send(
                "GET",
                "admin/payments?needsReview=true&orderId=" + firstOrder.id() + "&pageSize=1",
                null,
                null,
                owner,
                false);
        assertThat(list.statusCode()).isEqualTo(200);
        var page = body(list);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("reference").asString())
                .isEqualTo(command.path("reference").asString());
        assertThat(list.body()).doesNotContain("provider_ref", "tokenHash", "operation_result");
        assertThat(body(send(
                                "GET",
                                "admin/payments?orderId=" + firstOrder.id() + "&page=2&pageSize=1",
                                null,
                                null,
                                owner,
                                false))
                        .path("items")
                        .isEmpty())
                .isTrue();
        for (String query : List.of("page=0", "pageSize=101", "needsReview=yes", "page=1&page=2", "unknown=1"))
            error(send("GET", "admin/payments?" + query, null, null, owner, false), 422, "INVALID_INPUT");
    }

    @Test
    void existingUnknownBankReceiptIsAppliedByConfirmAndUnchangedEvidenceIsPreserved() throws Exception {
        var order = place("pickup", "bank_transfer");
        var command = confirm(order, "bank");
        UUID receipt = payment(ok(post("admin/payments", intake(command, null), key(), owner)));
        var immutable = sql.queryForMap(
                "SELECT provider_ref,bank_receipt,confirmed_at,confirmed_by,amount_vnd FROM cane.payments WHERE id=?",
                receipt);
        assertThat(payment(ok(post(confirmRoute(order), command, key(), owner))))
                .isEqualTo(receipt);
        assertOrder(order, "confirmed", "paid");
        assertThat(sql.queryForMap(
                        "SELECT provider_ref,bank_receipt,confirmed_at,confirmed_by,amount_vnd FROM cane.payments WHERE id=?",
                        receipt))
                .isEqualTo(immutable);
        assertThat(sql.queryForObject(
                        "SELECT reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND NOT needs_review AND order_link_verified FROM cane.payments WHERE id=?",
                        Boolean.class,
                        receipt))
                .isTrue();
        assertThat(notifications(receipt, "payment_received")).isEqualTo(1);
        assertThat(facts(receipt, "payment_received").path("orderLinkVerified").asBoolean())
                .isFalse();
        assertThat(facts(receipt, "payment_applied").path("orderLinkVerified").asBoolean())
                .isTrue();
    }

    @Test
    void resolveCanRelinkVerifiedBankWithWrongMemoButCannotMoveCashOrApplyPartialOrAlreadyPrincipal() throws Exception {
        var wrong = place("pickup", "cod");
        var target = place("pickup", "bank_transfer");
        var command = confirm(target, "bank");
        command.withObject("bankReceipt").put("transferContent", "Unrecognized original memo");
        UUID bank = payment(ok(post("admin/payments", intake(command, wrong.id()), key(), owner)));
        error(post(confirmRoute(target), command, key(), owner), 409, "PAYMENT_CONFLICT");
        ok(post(resolveRoute(bank), resolve("apply_to_order", target.id()), key(), owner));
        assertOrder(target, "confirmed", "paid");
        assertOrder(wrong, "confirmed", "unpaid");
        assertThat(sql.queryForObject("SELECT order_id FROM cane.payments WHERE id=?", UUID.class, bank))
                .isEqualTo(target.id());
        assertThat(facts(bank, "payment_applied").path("action").asString()).isEqualTo("apply_to_order");
        var extra = intake(confirm(target, "bank"), null);
        UUID other = payment(ok(post("admin/payments", extra, key(), owner)));
        error(post(resolveRoute(other), resolve("apply_to_order", target.id()), key(), owner), 409, "PAYMENT_CONFLICT");
        var cash = intake(confirm(wrong, "cash"), wrong.id()).put("note", "MOCK physical cash evidence");
        UUID cashId = payment(ok(post("admin/payments", cash, key(), owner)));
        error(post(resolveRoute(cashId), resolve("close_review", null), key(), owner), 409, "PAYMENT_CONFLICT");
        error(
                post(resolveRoute(cashId), resolve("apply_to_order", target.id()), key(), owner),
                409,
                "PAYMENT_CONFLICT");
        var partial = intake(confirm(wrong, "cash"), wrong.id())
                .put("amountVnd", wrong.total() - 1)
                .put("note", "MOCK partial cash evidence");
        UUID partialId = payment(ok(post("admin/payments", partial, key(), owner)));
        error(
                post(resolveRoute(partialId), resolve("apply_to_order", wrong.id()), key(), owner),
                409,
                "PAYMENT_CONFLICT");
        assertOrder(wrong, "confirmed", "unpaid");
    }

    @Test
    void closeReviewOnlyVerifiesLinkAndCanUnlinkBankWithoutChangingOrderMoney() throws Exception {
        var order = place("delivery", "cod");
        var foreignBank = bankEvidence(order, order.total()).put("reference", "unknown-" + key());
        UUID receipt = payment(ok(post("admin/payments", intake(foreignBank, null), key(), owner)));
        long stock = count("stock_movements");
        ok(post(resolveRoute(receipt), resolve("close_review", order.id()), key(), owner));
        assertOrder(order, "confirmed", "unpaid");
        assertThat(notifications(receipt, "payment_received")).isEqualTo(1);
        assertThat(facts(receipt, "payment_resolved").path("isPrincipal").asBoolean())
                .isFalse();
        ok(post(resolveRoute(receipt), resolve("close_review", null), key(), owner));
        assertThat(sql.queryForObject(
                        "SELECT order_id IS NULL AND NOT order_link_verified AND NOT needs_review FROM cane.payments WHERE id=?",
                        Boolean.class,
                        receipt))
                .isTrue();
        assertThat(count("stock_movements")).isEqualTo(stock);
        UUID payment = payment(ok(post(
                "admin/payments",
                intake(confirm(order, "cash"), order.id()).put("note", "MOCK verified receipt"),
                key(),
                owner)));
        ok(post(resolveRoute(payment), resolve("close_review", order.id()), key(), owner));
        assertOrder(order, "confirmed", "unpaid");
        assertThat(notifications(payment, "payment_received")).isZero();
    }

    @Test
    void lateCancelledCashRequiresOwnerResolveThenFullPrincipalRefundWithoutStockWrites() throws Exception {
        var order = place("delivery", "cod");
        cancelFixture(order);
        long stock = count("stock_movements");
        error(
                post(confirmRoute(order), confirm(order, "cash").put("expectedStatus", "cancelled"), key(), owner),
                409,
                "PAYMENT_CONFLICT");
        UUID receipt = payment(ok(post(
                "admin/payments",
                intake(confirm(order, "cash"), order.id()).put("note", "MOCK cash arrived after cancellation"),
                key(),
                owner)));
        ok(post(resolveRoute(receipt), resolve("apply_to_order", order.id()), key(), owner));
        assertOrder(order, "cancelled", "refund_pending");
        var command = refund("MOCK-refund-" + key());
        String key = key();
        var result = ok(post(refundRoute(receipt), command, key, owner));
        assertOrder(order, "cancelled", "refunded");
        assertThat(sql.queryForObject("SELECT amount_vnd FROM cane.payments WHERE id=?", Long.class, receipt))
                .isEqualTo(order.total());
        var counts = counts();
        assertThat(ok(post(refundRoute(receipt), command, key, owner)).path("result"))
                .isEqualTo(result.path("result"));
        assertThat(counts()).isEqualTo(counts);
        ok(post(refundRoute(receipt), command, key(), owner));
        assertThat(count("business_events")).isEqualTo(counts.events() + 1);
        assertThat(count("notification_outbox")).isEqualTo(counts.outbox());
        error(post(refundRoute(receipt), refund("different-evidence"), key(), owner), 409, "PAYMENT_CONFLICT");
        error(post(resolveRoute(receipt), resolve("close_review", order.id()), key(), owner), 409, "PAYMENT_CONFLICT");
        assertThat(notifications(receipt, "refunded")).isEqualTo(1);
        assertThat(count("stock_movements")).isEqualTo(stock);
    }

    @Test
    void extraRefundsOnCompletedAndUnknownReceiptsDoNotChangePrincipalOrLeakToUnverifiedCustomer() throws Exception {
        var order = place("delivery", "cod");
        UUID principal = payment(ok(post(confirmRoute(order), confirm(order, "cash"), key(), owner)));
        sql.update("UPDATE cane.orders SET status='completed' WHERE id=?", order.id());
        error(post(refundRoute(principal), refund("not-allowed-active"), key(), owner), 409, "PAYMENT_CONFLICT");
        long stock = count("stock_movements");
        for (int i = 0; i < 2; i++) {
            UUID extra = payment(ok(post(
                    "admin/payments",
                    intake(confirm(order, "cash"), order.id())
                            .put("amountVnd", 10000 + i)
                            .put("note", "MOCK extra cash evidence"),
                    key(),
                    owner)));
            ok(post(refundRoute(extra), refund("MOCK-extra-refund-" + key()), key(), owner));
            assertOrder(order, "completed", "paid");
            assertThat(notifications(extra, "refunded")).isEqualTo(1);
            assertThat(facts(extra, "payment_refunded").path("isPrincipal").asBoolean())
                    .isFalse();
        }
        for (UUID link : new UUID[] {null, order.id()}) {
            UUID unknown = payment(ok(post("admin/payments", intake(bankEvidence(order, 20000), link), key(), owner)));
            ok(post(refundRoute(unknown), refund("MOCK-unverified-refund-" + key()), key(), owner));
            assertThat(notifications(unknown, "refunded")).isZero();
            assertThat(notifications(unknown, "owner_alert")).isGreaterThanOrEqualTo(2);
        }
        assertThat(sql.queryForObject("SELECT status FROM cane.payments WHERE id=?", String.class, principal))
                .isEqualTo("succeeded");
        assertThat(count("stock_movements")).isEqualTo(stock);
    }

    @Test
    void concurrentSameKeySameReferenceAndCompetingPrincipalCommitOnce() throws Exception {
        var order = place("delivery", "bank_transfer");
        var command = confirm(order, "bank");
        String key = key();
        var responses = race(
                () -> post(confirmRoute(order), command, key, owner),
                () -> post(confirmRoute(order), command, key, owner));
        assertThat(responses.stream().map(HttpResponse::statusCode)).contains(200);
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isIn(200, 409));
        for (var response : responses) if (response.statusCode() == 409) error(response, 409, "REQUEST_IN_PROGRESS");
        assertThat(ok(post(confirmRoute(order), command, key, owner))
                        .path("replayed")
                        .asBoolean())
                .isTrue();
        assertThat(principals(order)).isEqualTo(1);
        var unknown = intake(bankEvidence(order, 25000), null);
        responses = race(
                () -> post("admin/payments", unknown, key(), owner),
                () -> post("admin/payments", unknown, key(), owner));
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(payment(body(responses.get(0)))).isEqualTo(payment(body(responses.get(1))));
        var cod = place("delivery", "cod");
        responses = race(
                () -> post(confirmRoute(cod), confirm(cod, "cash"), key(), owner),
                () -> post(confirmRoute(cod), confirm(cod, "cash"), key(), operator));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
        assertThat(principals(cod)).isEqualTo(1);
    }

    @Test
    void concurrentResolveRefundAndRelinkKeepOneStableReceiptAndCorrectRecipient() throws Exception {
        var first = place("pickup", "bank_transfer");
        var second = place("pickup", "bank_transfer");
        UUID receipt =
                payment(ok(post("admin/payments", intake(bankEvidence(first, first.total()), null), key(), owner)));
        var responses = race(
                () -> post(resolveRoute(receipt), resolve("apply_to_order", first.id()), key(), owner),
                () -> post(resolveRoute(receipt), resolve("apply_to_order", second.id()), key(), owner));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
        assertThat(principals(first) + principals(second)).isEqualTo(1);
        var target = sql.queryForObject("SELECT order_id FROM cane.payments WHERE id=?", UUID.class, receipt);
        assertThat(sql.queryForObject(
                        "SELECT order_id FROM cane.notification_outbox WHERE payment_id=? AND kind='payment_received'",
                        UUID.class,
                        receipt))
                .isEqualTo(target);
        UUID extra = payment(ok(post("admin/payments", intake(bankEvidence(first, 10000), null), key(), owner)));
        responses = race(
                () -> post(resolveRoute(extra), resolve("close_review", first.id()), key(), owner),
                () -> post(refundRoute(extra), refund("MOCK-race-refund"), key(), owner));
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isIn(200, 409));
        assertThat(sql.queryForObject("SELECT status FROM cane.payments WHERE id=?", String.class, extra))
                .isEqualTo("refunded");
        var facts = facts(extra, "payment_refunded");
        long emails = notifications(extra, "refunded");
        assertThat(emails).isEqualTo(facts.path("orderLinkVerified").asBoolean() ? 1 : 0);
    }

    @Test
    void faultAfterReceiptAndOrderBeforeOutboxRollsEverythingBackAndSameKeyCanRetry() throws Exception {
        var order = place("delivery", "bank_transfer");
        var command = confirm(order, "bank");
        String key = key();
        var before = counts();
        sql.execute(
                "ALTER TABLE cane.notification_outbox ADD CONSTRAINT fixture_reject_payment CHECK (kind <> 'payment_received') NOT VALID");
        try {
            var failed = post(confirmRoute(order), command, key, owner);
            error(failed, 503, "SERVICE_UNAVAILABLE");
            assertThat(failed.body())
                    .doesNotContain(
                            "fixture_reject_payment",
                            "INSERT",
                            command.path("reference").asString());
            assertOrder(order, "awaiting_payment", "unpaid");
            assertThat(counts()).isEqualTo(before);
        } finally {
            sql.execute("ALTER TABLE cane.notification_outbox DROP CONSTRAINT fixture_reject_payment");
        }
        ok(post(confirmRoute(order), command, key, owner));
        assertOrder(order, "confirmed", "paid");
    }

    @Test
    void pickupCashContractJoinsCompletionTransactionAndRollsBackAsAUnit() throws Exception {
        var order = place("pickup", "cod");
        sql.update("UPDATE cane.orders SET status='ready_for_pickup' WHERE id=?", order.id());
        var transactions = context.getBean(Transactions.class);
        var service = context.getBean(OrderMoneyService.class);
        var principal = context.getBean(StaffSessionService.class)
                .authenticate(owner.token().value())
                .orElseThrow();
        var before = counts();
        assertThatThrownBy(() -> transactions.write(() -> {
                    service.collectAtPickup(principal, order.id());
                    throw new IllegalStateException("fixture completion failure");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertOrder(order, "ready_for_pickup", "unpaid");
        assertThat(counts()).isEqualTo(before);
        transactions.write(() -> {
            var receipt = service.collectAtPickup(principal, order.id());
            // WP06 owns this transition/event/outbox. The test harness adds no product route.
            context.getBean(JdbcTemplate.class)
                    .update("UPDATE cane.orders SET status='completed' WHERE id=?", order.id());
            UUID event = UUID.randomUUID();
            context.getBean(BusinessEventWriter.class)
                    .append(new BusinessEvent(
                            event,
                            order.id(),
                            receipt.id(),
                            "order_completed",
                            "ready_for_pickup",
                            "completed",
                            principal.actor().id().toString(),
                            "Order completed",
                            null,
                            JSON.createObjectNode()
                                    .put("orderId", order.id().toString())
                                    .put("paymentId", receipt.id().toString())
                                    .put("paymentStatus", "paid"),
                            null));
            context.getBean(OutboxWriter.class)
                    .enqueue(
                            event,
                            List.of(new Notification(
                                    UUID.randomUUID(),
                                    Notification.Kind.COMPLETED,
                                    order.id(),
                                    receipt.id(),
                                    Notification.Channel.EMAIL,
                                    "wp04@example.invalid",
                                    Notification.Audience.CUSTOMER)));
            return null;
        });
        assertOrder(order, "completed", "paid");
        assertThat(principals(order)).isEqualTo(1);
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.business_events WHERE order_id=? AND kind='payment_received'",
                        Long.class,
                        order.id()))
                .isZero();
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM cane.notification_outbox WHERE order_id=? AND kind='completed'",
                        Long.class,
                        order.id()))
                .isEqualTo(1);
    }

    @Test
    void inconsistentPrincipalFailsClosedAndStrictHttpInputsNeverMutateMoney() throws Exception {
        var order = place("delivery", "cod");
        var command = confirm(order, "cash");
        var before = counts();
        error(post(confirmRoute(order), command.deepCopy().put("amountVnd", 1.25), key(), owner), 400, "INVALID_INPUT");
        error(
                post(
                        confirmRoute(order),
                        command.deepCopy().put("actor", staff(owner).toString()),
                        key(),
                        owner),
                400,
                "INVALID_INPUT");
        error(post(confirmRoute(order), command, null, owner), 400, "INVALID_INPUT");
        error(
                post("admin/payments", intake(command, null).put("note", "MOCK cash"), key(), owner),
                422,
                "INVALID_INPUT");
        assertThat(counts()).isEqualTo(before);
        // SQL permits the cross-row inconsistency; application must fail rather than invent a repair.
        sql.update(
                "INSERT INTO cane.payments(order_id,method,amount_vnd,applied_to_order,order_link_verified,confirmed_by) VALUES (?,'cash',?,true,true,?)",
                order.id(),
                order.total() - 1,
                staff(owner).toString());
        var corrupted = counts();
        error(post(confirmRoute(order), command, key(), owner), 503, "SERVICE_UNAVAILABLE");
        assertOrder(order, "confirmed", "unpaid");
        assertThat(counts()).isEqualTo(corrupted);
    }

    private Order place(String fulfillment, String method) throws Exception {
        var command =
                JSON.createObjectNode().put("fulfillmentMethod", fulfillment).put("paymentMethod", method);
        String sku = sql.queryForObject(
                "SELECT sku FROM cane.products WHERE delivery_scope='city' AND is_active AND price_vnd > 1 ORDER BY sku LIMIT 1",
                String.class);
        command.putArray("items").addObject().put("sku", sku).put("quantity", 1);
        if (fulfillment.equals("pickup")) command.putNull("address");
        else
            command.putObject("address")
                    .put(
                            "wardCode",
                            setting("delivery_zones").get(0).path("wardCode").asString())
                    .put("line1", "MOCK WP04 address");
        var quote = send("POST", "checkout/quote", command, null, null, false);
        assertThat(quote.statusCode()).isEqualTo(200);
        long total = body(quote).path("totalVnd").asLong();
        command.putObject("recipient")
                .put("name", "MOCK WP04 customer")
                .put("phone", "0338825982")
                .put("email", "wp04@example.invalid");
        command.putNull("note").put("expectedTotalVnd", total);
        String key = key();
        var placed = send("POST", "orders", command, key, null, false);
        assertThat(placed.statusCode()).isEqualTo(201);
        String number = body(placed).path("order").path("orderNumber").asString();
        UUID id = sql.queryForObject("SELECT id FROM cane.orders WHERE order_number=?", UUID.class, number);
        return new Order(id, number, total, command, key);
    }

    private ObjectNode confirm(Order order, String method) {
        if (method.equals("bank")) return bankEvidence(order, order.total()).put("expectedStatus", "awaiting_payment");
        return JSON.createObjectNode()
                .put("expectedStatus", "confirmed")
                .put("method", "cash")
                .put("amountVnd", order.total())
                .putNull("reference")
                .putNull("bankReceipt");
    }

    private ObjectNode bankEvidence(Order order, long amount) {
        var command = JSON.createObjectNode()
                .put("method", "bank")
                .put("amountVnd", amount)
                .put("reference", "MOCK-bank-" + key());
        var bank =
                sql.queryForObject("SELECT bank_snapshot::text FROM cane.orders WHERE id=?", String.class, order.id());
        var snapshot = bank == null ? setting("bank_details") : JSON.readTree(bank);
        command.putObject("bankReceipt")
                .put("bankCode", snapshot.path("bankCode").asString())
                .put("accountNo", snapshot.path("accountNo").asString())
                .put("transferContent", "MOCK transfer " + order.number())
                .put("transactionAt", "2026-11-15T01:30:20Z");
        return command;
    }

    private static ObjectNode intake(ObjectNode confirm, UUID order) {
        var body = confirm.deepCopy();
        body.remove("expectedStatus");
        if (order == null) body.putNull("orderId");
        else body.put("orderId", order.toString());
        return body;
    }

    private static ObjectNode resolve(String action, UUID order) {
        var body = JSON.createObjectNode()
                .put("action", action)
                .put("expectedPaymentStatus", "succeeded")
                .put("note", "MOCK Owner verified original evidence");
        if (order == null) body.putNull("orderId");
        else body.put("orderId", order.toString());
        return body;
    }

    private static ObjectNode refund(String reference) {
        return JSON.createObjectNode().put("expectedPaymentStatus", "succeeded").put("reference", reference);
    }

    private static String confirmRoute(Order o) {
        return "admin/orders/" + o.id() + "/confirm-payment";
    }

    private static String resolveRoute(UUID id) {
        return "admin/payments/" + id + "/resolve";
    }

    private static String refundRoute(UUID id) {
        return "admin/payments/" + id + "/confirm-refund";
    }

    private static UUID payment(JsonNode response) {
        return UUID.fromString(response.path("result").path("paymentId").asString());
    }

    private void assertOrder(Order order, String status, String payment) {
        assertThat(sql.queryForMap("SELECT status,payment_status FROM cane.orders WHERE id=?", order.id()))
                .isEqualTo(Map.of("status", status, "payment_status", payment));
    }

    private long principals(Order order) {
        return sql.queryForObject(
                "SELECT count(*) FROM cane.payments WHERE order_id=? AND applied_to_order", Long.class, order.id());
    }

    private long notifications(UUID payment, String kind) {
        return sql.queryForObject(
                "SELECT count(*) FROM cane.notification_outbox WHERE payment_id=? AND kind=?",
                Long.class,
                payment,
                kind);
    }

    private JsonNode facts(UUID payment, String kind) {
        return JSON.readTree(sql.queryForObject(
                "SELECT data::text FROM cane.business_events WHERE payment_id=? AND kind=? ORDER BY created_at LIMIT 1",
                String.class,
                payment,
                kind));
    }

    private void cancelFixture(Order o) {
        sql.update(
                "UPDATE cane.orders SET status='cancelled',payment_due_at=NULL,cancel_kind='before_handover',cancel_reason='MOCK WP06 fixture',cancelled_at=statement_timestamp() WHERE id=?",
                o.id());
    }

    private static long count(String table) {
        return sql.queryForObject("SELECT count(*) FROM cane." + table, Long.class);
    }

    private static Counts counts() {
        return new Counts(
                count("orders"),
                count("payments"),
                count("business_events"),
                count("notification_outbox"),
                count("stock_movements"));
    }

    private void setting(String key, JsonNode value) {
        sql.update("UPDATE cane.settings SET value=CAST(? AS jsonb) WHERE key=?", value.toString(), key);
    }

    private JsonNode setting(String key) {
        return JSON.readTree(
                sql.queryForObject("SELECT value::text FROM cane.settings WHERE key=?", String.class, key));
    }

    private StaffSessionService.IssuedSession issue(String role) {
        UUID id = sql.queryForObject(
                "INSERT INTO cane.staff_users(username,password_hash,role) VALUES (?,?,?) RETURNING id",
                UUID.class,
                "fixture-" + key(),
                "fixture-not-a-login-hash",
                role);
        return context.getBean(Transactions.class)
                .write(() -> context.getBean(StaffSessionService.class).issue(id, 1, null));
    }

    @Test
    void ownerResendKeepsExpiryRotateRevokesAndReplayDoesNotQueueAgain() throws Exception {
        var order = place("delivery", "cod");
        String route = "admin/orders/" + order.id() + "/resend-link";
        var links = context.getBean(vn.cane.orders.application.OrderLinkService.class);
        String old = links.mintCurrent(order.id()).value();
        long before = sql.queryForObject("SELECT count(*) FROM cane.notification_outbox", Long.class);
        String key = key();
        var command = JSON.createObjectNode().put("rotate", false);
        var sent = ok(post(route, command, key, owner));
        assertThat(sent.path("result").path("linkVersion").asLong()).isEqualTo(1);
        assertThat(sent.toString()).doesNotContain(old, "viewUrl", "#t=");
        assertThat(links.mintCurrent(order.id()).value()).isEqualTo(old);
        var rotated = ok(post(route, JSON.createObjectNode().put("rotate", true), key(), owner));
        assertThat(rotated.path("result").path("linkVersion").asLong()).isEqualTo(2);
        assertThatThrownBy(() -> links.authorizeRead(old))
                .isInstanceOf(vn.cane.orders.domain.InvalidOrderLinkException.class);
        var replay = ok(post(route, command, key, owner));
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.get("result")).isEqualTo(sent.get("result"));
        assertThat(sql.queryForObject("SELECT count(*) FROM cane.notification_outbox", Long.class))
                .isEqualTo(before + 2);
        error(post(route, JSON.createObjectNode().put("rotate", true), key, owner), 409, "IDEMPOTENCY_MISMATCH");
        error(post(route, command, key, operator), 403, "FORBIDDEN");
        error(send("POST", route, command, key(), owner, false), 403, "FORBIDDEN");
    }

    @Test
    void expiredResendConflictsButRotateIsAllowedAndOverflowRollsBack() throws Exception {
        var order = place("pickup", "cod");
        String route = "admin/orders/" + order.id() + "/resend-link";
        sql.update(
                "UPDATE cane.orders SET created_at=now()-interval '2 days',link_expires_at=date_trunc('milliseconds',now()-interval '1 day') WHERE id=?",
                order.id());
        error(post(route, JSON.createObjectNode().put("rotate", false), key(), owner), 409, "STATE_CONFLICT");
        assertThat(ok(post(route, JSON.createObjectNode().put("rotate", true), key(), owner))
                        .path("result")
                        .path("queued")
                        .asBoolean())
                .isTrue();
        sql.update("UPDATE cane.orders SET link_version=? WHERE id=?", Long.MAX_VALUE, order.id());
        long before = sql.queryForObject("SELECT count(*) FROM cane.business_events", Long.class);
        error(post(route, JSON.createObjectNode().put("rotate", true), key(), owner), 409, "STATE_CONFLICT");
        assertThat(sql.queryForObject("SELECT count(*) FROM cane.business_events", Long.class))
                .isEqualTo(before);
    }

    @Test
    void cancelRequestAndRotateSerializeOnTheSameOrderWithoutPartialHistory() throws Exception {
        var order = place("pickup", "cod");
        String token = context.getBean(vn.cane.orders.application.OrderLinkService.class)
                .mintCurrent(order.id())
                .value();
        var request = HttpRequest.newBuilder(URI.create(base + "guest/order/cancel-request"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        var results = race(
                () -> http.send(request, HttpResponse.BodyHandlers.ofString()),
                () -> post(
                        "admin/orders/" + order.id() + "/resend-link",
                        JSON.createObjectNode().put("rotate", true),
                        key(),
                        owner));
        assertThat(results.get(1).statusCode()).isEqualTo(200);
        assertThat(results.get(0).statusCode()).isIn(200, 404);
        long events = sql.queryForObject(
                "SELECT count(*) FROM cane.business_events WHERE order_id=? AND kind='cancel_requested'",
                Long.class,
                order.id());
        boolean requested = Boolean.TRUE.equals(sql.queryForObject(
                "SELECT cancel_requested_at IS NOT NULL FROM cane.orders WHERE id=?", Boolean.class, order.id()));
        assertThat(events).isEqualTo(requested ? 1 : 0);
        assertThat(requested).isEqualTo(results.get(0).statusCode() == 200);
        assertThat(sql.queryForObject(
                        "SELECT status||'/'||payment_status FROM cane.orders WHERE id=?", String.class, order.id()))
                .isEqualTo("confirmed/unpaid");
    }

    private UUID staff(StaffSessionService.IssuedSession session) {
        return context.getBean(StaffSessionService.class)
                .authenticate(session.token().value())
                .orElseThrow()
                .actor()
                .id();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode resource(String path) throws Exception {
        try (var stream = ManualPaymentsIT.class.getResourceAsStream(path)) {
            return JSON.readTree(stream);
        }
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("HTTP body: %s", response.body()).isEqualTo(200);
        return body(response);
    }

    private static void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as("HTTP body: %s", response.body()).isEqualTo(status);
        assertThat(body(response).path("error").path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
    }

    private HttpResponse<String> post(
            String route, JsonNode body, String key, StaffSessionService.IssuedSession session) throws Exception {
        return send("POST", route, body, key, session, true);
    }

    private HttpResponse<String> send(
            String method,
            String route,
            JsonNode body,
            String key,
            StaffSessionService.IssuedSession session,
            boolean withCsrf)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(20));
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        String cookie = session == null
                ? ""
                : SessionCookies.NAME + "=" + session.token().value();
        if (withCsrf) {
            request.header("Origin", "https://cane.example.test").header(StaffCsrf.HEADER, csrf);
            cookie += (cookie.isEmpty() ? "" : "; ") + StaffCsrf.COOKIE + "=" + csrf;
        }
        if (!cookie.isEmpty()) request.header("Cookie", cookie);
        return http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private List<HttpResponse<String>> race(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
            throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var futures = List.of(first, second).stream()
                    .map(task -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return task.call();
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    futures.get(0).get(25, TimeUnit.SECONDS), futures.get(1).get(25, TimeUnit.SECONDS));
        }
    }

    private record Order(UUID id, String number, long total, ObjectNode command, String key) {
        @Override
        public String toString() {
            return "FixtureOrder[redacted]";
        }
    }

    private record Counts(long orders, long payments, long events, long outbox, long movements) {}
}
