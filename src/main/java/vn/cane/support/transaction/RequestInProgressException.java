package vn.cane.support.transaction;

public final class RequestInProgressException extends RuntimeException {
    public RequestInProgressException() {
        super("Request is already in progress");
    }
}
