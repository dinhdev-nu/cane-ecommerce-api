package vn.cane.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.cane.support.ratelimit.ClientIpResolver;
import vn.cane.support.ratelimit.RateGroup;
import vn.cane.support.ratelimit.RateLimiter;

/** The other groups are consumed by their business routes when those routes are implemented. */
public final class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    private final ClientIpResolver addresses;
    private final ApiErrors errors;

    public RateLimitFilter(RateLimiter limiter, ClientIpResolver addresses, ApiErrors errors) {
        this.limiter = limiter;
        this.addresses = addresses;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return group(request) == null;
    }

    private static RateGroup group(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return null;
        return switch (request.getServletPath()) {
            case "/api/v1/admin/auth/login" -> RateGroup.LOGIN;
            case "/api/v1/checkout/quote" -> RateGroup.QUOTE;
            case "/api/v1/orders" -> RateGroup.PLACE_ORDER;
            default -> null;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimiter.Decision decision;
        try {
            String forwarded = String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
            decision = limiter.check(group(request), addresses.resolve(request.getRemoteAddr(), forwarded));
        } catch (RuntimeException unavailable) {
            errors.write(request, response, 503, ApiErrorCode.SERVICE_UNAVAILABLE);
            return;
        }
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            errors.write(request, response, 429, ApiErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }
}
