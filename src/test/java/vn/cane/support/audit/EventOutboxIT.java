package vn.cane.support.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.bootstrap.TransactionConfiguration;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.idempotency.OperationReceipts;
import vn.cane.support.idempotency.OperationStamp;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.Notification.Audience;
import vn.cane.support.outbox.Notification.Channel;
import vn.cane.support.outbox.Notification.Kind;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.SqlFailures;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputNormalizer;

/** Real runtime grants/triggers; component harness, not future order/payment use cases. */
@Testcontainers
class EventOutboxIT {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_ORDER = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final String KEY = "fixture-operation-0001";
    private static final String CUSTOMER = "customer@example.invalid";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private final JsonMapper json = JsonMapper.builder().build();
    private AnnotationConfigApplicationContext context;
    private HikariDataSource source;
    private ValidatorFactory validators;
    private JdbcTemplate owner;
    private JdbcTemplate jdbc;
    private Transactions transactions;
    private BusinessEventWriter events;
    private OperationReceipts receipts;
    private OutboxWriter outbox;
    private String url;

    @BeforeEach
    void prepare() throws Exception {
        String database = "cane_events_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER cane_migrator");
        }
        url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        owner = new JdbcTemplate(
                new DriverManagerDataSource(url, "cane_migrator", TestInfrastructure.MIGRATION_PASSWORD));
        owner.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO cane_api, cane_worker");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        TestInfrastructure.prepareCore(POSTGRES, database);
        insertOrder(ORDER, "CANEAUDI01", CUSTOMER);
        insertOrder(OTHER_ORDER, "CANEAUDI02", "other@example.invalid");
        owner.update(
                "INSERT INTO cane.products(sku,name,unit_label,price_vnd,delivery_scope) VALUES ('AUDIT-A','Fixture','unit',25000,'city')");
        owner.update(
                "UPDATE cane.settings SET value=CAST(? AS jsonb) WHERE key='owner_notify'",
                "{\"email\":\"customer@example.invalid\",\"telegramChatId\":\"fixture-chat\"}");
        source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername("cane_api");
        source.setPassword(TestInfrastructure.API_PASSWORD);
        source.setMaximumPoolSize(4);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(3000);
        source.addDataSourceProperty(
                "options", "-c search_path=cane,pg_catalog -c statement_timeout=5000 -c lock_timeout=2000");
        source.addDataSourceProperty("connectTimeout", "3");
        source.addDataSourceProperty("socketTimeout", "6");
        jdbc = new JdbcTemplate(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(TransactionConfiguration.class, Transactions.class);
        context.refresh();
        transactions = context.getBean(Transactions.class);
        var named = new NamedParameterJdbcTemplate(jdbc);
        validators = Validation.buildDefaultValidatorFactory();
        events = new BusinessEventWriter(named, json);
        receipts = new OperationReceipts(named, json);
        outbox = new OutboxWriter(named, json, new InputNormalizer(validators.getValidator()));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (source != null) source.close();
        if (validators != null) validators.close();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
    }

    @Test
    void writersRequireTheCallersWriteTransaction() {
        var event = event("order_placed", ORDER, null, json.createObjectNode(), null);
        assertThatThrownBy(() -> events.append(event)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.enqueue(event.id(), List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.readSnapshot(() -> events.append(event)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.readSnapshot(() -> outbox.enqueue(event.id(), List.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("business_events")).isZero();
    }

    @Test
    void eventReceiptAndQueueCommitTogetherWithMinimalDefaults() {
        var event = event("order_placed", ORDER, null, json.createObjectNode().put("status", "confirmed"), null);
        var result = json.createObjectNode()
                .put("orderId", ORDER.toString())
                .put("eventId", event.id().toString());
        transactions.write(() -> {
            var recorded = events.append(event, new OperationStamp(KEY, new byte[32], result));
            assertThat(recorded.id()).isEqualTo(event.id());
            assertThat(recorded.createdAt()).isNotNull();
            assertThat(outbox.enqueue(
                            event.id(),
                            List.of(notification(
                                    Kind.ORDER_PLACED, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER))))
                    .hasSize(1);
            assertThat(receipts.find(ACTOR, KEY))
                    .hasValueSatisfying(stored -> assertThat(stored.result()).isEqualTo(result));
            assertThat(count("business_events")).isZero();
            assertThat(count("notification_outbox")).isZero();
            return null;
        });
        assertThat(receipts.find(UUID.randomUUID(), KEY)).isEmpty();
        var row = owner.queryForMap("SELECT status,attempts,locked_at,sent_at,recipient FROM cane.notification_outbox");
        assertThat(row)
                .containsEntry("status", "pending")
                .containsEntry("attempts", 0)
                .containsEntry("locked_at", null)
                .containsEntry("sent_at", null)
                .containsEntry("recipient", CUSTOMER);
        assertThat(owner.queryForObject(
                        "SELECT e.created_at=q.created_at FROM cane.business_events e JOIN cane.notification_outbox q ON q.event_id=e.id",
                        Boolean.class))
                .isTrue();
    }

    @Test
    void customerWinsOwnerCollisionAndOnlyExactQueueConflictsAreIgnored() {
        var event = event("order_placed", ORDER, null, json.createObjectNode(), null);
        var customer = notification(
                Kind.ORDER_PLACED, ORDER, null, Channel.EMAIL, " CUSTOMER@EXAMPLE.INVALID ", Audience.CUSTOMER);
        var ownerEmail = notification(Kind.OWNER_NEW_ORDER, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.OWNER);
        var telegram =
                notification(Kind.OWNER_NEW_ORDER, ORDER, null, Channel.TELEGRAM, " fixture-chat ", Audience.OWNER);
        transactions.write(() -> {
            events.append(event);
            assertThat(outbox.enqueue(event.id(), List.of(ownerEmail, telegram, customer)))
                    .hasSize(2);
            assertThat(outbox.enqueue(event.id(), List.of(ownerEmail, customer, telegram)))
                    .isEmpty();
            events.append(event("order_prepared", ORDER, null, json.createObjectNode(), null));
            return null;
        });
        assertThat(owner.queryForList("SELECT kind FROM cane.notification_outbox WHERE channel='email'", String.class))
                .containsExactly("order_placed");
        assertThat(count("business_events")).isEqualTo(2);
        assertThat(count("notification_outbox")).isEqualTo(2);
    }

    @Test
    void allCandidatesAreValidatedBeforeAnyQueueInsert() {
        var event = event("order_placed", ORDER, null, json.createObjectNode(), null);
        transactions.write(() -> events.append(event));
        transactions.write(() -> {
            assertThatThrownBy(() -> outbox.enqueue(
                            event.id(),
                            List.of(
                                    notification(
                                            Kind.ORDER_PLACED, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER),
                                    notification(
                                            Kind.OWNER_NEW_ORDER,
                                            ORDER,
                                            null,
                                            Channel.TELEGRAM,
                                            "arbitrary-chat",
                                            Audience.OWNER))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cane.notification_outbox", Long.class))
                    .isZero();
            return null;
        });
    }

    @Test
    void arbitraryDestinationsKindsAndMissingSourcesAreRejected() {
        var event = event("order_placed", ORDER, null, json.createObjectNode(), null);
        transactions.write(() -> events.append(event));
        for (var invalid : List.of(
                notification(
                        Kind.ORDER_PLACED, ORDER, null, Channel.EMAIL, "attacker@example.invalid", Audience.CUSTOMER),
                notification(Kind.ORDER_PLACED, ORDER, null, Channel.TELEGRAM, "fixture-chat", Audience.CUSTOMER),
                notification(Kind.SHIPPED, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER),
                notification(Kind.ORDER_PLACED, OTHER_ORDER, null, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER),
                notification(
                        Kind.OWNER_NEW_ORDER,
                        ORDER,
                        null,
                        Channel.EMAIL,
                        "attacker@example.invalid",
                        Audience.OWNER))) {
            assertThatThrownBy(() -> transactions.write(() -> outbox.enqueue(event.id(), List.of(invalid))))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> transactions.write(() -> outbox.enqueue(
                        UUID.randomUUID(),
                        List.of(notification(
                                Kind.ORDER_PLACED, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER)))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("notification_outbox")).isZero();
    }

    @Test
    void paymentFactsMustMatchItsSourceAtEventCreation() {
        UUID payment = insertBank(ORDER, true);
        var facts = paymentFacts(payment, ORDER, true);
        for (var wrong : List.of(
                facts.deepCopy().put("amountVnd", 1),
                facts.deepCopy().put("method", "cash"),
                facts.deepCopy().put("reference", "wrong"),
                facts.deepCopy().put("isPrincipal", true),
                facts.deepCopy().put("orderLinkVerified", false),
                facts.deepCopy().put("orderId", OTHER_ORDER.toString()))) {
            assertThatThrownBy(() -> transactions.write(
                            () -> events.append(event("payment_received", ORDER, payment, wrong, null))))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> transactions.write(() ->
                        events.append(event("payment_received", OTHER_ORDER, payment, json.createObjectNode(), null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.write(() -> events.append(
                        event("payment_received", ORDER, UUID.randomUUID(), json.createObjectNode(), null))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("business_events")).isZero();
    }

    @Test
    void oldVerifiedPaymentEventKeepsItsOrderEvenAfterReceiptIsRelinked() {
        UUID payment = insertBank(ORDER, true);
        var event = event("payment_received", ORDER, payment, paymentFacts(payment, ORDER, true), null);
        transactions.write(() -> events.append(event));
        owner.update("UPDATE cane.payments SET order_id=? WHERE id=?", OTHER_ORDER, payment);
        transactions.write(() -> outbox.enqueue(
                event.id(),
                List.of(notification(
                        Kind.PAYMENT_RECEIVED, ORDER, payment, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER))));
        assertThat(owner.queryForObject("SELECT order_id FROM cane.notification_outbox", UUID.class))
                .isEqualTo(ORDER);
        assertThat(owner.queryForObject("SELECT order_id FROM cane.payments WHERE id=?", UUID.class, payment))
                .isEqualTo(OTHER_ORDER);
        assertThat(owner.queryForObject("SELECT data->>'orderId' FROM cane.business_events", String.class))
                .isEqualTo(ORDER.toString());
    }

    @Test
    void unverifiedAndUnlinkedMoneyOnlyQueuesAnOwnerAlert() {
        UUID payment = insertBank(ORDER, false);
        var event = event("payment_received", ORDER, payment, paymentFacts(payment, ORDER, false), null);
        transactions.write(() -> events.append(event));
        assertThatThrownBy(() -> transactions.write(() -> outbox.enqueue(
                        event.id(),
                        List.of(notification(
                                Kind.PAYMENT_RECEIVED, ORDER, payment, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER)))))
                .isInstanceOf(IllegalStateException.class);
        transactions.write(() -> outbox.enqueue(
                event.id(),
                List.of(notification(Kind.OWNER_ALERT, ORDER, payment, Channel.EMAIL, CUSTOMER, Audience.OWNER))));
        UUID unknown = insertBank(null, false);
        var unknownEvent = event("payment_received", null, unknown, paymentFacts(unknown, null, false), null);
        transactions.write(() -> {
            events.append(unknownEvent);
            return outbox.enqueue(
                    unknownEvent.id(),
                    List.of(notification(
                            Kind.OWNER_ALERT, null, unknown, Channel.TELEGRAM, "fixture-chat", Audience.OWNER)));
        });
        assertThat(count("notification_outbox")).isEqualTo(2);
        assertThat(owner.queryForList("SELECT DISTINCT kind FROM cane.notification_outbox", String.class))
                .containsExactly("owner_alert");
    }

    @Test
    void cashAtHandoverUsesCompletedNotificationWithoutASeparatePaymentEmail() {
        UUID payment = UUID.randomUUID();
        owner.update(
                "INSERT INTO cane.payments(id,order_id,method,amount_vnd,order_link_verified,confirmed_by) VALUES (?,?,'cash',40000,true,?)",
                payment,
                ORDER,
                ACTOR.toString());
        var completed =
                event("order_completed", ORDER, payment, json.createObjectNode().put("paymentStatus", "paid"), null);
        transactions.write(() -> {
            events.append(completed);
            return outbox.enqueue(
                    completed.id(),
                    List.of(notification(Kind.COMPLETED, ORDER, payment, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER)));
        });
        var cash = event(
                "payment_received",
                ORDER,
                payment,
                json.createObjectNode().put("method", "cash").put("orderLinkVerified", true),
                null);
        transactions.write(() -> events.append(cash));
        assertThatThrownBy(() -> transactions.write(() -> outbox.enqueue(
                        cash.id(),
                        List.of(notification(
                                Kind.PAYMENT_RECEIVED, ORDER, payment, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER)))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("notification_outbox")).isEqualTo(1);
    }

    @Test
    void ownerRecipientIsFixedAtEnqueueAndChangesOnlyApplyToNewRows() {
        var first = event("owner_alert", ORDER, null, json.createObjectNode(), null);
        transactions.write(() -> {
            events.append(first);
            return outbox.enqueue(
                    first.id(),
                    List.of(notification(Kind.OWNER_ALERT, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.OWNER)));
        });
        owner.update(
                "UPDATE cane.settings SET value='{\"email\":\"new-owner@example.invalid\"}'::jsonb WHERE key='owner_notify'");
        var second = event("owner_alert", ORDER, null, json.createObjectNode(), null);
        transactions.write(() -> {
            events.append(second);
            return outbox.enqueue(
                    second.id(),
                    List.of(notification(
                            Kind.OWNER_ALERT,
                            ORDER,
                            null,
                            Channel.EMAIL,
                            "new-owner@example.invalid",
                            Audience.OWNER)));
        });
        assertThat(owner.queryForObject(
                        "SELECT recipient FROM cane.notification_outbox WHERE event_id=?", String.class, first.id()))
                .isEqualTo(CUSTOMER);
        assertThat(owner.queryForObject(
                        "SELECT recipient FROM cane.notification_outbox WHERE event_id=?", String.class, second.id()))
                .isEqualTo("new-owner@example.invalid");
    }

    @Test
    void newNoopHasAReceiptButNoNotificationAndSystemCannotOwnAnAdminReceipt() {
        var event = event("operation_noop", ORDER, null, json.createObjectNode(), null);
        transactions.write(() -> events.append(
                event,
                new OperationStamp(KEY, new byte[32], json.createObjectNode().put("orderId", ORDER.toString()))));
        assertThat(receipts.find(ACTOR, KEY)).isPresent();
        assertThatThrownBy(() -> transactions.write(() -> outbox.enqueue(
                        event.id(),
                        List.of(notification(Kind.OWNER_ALERT, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.OWNER)))))
                .isInstanceOf(IllegalStateException.class);
        var system = new BusinessEvent(
                UUID.randomUUID(),
                null,
                null,
                "owner_alert",
                null,
                null,
                "system",
                null,
                null,
                json.createObjectNode(),
                null);
        assertThatThrownBy(() -> transactions.write(
                        () -> events.append(system, new OperationStamp(KEY, new byte[32], json.createObjectNode()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("business_events")).isEqualTo(1);
        assertThat(count("notification_outbox")).isZero();
    }

    @Test
    void uniqueSourcesOperationKeysAndQueuePrimaryKeysRemainDistinctFailures() {
        var first = event("order_placed", ORDER, null, json.createObjectNode(), "fixture:source");
        transactions.write(() -> events.append(first, new OperationStamp(KEY, new byte[32], json.createObjectNode())));
        Throwable sourceConflict = catchThrowable(() -> transactions.write(
                () -> events.append(event("order_placed", ORDER, null, json.createObjectNode(), "fixture:source"))));
        assertThat(SqlFailures.uniqueConstraint(sourceConflict)).contains("business_events_source_unique");
        Throwable operationConflict = catchThrowable(() -> transactions.write(() -> events.append(
                event("order_placed", ORDER, null, json.createObjectNode(), null),
                new OperationStamp(KEY, new byte[32], json.createObjectNode()))));
        assertThat(SqlFailures.uniqueConstraint(operationConflict)).contains("business_events_operation_unique");
        assertThat(receipts.find(ACTOR, KEY)).isPresent(); // Read only after the failed transaction rolled back.
        var notification = notification(Kind.ORDER_PLACED, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER);
        transactions.write(() -> outbox.enqueue(first.id(), List.of(notification)));
        var second = event("order_placed", ORDER, null, json.createObjectNode(), null);
        transactions.write(() -> events.append(second));
        Throwable primaryConflict =
                catchThrowable(() -> transactions.write(() -> outbox.enqueue(second.id(), List.of(notification))));
        assertThat(SqlFailures.uniqueConstraint(primaryConflict)).contains("notification_outbox_pkey");
        assertThat(count("notification_outbox")).isEqualTo(1);
    }

    @Test
    void concurrentExactQueueDuplicatesCreateOnlyOneRow() throws Exception {
        var event = event("order_placed", ORDER, null, json.createObjectNode(), null);
        transactions.write(() -> events.append(event));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(ignored -> executor.submit(() -> transactions.write(() -> {
                        await(barrier);
                        return outbox.enqueue(
                                        event.id(),
                                        List.of(notification(
                                                Kind.ORDER_PLACED,
                                                ORDER,
                                                null,
                                                Channel.EMAIL,
                                                CUSTOMER,
                                                Audience.CUSTOMER)))
                                .size();
                    })))
                    .toList();
            assertThat(tasks.get(0).get(10, TimeUnit.SECONDS) + tasks.get(1).get(10, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(count("notification_outbox")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void callerFailureRollsBackMoneyStockEventReceiptLedgerAndOutbox(int failAt) {
        String before = state();
        assertThatThrownBy(() -> transactions.write(() -> {
                    jdbc.queryForObject("SELECT id FROM cane.orders WHERE id=? FOR UPDATE", UUID.class, ORDER);
                    UUID payment = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO cane.payments(id,order_id,method,amount_vnd,order_link_verified,applied_to_order,confirmed_by) VALUES (?,?,'cash',40000,true,true,?)",
                            payment,
                            ORDER,
                            ACTOR.toString());
                    jdbc.update("UPDATE cane.orders SET status='completed',payment_status='paid' WHERE id=?", ORDER);
                    fail(failAt, 1);
                    jdbc.update("UPDATE cane.products SET stock_qty=stock_qty+1 WHERE sku='AUDIT-A'");
                    fail(failAt, 2);
                    var event = event(
                            "order_completed",
                            ORDER,
                            payment,
                            json.createObjectNode().put("paymentStatus", "paid"),
                            null);
                    events.append(
                            event,
                            new OperationStamp(
                                    KEY,
                                    new byte[32],
                                    json.createObjectNode()
                                            .put("eventId", event.id().toString())));
                    fail(failAt, 3);
                    jdbc.update(
                            "INSERT INTO cane.stock_movements(sku,delta,reason,event_id,actor) VALUES ('AUDIT-A',1,'restock',?,?)",
                            event.id(),
                            ACTOR.toString());
                    fail(failAt, 4);
                    outbox.enqueue(
                            event.id(),
                            List.of(notification(
                                    Kind.COMPLETED, ORDER, payment, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER)));
                    fail(failAt, 5);
                    if (failAt == 6)
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new InjectedFailure();
                            }
                        });
                    return null;
                }))
                .isInstanceOf(InjectedFailure.class);
        assertThat(state()).isEqualTo(before);
        assertThat(receipts.find(ACTOR, KEY)).isEmpty();
    }

    @Test
    void workerCanAppendAndQueueWithoutStaffTableAccess() {
        var workerSource = new DriverManagerDataSource(url, "cane_worker", TestInfrastructure.WORKER_PASSWORD);
        var workerJdbc = new JdbcTemplate(workerSource);
        var manager = new org.springframework.jdbc.support.JdbcTransactionManager(workerSource);
        var workerTx = new Transactions(manager);
        var workerNamed = new NamedParameterJdbcTemplate(workerJdbc);
        var workerEvents = new BusinessEventWriter(workerNamed, json);
        var workerOutbox = new OutboxWriter(workerNamed, json, new InputNormalizer(validators.getValidator()));
        var event = new BusinessEvent(
                UUID.randomUUID(),
                ORDER,
                null,
                "owner_payment_due",
                null,
                null,
                "system",
                null,
                null,
                json.createObjectNode(),
                "order:" + ORDER + ":payment_due");
        workerTx.write(() -> {
            workerEvents.append(event);
            return workerOutbox.enqueue(
                    event.id(),
                    List.of(notification(
                            Kind.OWNER_PAYMENT_DUE, ORDER, null, Channel.EMAIL, CUSTOMER, Audience.OWNER)));
        });
        assertThat(count("notification_outbox")).isEqualTo(1);
        assertThatThrownBy(() -> workerJdbc.queryForList("SELECT id FROM cane.staff_users"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void refundUsesRefundReferenceAndReceiptResultCannotNameAnotherEvent() {
        UUID payment = insertBank(ORDER, true);
        owner.update(
                "UPDATE cane.payments SET status='refunded',refunded_at=now(),refund_reference='fixture-refund' WHERE id=?",
                payment);
        var facts = paymentFacts(payment, ORDER, true).put("reference", "fixture-refund");
        var event = event("payment_refunded", ORDER, payment, facts, null);
        assertThatThrownBy(() -> transactions.write(() -> events.append(
                        event,
                        new OperationStamp(
                                KEY,
                                new byte[32],
                                json.createObjectNode()
                                        .put("eventId", UUID.randomUUID().toString())))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.write(() -> events.append(event(
                        "payment_refunded",
                        ORDER,
                        payment,
                        facts.deepCopy().put("reference", "fixture-bank-" + payment),
                        null))))
                .isInstanceOf(IllegalStateException.class);
        transactions.write(() -> {
            events.append(
                    event,
                    new OperationStamp(
                            KEY,
                            new byte[32],
                            json.createObjectNode().put("eventId", event.id().toString())));
            return outbox.enqueue(
                    event.id(),
                    List.of(notification(Kind.REFUNDED, ORDER, payment, Channel.EMAIL, CUSTOMER, Audience.CUSTOMER)));
        });
        assertThat(count("business_events")).isEqualTo(1);
        assertThat(count("notification_outbox")).isEqualTo(1);
    }

    @Test
    void nonexistentOrderForeignKeyRollsBackBeforeLeavingAnOperationReceipt() {
        Throwable failure = catchThrowable(() -> transactions.write(() -> events.append(
                event("order_placed", UUID.randomUUID(), null, json.createObjectNode(), null),
                new OperationStamp(KEY, new byte[32], json.createObjectNode()))));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(receipts.find(ACTOR, KEY)).isEmpty();
        assertThat(count("business_events")).isZero();
    }

    private void insertOrder(UUID id, String number, String email) {
        owner.update("""
                INSERT INTO cane.orders(id,order_number,status,payment_method,fulfillment_method,
                  recipient_name,recipient_phone,recipient_email,address,subtotal_vnd,shipping_fee_vnd,
                  link_expires_at,idempotency_key,request_hash)
                VALUES (?,?,'confirmed','cod','delivery','Fixture','0900000000',?,
                  '{"wardCode":"TEST","line1":"Fixture"}',25000,15000,now()+interval '90 days',?,decode(repeat('01',32),'hex'))
                """, id, number, email, "fixture-order-key-" + number);
    }

    private UUID insertBank(UUID order, boolean verified) {
        UUID id = UUID.randomUUID();
        owner.update("""
                INSERT INTO cane.payments(id,order_id,method,amount_vnd,provider_ref,bank_receipt,order_link_verified,confirmed_by)
                VALUES (?,?,'bank',40000,?,'{"bankCode":"TEST","accountNo":"TEST","transferContent":"fixture","transactionAt":"2026-11-14T00:00:00Z"}',?,?)
                """, id, order, "fixture-bank-" + id, verified, ACTOR.toString());
        return id;
    }

    private ObjectNode paymentFacts(UUID payment, UUID order, boolean verified) {
        var facts = json.createObjectNode()
                .put("paymentId", payment.toString())
                .put("amountVnd", 40000)
                .put("method", "bank")
                .put("reference", "fixture-bank-" + payment)
                .put("isPrincipal", false)
                .put("orderLinkVerified", verified);
        if (order == null) facts.putNull("orderId");
        else facts.put("orderId", order.toString());
        return facts;
    }

    private BusinessEvent event(String kind, UUID order, UUID payment, ObjectNode facts, String sourceKey) {
        return new BusinessEvent(
                UUID.randomUUID(), order, payment, kind, null, null, ACTOR.toString(), null, null, facts, sourceKey);
    }

    private static Notification notification(
            Kind kind, UUID order, UUID payment, Channel channel, String recipient, Audience audience) {
        return new Notification(UUID.randomUUID(), kind, order, payment, channel, recipient, audience);
    }

    private long count(String table) {
        // Fixed test-owned names only, never request input.
        return owner.queryForObject("SELECT count(*) FROM cane." + table, Long.class);
    }

    private String state() {
        return owner.queryForObject("""
                SELECT jsonb_build_object(
                  'products',(SELECT jsonb_agg(to_jsonb(t) ORDER BY sku) FROM cane.products t),
                  'orders',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.orders t),
                  'payments',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.payments t),
                  'events',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.business_events t),
                  'movements',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.stock_movements t),
                  'outbox',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM cane.notification_outbox t))::text
                """, String.class);
    }

    private static void fail(int requested, int step) {
        if (requested == step) throw new InjectedFailure();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class InjectedFailure extends RuntimeException {}
}
