package vn.cane.orders.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.cane.orders.domain.InvalidOrderLinkException;
import vn.cane.orders.domain.OrderLinkState;
import vn.cane.support.transaction.TransactionAccess;

@Service
public final class OrderLinkService {
    private final OrderLinkTokens tokens;
    private final OrderLinkRepository repository;
    private final Clock clock;

    public OrderLinkService(OrderLinkTokens tokens, OrderLinkRepository repository, Clock clock) {
        this.tokens = tokens;
        this.repository = repository;
        this.clock = clock;
    }

    /** Server/worker call only. Caller owns authorization; a UUID alone must never be a guest API. */
    public OrderLinkTokens.Issued mintCurrent(UUID orderId) {
        var current = repository.find(orderId).orElseThrow(InvalidOrderLinkException::new);
        requireUnexpired(current);
        return tokens.sign(current);
    }

    public OrderLinkState authorizeRead(String token) {
        return authorize(token, false);
    }

    /** Caller owns the write transaction. Call before payment/SKU locks and any guest write. */
    public OrderLinkState authorizeWrite(String token) {
        TransactionAccess.requireWrite();
        return authorize(token, true);
    }

    private OrderLinkState authorize(String token, boolean locked) {
        var claims = tokens.verifySignature(token);
        requireUnexpired(claims);
        var current = (locked ? repository.lock(claims.orderId()) : repository.find(claims.orderId()))
                .orElseThrow(InvalidOrderLinkException::new);
        if (!claims.equals(current)) throw new InvalidOrderLinkException();
        requireUnexpired(current); // Re-read the clock after waiting for a row lock/database round trip.
        return current;
    }

    private void requireUnexpired(OrderLinkState state) {
        if (!state.validAt(clock.instant())) throw new InvalidOrderLinkException();
    }
}
