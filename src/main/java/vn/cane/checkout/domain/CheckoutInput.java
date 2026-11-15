package vn.cane.checkout.domain;

import java.util.List;

public record CheckoutInput(List<Item> items, String fulfillmentMethod, Address address, String paymentMethod) {
    public CheckoutInput {
        items = List.copyOf(items);
    }

    public List<String> skus() {
        return items.stream().map(Item::sku).toList();
    }

    public record Item(String sku, int quantity) {}

    public record Address(String wardCode, String line1) {
        @Override
        public String toString() {
            return "Address[redacted]";
        }
    }

    @Override
    public String toString() {
        return "CheckoutInput[redacted]";
    }
}
