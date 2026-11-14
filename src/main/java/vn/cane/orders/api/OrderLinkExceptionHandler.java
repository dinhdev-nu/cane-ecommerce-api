package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.cane.orders.domain.InvalidOrderLinkException;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

@RestControllerAdvice
@Profile("api & !worker")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class OrderLinkExceptionHandler {
    private final ApiErrors errors;

    public OrderLinkExceptionHandler(ApiErrors errors) {
        this.errors = errors;
    }

    @ExceptionHandler(InvalidOrderLinkException.class)
    ResponseEntity<Object> invalid(InvalidOrderLinkException failure, HttpServletRequest request) {
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("X-Request-ID", ApiErrors.requestId(request))
                .body(errors.body(request, ApiErrorCode.ORDER_LINK_INVALID, Map.of()));
    }
}
