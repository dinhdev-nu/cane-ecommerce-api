package vn.cane.orders.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import vn.cane.orders.application.OrderLinkTokens;
import vn.cane.orders.domain.InvalidOrderLinkException;
import vn.cane.orders.domain.OrderLinkState;

public final class HmacOrderLinkTokens implements OrderLinkTokens {
    private final LinkKeyRing ring;

    public HmacOrderLinkTokens(LinkKeyRing ring) {
        this.ring = ring;
    }

    @Override
    public Issued sign(OrderLinkState state) {
        if (!state.hasMillisecondPrecision())
            throw new IllegalStateException("Stored order link expiry must have millisecond precision");
        String kid = ring.activeKid();
        String payload = kid + "." + state.orderId() + "." + state.version() + "."
                + state.expiresAt().toEpochMilli();
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(kid, state));
        return new Issued("v1." + payload + "." + signature);
    }

    @Override
    public OrderLinkState verifySignature(String token) {
        try {
            if (token == null || token.length() > 512 || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e))
                throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 6
                    || !parts[0].equals("v1")
                    || !LinkKeyRing.validKid(parts[1])
                    || ring.key(parts[1]) == null) throw invalid();
            UUID id = UUID.fromString(parts[2]);
            if (!id.toString().equals(parts[2])
                    || !parts[3].matches("[1-9][0-9]*")
                    || !parts[4].matches("[1-9][0-9]*")
                    || !parts[5].matches("[A-Za-z0-9_-]{43}")) throw invalid();
            var state =
                    new OrderLinkState(id, Long.parseLong(parts[3]), Instant.ofEpochMilli(Long.parseLong(parts[4])));
            byte[] supplied = Base64.getUrlDecoder().decode(parts[5]);
            if (supplied.length != 32
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(supplied)
                            .equals(parts[5])
                    || !MessageDigest.isEqual(mac(parts[1], state), supplied)) throw invalid();
            return state;
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private byte[] mac(String kid, OrderLinkState state) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(ring.key(kid));
            String message = "cane-order-link-v1\n" + kid + "\n" + state.orderId() + "\n" + state.version() + "\n"
                    + state.expiresAt().toEpochMilli();
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable");
        }
    }

    private static InvalidOrderLinkException invalid() {
        return new InvalidOrderLinkException();
    }

    @Override
    public String toString() {
        return "HmacOrderLinkTokens[redacted]";
    }
}
