package vn.cane.catalog.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.cane.support.transaction.TransactionAccess;

/** Orders calls after locking order/principal; Catalog owns stock and immutable ledger changes. */
@Service
public final class OrderStockReturns {
    private final CheckoutCatalog catalog;
    private final StockLedger ledger;
    private final PricesInvalidation prices;

    public OrderStockReturns(CheckoutCatalog catalog, StockLedger ledger, PricesInvalidation prices) {
        this.catalog = catalog;
        this.ledger = ledger;
        this.prices = prices;
    }

    public record Item(String sku, int quantity, String note) {}

    public record Change(String sku, int quantity, int after, String note) {}

    public record Plan(UUID orderId, List<Change> changes) {
        public Plan {
            changes = List.copyOf(changes);
        }
    }

    public Plan prepare(UUID id, List<Item> items) {
        TransactionAccess.requireWrite();
        if (items.isEmpty()
                || items.stream().anyMatch(i -> i.quantity() < 0)
                || items.stream().map(Item::sku).distinct().count() != items.size())
            throw new IllegalArgumentException("Invalid order stock return");
        var products = catalog.lock(items.stream().map(Item::sku).toList());
        if (products.size() != items.size() || ledger.hasOrderReturn(id))
            throw new IllegalStateException("Order return history is inconsistent");
        var changes = items.stream()
                .sorted(java.util.Comparator.comparing(Item::sku))
                .map(item -> {
                    var product = products.stream()
                            .filter(p -> p.sku().equals(item.sku()))
                            .findFirst()
                            .orElseThrow();
                    int after = Math.addExact(product.stockQty(), item.quantity());
                    return new Change(item.sku(), item.quantity(), after, item.note());
                })
                .toList();
        return new Plan(id, changes);
    }

    public void apply(Plan plan, UUID eventId, UUID actor, String reason) {
        TransactionAccess.requireWrite();
        if (!Set.of("cancel", "delivery_return").contains(reason))
            throw new IllegalArgumentException("Invalid return kind");
        for (var item : plan.changes()) {
            if (item.quantity() == 0) continue;
            ledger.setStock(item.sku(), item.after(), actor);
            ledger.append(
                    UUID.randomUUID(),
                    item.sku(),
                    item.quantity(),
                    reason,
                    plan.orderId(),
                    eventId,
                    actor,
                    item.note());
        }
        prices.afterCommit();
    }
}
