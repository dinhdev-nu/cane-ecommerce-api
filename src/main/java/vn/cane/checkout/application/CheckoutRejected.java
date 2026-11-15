package vn.cane.checkout.application;

import java.util.List;
import vn.cane.checkout.domain.Quote;

/** Business rejection. HTTP mapping stays in the API adapter. */
public final class CheckoutRejected extends RuntimeException {
    private final String code;
    private final Quote quote;
    private final List<Availability> availability;

    public CheckoutRejected(String code, Quote quote, List<Availability> availability) {
        super("Checkout rejected");
        this.code = code;
        this.quote = quote;
        this.availability = List.copyOf(availability);
    }

    public String code() {
        return code;
    }

    public Quote quote() {
        return quote;
    }

    public List<Availability> availability() {
        return availability;
    }

    public record Availability(String sku, int available) {}
}
