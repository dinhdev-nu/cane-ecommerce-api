package vn.cane.admin.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/** Trusted configuration only; Host and forwarding headers never establish the public origin. */
public final class AdminRequestOrigin {
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final Origin trusted;

    public AdminRequestOrigin(String publicOrigin, String environment) {
        try {
            trusted = origin(publicOrigin, false);
            if (!Set.of("local", "test").contains(environment)
                    && !trusted.scheme().equals("https")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "PUBLIC_ORIGIN must be a configured HTTP origin; staging/production require HTTPS");
        }
    }

    public static boolean requiresProtection(HttpServletRequest request) {
        String path = request.getServletPath();
        return WRITES.contains(request.getMethod())
                && (path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/"));
    }

    public boolean permits(HttpServletRequest request) {
        var sites = Collections.list(request.getHeaders("Sec-Fetch-Site"));
        if (sites.size() > 1 || sites.stream().anyMatch("cross-site"::equalsIgnoreCase)) return false;
        var origins = Collections.list(request.getHeaders("Origin"));
        if (!origins.isEmpty()) {
            return origins.size() == 1 && matches(origins.getFirst(), false);
        }
        var referers = Collections.list(request.getHeaders("Referer"));
        return referers.size() == 1 && matches(referers.getFirst(), true);
    }

    private boolean matches(String value, boolean referer) {
        try {
            return trusted.equals(origin(value, referer));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static Origin origin(String value, boolean referer) {
        if (value == null || value.length() > 8192) throw new IllegalArgumentException();
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) throw new IllegalArgumentException();
        if (!referer && (!uri.getRawPath().isEmpty() || uri.getRawQuery() != null)) {
            throw new IllegalArgumentException();
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IllegalArgumentException();
        return new Origin(
                scheme, host.toLowerCase(Locale.ROOT), port == -1 ? (scheme.equals("https") ? 443 : 80) : port);
    }

    private record Origin(String scheme, String host, int port) {}
}
