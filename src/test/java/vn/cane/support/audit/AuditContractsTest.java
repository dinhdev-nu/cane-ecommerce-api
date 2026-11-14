package vn.cane.support.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.idempotency.OperationReceipts;
import vn.cane.support.idempotency.OperationStamp;
import vn.cane.support.outbox.Notification;
import vn.cane.support.validation.AuditJson;
import vn.cane.support.validation.InputValidationException;

class AuditContractsTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void onlyExplicitPersistenceFactsAndMinimalResultsAreAccepted() {
        for (String field : new String[] {"email", "recipient", "password", "token", "url", "body", "bankReceipt"}) {
            var secret = json.createObjectNode().put(field, "sensitive-value");
            assertThatThrownBy(() -> AuditJson.facts(secret))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(field)
                    .hasMessageNotContaining("sensitive-value");
            assertThatThrownBy(() -> AuditJson.result(secret)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AuditJson.result(node("{\"expected\":\"preparing\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditJson.facts(node("{\"amountVnd\":1.5}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditJson.facts(node("{\"status\":{\"token\":\"secret\"}}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inventoryInspectionPreservesZeroAndRejectsUnexpectedNestedData() {
        var facts = node(
                "{\"restockItems\":[{\"sku\":\"TEST\",\"quantity\":0,\"note\":null}],\"changedKeys\":[\"owner_notify\"]}");
        assertThat(AuditJson.facts(facts)).isEqualTo(facts);
        assertThatThrownBy(() -> AuditJson.facts(
                        node("{\"restockItems\":[{\"sku\":\"TEST\",\"quantity\":0,\"email\":\"private\"}]}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditJson.facts(node("{\"changedKeys\":[{}]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventStampAndLoadedReceiptDefensivelyCopyTheirMutableValues() {
        var id = UUID.randomUUID();
        var facts = json.createObjectNode().put("eventId", id.toString());
        var result = AuditJson.result(facts);
        byte[] hash = new byte[32];
        var stamp = new OperationStamp("fixture-operation-0001", hash, result);
        var stored = new OperationReceipts.Stored(id, hash, result);
        var eventData = json.createObjectNode().put("status", "confirmed");
        var event = new BusinessEvent(
                id, null, null, "operation_noop", null, null, "system", null, "internal note", eventData, null);
        hash[0] = 7;
        result.put("status", "changed");
        eventData.put("status", "changed");
        stamp.requestHash()[1] = 7;
        stored.requestHash()[1] = 7;
        stamp.result().put("status", "changed");
        stored.result().put("status", "changed");
        event.data().put("status", "changed");
        assertThat(stamp.requestHash()).containsOnly((byte) 0);
        assertThat(stored.requestHash()).containsOnly((byte) 0);
        assertThat(stamp.result()).isEqualTo(facts);
        assertThat(stored.result()).isEqualTo(facts);
        assertThat(event.data().path("status").asString()).isEqualTo("confirmed");
        assertThat(event.toString()).doesNotContain("internal note", "confirmed");
        assertThat(stamp.toString()).doesNotContain("fixture-operation", id.toString());
        assertThat(stored.toString()).doesNotContain(id.toString());
        assertThat(new Notification(
                                id,
                                Notification.Kind.OWNER_ALERT,
                                null,
                                null,
                                Notification.Channel.EMAIL,
                                "private@example.invalid",
                                Notification.Audience.OWNER)
                        .toString())
                .doesNotContain("private@example.invalid");
    }

    @Test
    void operationKeysAndHashesUseTheExistingInputContract() {
        assertThatThrownBy(() -> new OperationStamp("short", new byte[32], json.createObjectNode()))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> new OperationStamp("fixture-operation-0001", new byte[31], json.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationStamp("fixture operation 0001", new byte[32], json.createObjectNode()))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void actorMustBeServerIdentityAndPublicLabelRequiresAnOrder() {
        assertThatThrownBy(() -> new BusinessEvent(
                        UUID.randomUUID(),
                        null,
                        null,
                        "operation_noop",
                        null,
                        null,
                        "client-name",
                        null,
                        null,
                        json.createObjectNode(),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessEvent(
                        UUID.randomUUID(),
                        null,
                        null,
                        "operation_noop",
                        null,
                        null,
                        "system",
                        "public label",
                        null,
                        json.createObjectNode(),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode node(String value) {
        return (ObjectNode) json.readTree(value);
    }
}
