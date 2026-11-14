package vn.cane.admin.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

public final class AdminOriginFilter extends OncePerRequestFilter {
    private final AdminRequestOrigin origin;
    private final ApiErrors errors;

    public AdminOriginFilter(AdminRequestOrigin origin, ApiErrors errors) {
        this.origin = origin;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AdminRequestOrigin.requiresProtection(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!origin.permits(request)) {
            errors.write(request, response, 403, ApiErrorCode.FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
