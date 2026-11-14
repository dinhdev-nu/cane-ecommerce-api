package vn.cane.orders.application;

import vn.cane.orders.domain.OrderLinkState;

/** Crypto contract; database/current-expiry authorization belongs to OrderLinkService. */
public interface OrderLinkTokens {
    Issued sign(OrderLinkState state);

    OrderLinkState verifySignature(String token);

    record Issued(String value) {
        @Override
        public String toString() {
            return "IssuedOrderLink[redacted]";
        }
    }
}
