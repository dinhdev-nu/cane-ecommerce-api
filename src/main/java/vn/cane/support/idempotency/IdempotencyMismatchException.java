package vn.cane.support.idempotency;

public final class IdempotencyMismatchException extends RuntimeException {
    public IdempotencyMismatchException() {
        super("Operation key was used for another command");
    }
}
