package vn.cane.catalog.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.StaffCommandService;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.CanonicalOperation;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.OperationReceipt;

@Service
public final class InitialStockService {
    private final InitialStockRepository repository;
    private final StaffCommandService staff;
    private final IdempotentOperations operations;
    private final BusinessEventWriter events;
    private final JsonMapper json;

    public InitialStockService(
            InitialStockRepository repository,
            StaffCommandService staff,
            IdempotentOperations operations,
            BusinessEventWriter events,
            JsonMapper json) {
        this.repository = repository;
        this.staff = staff;
        this.operations = operations;
        this.events = events;
        this.json = json;
    }

    public OperationReceipt initialize(InitialStockCommand command) {
        var body = json.createObjectNode()
                .put("sku", command.sku())
                .put("quantity", command.quantity())
                .put("reason", command.reason());
        var canonical = CanonicalOperation.commandLine("catalog/initial-stock", body, command.key());
        return operations.execute(() -> staff.requireOperationalOwner(command.ownerId()), canonical, scope -> {
            int before = repository.lockStock(command.sku());
            if (before != 0)
                throw new IllegalStateException(
                        "Initial stock requires zero stock; use a deliberate adjustment for existing stock");
            UUID eventId = UUID.randomUUID();
            UUID movementId = command.quantity() == 0 ? null : UUID.randomUUID();
            var result = json.createObjectNode()
                    .put("sku", command.sku())
                    .put("kind", "restock")
                    .put("stockBefore", 0)
                    .put("stockAfter", command.quantity())
                    .put("delta", command.quantity());
            if (movementId == null) result.putNull("movementId");
            else result.put("movementId", movementId.toString());
            if (command.quantity() > 0) repository.setStock(command.sku(), command.quantity(), scope.actor());
            events.append(
                    new BusinessEvent(
                            eventId,
                            null,
                            null,
                            command.quantity() == 0 ? "operation_noop" : "stock_adjusted",
                            null,
                            null,
                            scope.actor().toString(),
                            null,
                            command.reason(),
                            result,
                            null),
                    scope.stamp(result));
            if (movementId != null)
                repository.appendRestock(
                        movementId, command.sku(), command.quantity(), eventId, scope.actor(), command.reason());
        });
    }
}
