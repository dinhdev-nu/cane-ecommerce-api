package vn.cane.admin.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

/** Session/permission failures raised by application guards use the same HTTP representation. */
@RestControllerAdvice
@Order(0)
@Profile("api & !worker")
public final class StaffAccessErrors {
    private final SessionCookies cookies;
    private final ApiErrors errors;

    public StaffAccessErrors(SessionCookies cookies, ApiErrors errors) {
        this.cookies = cookies;
        this.errors = errors;
    }

    @ExceptionHandler(AuthenticationException.class)
    void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        cookies.clear(response);
        errors.write(request, response, 401, ApiErrorCode.AUTH_REQUIRED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        errors.write(request, response, 403, ApiErrorCode.FORBIDDEN);
    }
}
