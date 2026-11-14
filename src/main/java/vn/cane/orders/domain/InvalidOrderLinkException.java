package vn.cane.orders.domain;

public final class InvalidOrderLinkException extends RuntimeException {
    public InvalidOrderLinkException() {
        super("Order link is invalid");
    }
}
