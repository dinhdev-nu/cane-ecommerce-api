package vn.cane.admin.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

/** Added to the Security chain after CSRF; never registered again as a servlet filter. */
public final class StaffSessionFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(StaffSessionFilter.class);
    private final StaffSessionService sessions;
    private final SessionCookies cookies;
    private final ApiErrors errors;

    public StaffSessionFilter(StaffSessionService sessions, SessionCookies cookies, ApiErrors errors) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/api/v1/admin") && !path.startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean login = "POST".equals(request.getMethod()) && path.equals("/api/v1/admin/auth/login");
        boolean optional = login
                || ("GET".equals(request.getMethod()) && path.equals("/api/v1/admin/me"))
                || ("POST".equals(request.getMethod()) && path.equals("/api/v1/admin/auth/logout"));
        String token = cookies.read(request);
        try {
            // Login verifies staff itself and only uses the old cookie for replacement after success.
            Optional<StaffPrincipal> principal = login ? Optional.empty() : sessions.authenticate(token);
            if (principal.isPresent()) {
                var current = principal.get();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        current,
                        null,
                        List.of(new SimpleGrantedAuthority(
                                "ROLE_" + current.actor().role().name()))));
                SecurityContextHolder.setContext(context);
            } else if (!optional) {
                cookies.clear(response);
                errors.write(request, response, 401, ApiErrorCode.AUTH_REQUIRED);
                return;
            } else if (!login && token != null) {
                cookies.clear(response);
            }
        } catch (RuntimeException unavailable) {
            SecurityContextHolder.clearContext();
            LOG.atWarn().addKeyValue("component", "auth").log("Staff session lookup failed");
            errors.write(request, response, 503, ApiErrorCode.SERVICE_UNAVAILABLE);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
