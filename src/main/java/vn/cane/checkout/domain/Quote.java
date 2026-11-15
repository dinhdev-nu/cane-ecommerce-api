package vn.cane.checkout.domain;

import java.util.List;
import java.util.Map;

public record Quote(
        boolean canSubmit,
        List<Line> items,
        List<Issue> itemErrors,
        Map<String, String> fieldErrors,
        Long subtotalVnd,
        Long shippingFeeVnd,
        Long totalVnd,
        Long freeShipRemainingVnd) {
    public Quote {
        items = List.copyOf(items);
        itemErrors = List.copyOf(itemErrors);
        fieldErrors = Map.copyOf(fieldErrors);
    }

    public record Line(String sku, String name, int quantity, Long unitPriceVnd, Long lineTotalVnd) {}

    public record Issue(String sku, String code, String message) {}
}
