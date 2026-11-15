package vn.cane.checkout.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.catalog.application.CheckoutCatalog.Product;
import vn.cane.checkout.domain.CheckoutInput;
import vn.cane.checkout.domain.Quote;
import vn.cane.support.validation.NumericInputs;

/** Calculation and business guards shared by the read-only quote and the locked create path. */
@Component
public final class CheckoutPolicy {
    public Quote evaluate(CheckoutInput input, CheckoutSettings settings, List<Product> products) {
        var bySku = new HashMap<String, Product>();
        products.forEach(p -> bySku.put(p.sku(), p));
        var fields = new LinkedHashMap<String, String>();
        var errors = new ArrayList<Quote.Issue>();
        var lines = new ArrayList<Quote.Line>();
        if (!settings.accepting()) fields.put("shop", "SHOP_NOT_ACCEPTING");
        if (!settings.paymentMethods().contains(input.paymentMethod())
                || input.paymentMethod().equals("bank_transfer") && !settings.bankEnabled())
            fields.put("paymentMethod", "Payment method is unavailable.");
        boolean delivery = input.fulfillmentMethod().equals("delivery");
        var zone = delivery
                ? settings.zones().stream()
                        .filter(z -> z.wardCode().equals(input.address().wardCode()))
                        .findFirst()
                        .orElse(null)
                : null;
        boolean supported = !delivery || settings.feeVnd() != null && zone != null;
        if (!supported) fields.put("address.wardCode", "DELIVERY_NOT_SUPPORTED");
        if (!delivery && !settings.pickupEnabled()) fields.put("fulfillmentMethod", "Pickup is unavailable.");
        Long subtotal = 0L;
        for (var item : input.items()) {
            var p = bySku.get(item.sku());
            Long amount = p == null ? null : NumericInputs.multiplyVnd(p.priceVnd(), item.quantity(), "items");
            lines.add(new Quote.Line(
                    item.sku(), p == null ? null : p.name(), item.quantity(), p == null ? null : p.priceVnd(), amount));
            subtotal = subtotal == null || amount == null ? null : NumericInputs.addVnd(subtotal, amount, "items");
            if (p == null || !p.active())
                errors.add(new Quote.Issue(item.sku(), "ITEM_UNAVAILABLE", "Item is unavailable."));
            if (item.quantity() > settings.maxQty())
                errors.add(new Quote.Issue(item.sku(), "INVALID_INPUT", "Quantity exceeds the per-item limit."));
            if (p != null && item.quantity() > p.stockQty())
                errors.add(new Quote.Issue(item.sku(), "INSUFFICIENT_STOCK", "Insufficient stock."));
            if (delivery && zone != null && p != null && p.deliveryScope().equals("near") && !zone.allowNear())
                errors.add(new Quote.Issue(
                        item.sku(), "OUTSIDE_NEAR_AREA", "Choose pickup or a supported delivery ward for this item."));
        }
        Long remaining = subtotal == null || settings.freeThresholdVnd() == null
                ? null
                : Math.max(0, settings.freeThresholdVnd() - subtotal);
        Long fee = !supported
                ? null
                : !delivery
                        ? 0L
                        : subtotal != null
                                        && settings.freeThresholdVnd() != null
                                        && subtotal >= settings.freeThresholdVnd()
                                ? 0L
                                : settings.feeVnd();
        Long total = subtotal == null || fee == null ? null : NumericInputs.addVnd(subtotal, fee, "totalVnd");
        return new Quote(
                total != null && fields.isEmpty() && errors.isEmpty(),
                lines,
                errors,
                fields,
                subtotal,
                fee,
                total,
                remaining);
    }
}
