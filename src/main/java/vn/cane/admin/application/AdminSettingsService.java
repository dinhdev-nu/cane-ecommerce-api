package vn.cane.admin.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.transaction.Transactions;

@Service
@Profile("api & !worker")
public final class AdminSettingsService {
    private final SettingsReader reader;
    private final SettingsWriter writer;
    private final SettingsLocks locks;
    private final SettingsValidator validator;
    private final StaffAuthorization authorization;
    private final BusinessEventWriter events;
    private final SettingsEffects effects;
    private final Transactions tx;
    private final JsonMapper json;

    public AdminSettingsService(
            SettingsReader reader,
            SettingsWriter writer,
            SettingsLocks locks,
            SettingsValidator validator,
            StaffAuthorization authorization,
            BusinessEventWriter events,
            SettingsEffects effects,
            Transactions tx,
            JsonMapper json) {
        this.reader = reader;
        this.writer = writer;
        this.locks = locks;
        this.validator = validator;
        this.authorization = authorization;
        this.events = events;
        this.effects = effects;
        this.tx = tx;
        this.json = json;
    }

    public record Values(ObjectNode values, Instant serverTime) {
        @Override
        public String toString() {
            return "Settings[redacted]";
        }
    }

    public Values read(StaffPrincipal principal) {
        return tx.readSnapshot(() -> {
            authorization.requireOwner(principal);
            return new Values(current(), writer.serverTime());
        });
    }

    public Values update(StaffPrincipal principal, JsonNode input) {
        authorization.requireOwner(principal);
        var patch = validator.normalizePatch(input);
        return tx.write(() -> {
            var actor = authorization.requireOwner(principal);
            locks.lockAll();
            var before = current();
            var merged = validator.validateAndMerge(before, patch);
            // jsonb reads small integers as IntNode; validation uses LongNode. Compare
            // the same normalized representation, including numbers inside objects.
            var comparableBefore = validator.normalizePatch(before);
            var changed = patch.propertyNames().stream()
                    .filter(key -> !comparableBefore.get(key).equals(merged.get(key)))
                    .sorted()
                    .toList();
            for (String key : changed) writer.update(key, merged.get(key), actor.id());
            if (!changed.isEmpty()) {
                var facts = json.createObjectNode();
                var keys = facts.putArray("changedKeys");
                changed.forEach(keys::add);
                events.append(new BusinessEvent(
                        UUID.randomUUID(),
                        null,
                        null,
                        "settings_updated",
                        null,
                        null,
                        actor.id().toString(),
                        null,
                        null,
                        facts,
                        null));
                if (changed.contains("low_stock_threshold")) effects.afterLowStockChange();
            }
            return new Values(merged, writer.serverTime());
        });
    }

    private ObjectNode current() {
        var values = reader.read();
        if (!values.keySet().equals(SettingsValidator.KEYS)) throw new IllegalStateException("Settings are incomplete");
        var object = json.createObjectNode();
        SettingsValidator.KEYS.stream()
                .sorted()
                .forEach(key -> object.set(key, values.get(key).deepCopy()));
        return object;
    }
}
