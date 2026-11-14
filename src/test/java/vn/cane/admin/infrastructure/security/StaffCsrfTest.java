package vn.cane.admin.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StaffCsrfTest {
    private final StaffCsrf csrf = new StaffCsrf();

    @Test
    void bootstrapsRawHeaderCookieWithDeferredXorAttributesAndNoHttpSession() {
        var request = request("GET", "/api/v1/admin/me", null);
        var response = new MockHttpServletResponse();
        var deferred = csrf.loadDeferredToken(request, response);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        csrf.handle(request, response, deferred);
        String token = response.getHeader(StaffCsrf.HEADER);
        assertThat(token).isEqualTo(deferred.get().getToken());
        assertThat(response.getHeader("Set-Cookie"))
                .contains(StaffCsrf.COOKIE + "=" + token, "Path=/", "Secure")
                .doesNotContain("HttpOnly", "Domain=", "JSESSIONID");
        // MockHttpServletResponse omits extended Servlet cookie attributes from its header string.
        assertThat(response.getCookie(StaffCsrf.COOKIE).getAttribute("SameSite"))
                .isEqualTo("Lax");
        assertThat(request.getSession(false)).isNull();
        var masked = (org.springframework.security.web.csrf.CsrfToken) request.getAttribute("_csrf");
        assertThat(masked.getToken()).isNotEqualTo(token);
        request.addHeader(StaffCsrf.HEADER, masked.getToken());
        assertThat(csrf.resolveCsrfTokenValue(request, deferred.get())).isNull();
    }

    @Test
    void onlyAcceptsOneCanonicalHeaderAndCookieNotBodyQueryOrMaskedToken() {
        String token = UUID.randomUUID().toString();
        var request = request("POST", "/api/v1/admin/auth/logout", token);
        request.setParameter("_csrf", token);
        assertThat(csrf.resolveCsrfTokenValue(request, csrf.loadToken(request))).isNull();
        request.addHeader(StaffCsrf.HEADER, token);
        assertThat(csrf.resolveCsrfTokenValue(request, csrf.loadToken(request))).isEqualTo(token);
        request.addHeader(StaffCsrf.HEADER, token);
        assertThat(csrf.resolveCsrfTokenValue(request, csrf.loadToken(request))).isNull();
        request.setCookies(new Cookie(StaffCsrf.COOKIE, token), new Cookie(StaffCsrf.COOKIE, token));
        assertThat(csrf.loadToken(request)).isNull();
        request.setCookies(new Cookie(StaffCsrf.COOKIE, "not-a-token"));
        assertThat(csrf.loadToken(request)).isNull();
    }

    @Test
    void customLoginRotatesAfterCommitAndOldHeaderCannotMatchNewCookie() throws Exception {
        String old = UUID.randomUUID().toString();
        var request = request("POST", "/api/v1/admin/auth/login", old);
        request.addHeader(StaffCsrf.HEADER, old);
        var response = new MockHttpServletResponse();
        csrf.handle(request, response, csrf.loadDeferredToken(request, response));
        csrf.afterLogin(
                UsernamePasswordAuthenticationToken.authenticated("fixture", null, java.util.List.of()),
                request,
                response);
        String fresh = response.getHeader(StaffCsrf.HEADER);
        assertThat(fresh).isNotBlank().isNotEqualTo(old);
        assertThat(response.getHeaders("Set-Cookie"))
                .hasSize(2)
                .last()
                .asString()
                .contains(StaffCsrf.COOKIE + "=" + fresh);
        var next = request("POST", "/api/v1/admin/auth/logout", fresh);
        next.addHeader(StaffCsrf.HEADER, old);
        var rejected = new MockHttpServletResponse();
        var filter = new CsrfFilter(csrf);
        filter.setRequestHandler(csrf);
        filter.doFilter(next, rejected, (req, res) -> {
            throw new AssertionError("Old header reached handler");
        });
        assertThat(rejected.getStatus()).isEqualTo(403);
        next = request("POST", "/api/v1/admin/auth/logout", fresh);
        next.addHeader(StaffCsrf.HEADER, fresh);
        filter.doFilter(next, new MockHttpServletResponse(), (req, res) -> {});
    }

    @Test
    void logoutClearsSameCookieScopeAndHooksRefuseActiveTransaction() {
        var request =
                request("POST", "/api/v1/admin/auth/logout", UUID.randomUUID().toString());
        var response = new MockHttpServletResponse();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> csrf.afterLogin(null, request, response))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> csrf.afterLogout(null, request, response))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        csrf.afterLogout(null, request, response);
        assertThat(response.getHeader("Set-Cookie"))
                .contains(StaffCsrf.COOKIE + "=;", "Max-Age=0", "Secure", "Path=/")
                .doesNotContain("HttpOnly", "Domain=", "JSESSIONID");
        assertThat(response.getCookie(StaffCsrf.COOKIE).getAttribute("SameSite"))
                .isEqualTo("Lax");
        assertThat(response.getHeader(StaffCsrf.HEADER)).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    private static MockHttpServletRequest request(String method, String path, String token) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (token != null) request.setCookies(new Cookie(StaffCsrf.COOKIE, token));
        return request;
    }
}
