package vn.cane.support.audit;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.validation.AuditJson;
import vn.cane.support.validation.InputNormalizer;

/** Server-selected facts at the event's time, never an HTTP body or a database entity response. */
public record BusinessEvent(
        UUID id,
        UUID orderId,
        UUID paymentId,
        String kind,
        String fromStatus,
        String toStatus,
        String actor,
        String publicLabel,
        String note,
        ObjectNode data,
        String sourceKey) {
    public BusinessEvent {
        Objects.requireNonNull(id);
        if (kind == null || kind.isBlank() || !kind.equals(kind.strip())) throw invalid();
        if (!"system".equals(actor) && !"guest".equals(actor)) {
            try {
                if (!UUID.fromString(actor).toString().equals(actor)) throw invalid();
            } catch (IllegalArgumentException | NullPointerException invalidActor) {
                throw invalid();
            }
        }
        if (publicLabel != null && (orderId == null || publicLabel.isBlank())) throw invalid();
        if (sourceKey != null && sourceKey.isBlank()) throw invalid();
        note = InputNormalizer.optionalNote(note, "note");
        data = AuditJson.facts(data);
    }

    @Override
    public ObjectNode data() {
        return data.deepCopy();
    }

    @Override
    public String toString() {
        return "BusinessEvent[redacted]";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Business event metadata is invalid");
    }
}
