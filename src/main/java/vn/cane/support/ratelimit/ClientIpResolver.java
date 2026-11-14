package vn.cane.support.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** Only numeric peers and an explicitly trusted right-to-left X-Forwarded-For chain. */
public final class ClientIpResolver {
    private final List<IpAddressMatcher> trusted;

    public ClientIpResolver(List<String> trustedProxies) {
        trusted = trustedProxies.stream().map(ClientIpResolver::trustedRange).toList();
    }

    public String resolve(String remoteAddress, String forwardedFor) {
        String peer = canonicalAddress(remoteAddress);
        if (!isTrusted(peer) || forwardedFor == null || forwardedFor.isBlank()) return peer;
        if (forwardedFor.length() > 4096) return peer;
        String[] chain = forwardedFor.split(",", -1);
        if (chain.length > 32) return peer;
        String client = peer;
        try {
            for (int index = chain.length - 1; index >= 0; index--) {
                if (!isTrusted(client)) return client;
                client = canonicalAddress(chain[index].strip());
            }
            return client;
        } catch (IllegalArgumentException malformed) {
            return peer; // Malformed forwarding cannot select a new client bucket.
        }
    }

    private boolean isTrusted(String address) {
        return trusted.stream().anyMatch(range -> range.matches(address));
    }

    public static String canonicalAddress(String address) {
        if (address == null || address.isEmpty() || address.length() > 64) throw invalidAddress();
        if (address.indexOf(':') < 0) {
            String[] octets = address.split("\\.", -1);
            if (octets.length != 4) throw invalidAddress();
            var result = new StringBuilder();
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}")) throw invalidAddress();
                int number = Integer.parseInt(octet);
                if (number > 255) throw invalidAddress();
                if (!result.isEmpty()) result.append('.');
                result.append(number);
            }
            return result.toString();
        }
        if (!address.matches("[0-9a-fA-F:.]+")) throw invalidAddress();
        try {
            return InetAddress.getByName(address).getHostAddress(); // Numeric IPv6 only; no DNS/zone identifiers.
        } catch (UnknownHostException malformed) {
            throw invalidAddress();
        }
    }

    private static IpAddressMatcher trustedRange(String value) {
        String[] parts = value.strip().split("/", -1);
        if (parts.length > 2) throw invalidAddress();
        String address = canonicalAddress(parts[0]);
        if (parts.length == 1) return new IpAddressMatcher(address);
        if (!parts[1].matches("[0-9]{1,3}")) throw invalidAddress();
        int prefix = Integer.parseInt(parts[1]);
        if (prefix > (address.contains(":") ? 128 : 32)) throw invalidAddress();
        return new IpAddressMatcher(address + "/" + prefix);
    }

    private static IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException("A numeric IP address or valid trusted proxy range is required");
    }
}
