package vn.cane.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.cane.admin.application.StaffLoginService;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.admin.application.StaffSessionService;
import vn.cane.admin.infrastructure.security.SessionCookies;
import vn.cane.admin.infrastructure.security.StaffCsrf;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

@RestController
@Profile("api & !worker")
public final class AdminAuthController {
    private final StaffSessionService sessions;
    private final StaffLoginService logins;
    private final SessionCookies cookies;
    private final StaffCsrf csrf;
    private final ApiErrors errors;

    public AdminAuthController(
            StaffSessionService sessions,
            StaffLoginService logins,
            SessionCookies cookies,
            StaffCsrf csrf,
            ApiErrors errors) {
        this.sessions = sessions;
        this.logins = logins;
        this.cookies = cookies;
        this.csrf = csrf;
        this.errors = errors;
    }

    @GetMapping("/api/v1/admin/me")
    ResponseEntity<?> me(Authentication authentication, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        requireNoQuery(request);
        if (request.getInputStream().read() != -1) throw InputValidationException.malformed("body");
        String token = csrf.materialize(request, response);
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
            return ResponseEntity.status(401).body(errors.body(request, ApiErrorCode.AUTH_REQUIRED, Map.of()));
        }
        return ResponseEntity.ok(AdminAuthResponses.session(principal.actor(), token));
    }

    @PostMapping("/api/v1/admin/auth/login")
    ResponseEntity<?> login(
            @RequestBody AdminLoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        requireNoQuery(request);
        var authenticated = logins.login(body.username(), body.password(), cookies.read(request));
        if (authenticated.isEmpty()) {
            return ResponseEntity.status(401).body(errors.body(request, ApiErrorCode.AUTH_REQUIRED, Map.of()));
        }
        var result = authenticated.get();
        cookies.issue(response, result.session());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                result.actor(),
                null,
                List.of(new SimpleGrantedAuthority(
                        "ROLE_" + result.actor().role().name())));
        csrf.afterLogin(authentication, request, response);
        return ResponseEntity.ok(AdminAuthResponses.login(result.actor()));
    }

    @PostMapping("/api/v1/admin/auth/logout")
    ResponseEntity<Void> logout(
            @RequestBody EmptyBody body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireNoQuery(request);
        sessions.revoke(cookies.read(request));
        csrf.afterLogout(authentication, request, response);
        cookies.clear(response);
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
    }

    public record EmptyBody() {}
}
