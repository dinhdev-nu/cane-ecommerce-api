package vn.cane.admin.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.cane.admin.application.StaffSessionService.IssuedSession;

@Component
@Profile("api & !worker")
public final class SessionCookies {
    public static final String NAME = "__Host-cane-session";
    private final Clock clock;

    public SessionCookies(Clock clock) {
        this.clock = clock;
    }

    public String read(HttpServletRequest request) {
        String token = null;
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (!NAME.equals(cookie.getName())) continue;
            if (token != null) return ""; // Refuse ambiguous duplicate cookies.
            token = cookie.getValue();
        }
        return token;
    }

    public void issue(HttpServletResponse response, IssuedSession session) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Set the session cookie only after commit");
        long remaining = Math.clamp(
                Duration.between(clock.instant(), session.expiresAt()).toSeconds(), 0, 28_800);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie(session.token().value(), remaining).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    private static ResponseCookie cookie(String token, long maxAge) {
        return ResponseCookie.from(NAME, token)
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
