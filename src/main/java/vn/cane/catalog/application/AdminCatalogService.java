package vn.cane.catalog.application;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.catalog.domain.CatalogRejected;
import vn.cane.catalog.domain.CatalogSkus;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.idempotency.IdempotentOperations;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputValidationException;

@Service
@Profile("api & !worker")
public final class AdminCatalogService {
    private final AdminCatalogRepository products;
    private final ProductLocks locks;
    private final StockLedger ledger;
    private final StaffAuthorization authorization;
    private final IdempotentOperations operations;
    private final BusinessEventWriter events;
    private final PricesInvalidation prices;
    private final Transactions tx;
    private final JsonMapper json;

    public AdminCatalogService(
            AdminCatalogRepository products,
            ProductLocks locks,
            StockLedger ledger,
            StaffAuthorization authorization,
            IdempotentOperations operations,
            BusinessEventWriter events,
            PricesInvalidation prices,
            Transactions tx,
            JsonMapper json) {
        this.products = products;
        this.locks = locks;
        this.ledger = ledger;
        this.authorization = authorization;
        this.operations = operations;
        this.events = events;
        this.prices = prices;
        this.tx = tx;
        this.json = json;
    }

    public AdminCatalogView.Skus read(StaffPrincipal principal) {
        return tx.readSnapshot(() -> {
            authorization.requireCurrent(principal);
            var rows = products.skus();
            if (!rows.stream()
                    .map(AdminCatalogView.Sku::sku)
                    .collect(java.util.stream.Collectors.toSet())
                    .equals(CatalogSkus.ALL)) throw new IllegalStateException("Catalog is incomplete");
            return new AdminCatalogView.Skus(rows, products.serverTime());
        });
    }

    public AdminCatalogView.Movements movements(StaffPrincipal principal, AdminCatalogView.Query query) {
        return tx.readSnapshot(() -> {
            authorization.requireCurrent(principal);
            return new AdminCatalogView.Movements(
                    products.movements(query),
                    query.page(),
                    query.pageSize(),
                    products.count(query),
                    products.serverTime());
        });
    }

    public AdminCatalogView.Sku update(StaffPrincipal principal, String sku, Long price, Boolean active) {
        authorization.requireOwner(principal);
        return tx.write(() -> {
            var actor = authorization.requireOwner(principal);
            var before = locked(sku);
            long nextPrice = price == null ? before.priceVnd() : price;
            boolean nextActive = active == null ? before.isActive() : active;
            if (nextPrice == before.priceVnd() && nextActive == before.isActive()) return before;
            products.update(sku, nextPrice, nextActive, actor.id());
            var facts = json.createObjectNode()
                    .put("sku", sku)
                    .put("priceBeforeVnd", before.priceVnd())
                    .put("priceAfterVnd", nextPrice)
                    .put("isActiveBefore", before.isActive())
                    .put("isActiveAfter", nextActive);
            events.append(new BusinessEvent(
                    UUID.randomUUID(),
                    null,
                    null,
                    "product_updated",
                    null,
                    null,
                    actor.id().toString(),
                    null,
                    null,
                    facts,
                    null));
            prices.afterCommit();
            return products.find(sku).orElseThrow();
        });
    }

    public OperationReceipt adjust(StaffPrincipal principal, StockAdjustment command) {
        return operations.execute(() -> authorization.requireOwner(principal).id(), command.canonical(), operation -> {
            var before = locked(command.sku());
            if (command.mode().equals("set") && command.expectedStockQty() != before.stockQty())
                throw CatalogRejected.conflict(before.stockQty());
            long calculated =
                    command.mode().equals("set") ? command.value() : (long) before.stockQty() + command.value();
            if (calculated < 0 || calculated > Integer.MAX_VALUE)
                throw InputValidationException.invalid("value", "Số còn bán được nằm ngoài miền cho phép.");
            int after = (int) calculated, delta = after - before.stockQty();
            UUID event = UUID.randomUUID(), movement = UUID.randomUUID();
            var result = json.createObjectNode()
                    .put("sku", command.sku())
                    .put("kind", command.kind())
                    .put("stockBefore", before.stockQty())
                    .put("stockAfter", after)
                    .put("delta", delta)
                    .put("movementId", movement.toString());
            ledger.setStock(command.sku(), after, operation.actor());
            events.append(
                    new BusinessEvent(
                            event,
                            null,
                            null,
                            "stock_adjusted",
                            null,
                            null,
                            operation.actor().toString(),
                            null,
                            command.reason(),
                            result,
                            null),
                    operation.stamp(result));
            ledger.append(
                    movement, command.sku(), delta, command.kind(), null, event, operation.actor(), command.reason());
            prices.afterCommit();
        });
    }

    private AdminCatalogView.Sku locked(String sku) {
        if (locks.lockSkus(java.util.List.of(sku)).isEmpty()) throw CatalogRejected.missing();
        return products.find(sku).orElseThrow(CatalogRejected::missing);
    }
}
