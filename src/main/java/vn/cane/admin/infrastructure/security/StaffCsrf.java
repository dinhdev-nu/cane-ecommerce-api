package vn.cane.admin.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cookie/deferred SPA integration with header-only input and explicit custom-login/logout hooks. */
@Component
@Profile("api & !worker")
public final class StaffCsrf implements CsrfTokenRepository, CsrfTokenRequestHandler {
    public static final String COOKIE = "__Host-cane-csrf";
    public static final String HEADER = "X-CSRF-Token";
    private static final String TOKEN_SUPPLIER = StaffCsrf.class.getName() + ".token";
    private static final Pattern TOKEN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private final CookieCsrfTokenRepository cookies = CookieCsrfTokenRepository.withHttpOnlyFalse();
    private final XorCsrfTokenRequestAttributeHandler attributes = new XorCsrfTokenRequestAttributeHandler();
    private final CsrfAuthenticationStrategy authentication = new CsrfAuthenticationStrategy(this);
    private final CsrfLogoutHandler logout = new CsrfLogoutHandler(this);

    public StaffCsrf() {
        cookies.setCookieName(COOKIE);
        cookies.setHeaderName(HEADER);
        cookies.setCookieCustomizer(
                cookie -> cookie.secure(true).sameSite("Lax").path("/"));
        authentication.setRequestHandler(this);
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return cookies.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        cookies.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        int count = 0;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) {
                    if (++count > 1 || !valid(cookie.getValue())) return null;
                }
            }
        }
        return count == 1 ? cookies.loadToken(request) : null;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> deferredToken) {
        attributes.handle(request, response, deferredToken);
        request.setAttribute(TOKEN_SUPPLIER, deferredToken);
        if (request.getMethod().equals("GET") && request.getServletPath().equals("/api/v1/admin/me")) {
            materialize(request, response);
        }
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        var headers = Collections.list(request.getHeaders(HEADER));
        return headers.size() == 1 && valid(headers.getFirst()) ? headers.getFirst() : null;
    }

    public String materialize(HttpServletRequest request, HttpServletResponse response) {
        @SuppressWarnings("unchecked")
        var deferred = (Supplier<CsrfToken>) request.getAttribute(TOKEN_SUPPLIER);
        if (deferred == null) throw new IllegalStateException("CSRF filter must run before the auth handler");
        String token = deferred.get().getToken();
        response.setHeader(HEADER, token);
        return token;
    }

    public void afterLogin(Authentication actor, HttpServletRequest request, HttpServletResponse response) {
        requireCommitted();
        authentication.onAuthentication(actor, request, response);
        materialize(request, response);
    }

    public void afterLogout(Authentication actor, HttpServletRequest request, HttpServletResponse response) {
        requireCommitted();
        logout.logout(request, response, actor);
        response.setHeader(HEADER, null);
    }

    private static boolean valid(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    private static void requireCommitted() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Auth cookies must change only after transaction commit");
        }
    }
}
