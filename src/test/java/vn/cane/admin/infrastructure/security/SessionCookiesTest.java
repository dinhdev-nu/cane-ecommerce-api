package vn.cane.admin.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.cane.admin.application.StaffSessionService.IssuedSession;
import vn.cane.admin.domain.SessionToken;
import vn.cane.fixture.MutableClock;

class SessionCookiesTest {
    @Test
    void cookieIsHostOnlySecureHttpOnlyAndNeverOutlivesDatabaseExpiry() {
        Instant now = Instant.parse("2026-11-14T00:00:00Z");
        var clock = new MutableClock(now);
        var cookies = new SessionCookies(clock);
        var issued = mock(IssuedSession.class);
        when(issued.token()).thenReturn(SessionToken.generate(new SecureRandom()));
        when(issued.expiresAt()).thenReturn(now.plus(Duration.ofHours(8)));
        var response = new MockHttpServletResponse();
        cookies.issue(response, issued);
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith(SessionCookies.NAME + "=")
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=28800")
                .doesNotContain("Domain=");
        clock.advance(Duration.ofMillis(1500));
        response = new MockHttpServletResponse();
        cookies.issue(response, issued);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=28798");
        clock.advance(Duration.ofHours(9));
        response = new MockHttpServletResponse();
        cookies.issue(response, issued);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void duplicateCookiesAreRejectedAndClearUsesTheSameScope() {
        var cookies = new SessionCookies(java.time.Clock.systemUTC());
        var request = new MockHttpServletRequest();
        assertThat(cookies.read(request)).isNull();
        request.setCookies(new Cookie(SessionCookies.NAME, "one"), new Cookie(SessionCookies.NAME, "two"));
        assertThat(cookies.read(request)).isEmpty();
        var response = new MockHttpServletResponse();
        cookies.clear(response);
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith(SessionCookies.NAME + "=;")
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=0")
                .doesNotContain("Domain=");
    }

    @Test
    void issuingCookieDuringAnUncommittedTransactionIsRefused() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new SessionCookies(java.time.Clock.systemUTC())
                            .issue(new MockHttpServletResponse(), mock(IssuedSession.class)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
