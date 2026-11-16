package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.orders.application.OrderMoneyService;
import vn.cane.payments.domain.MoneyRejected;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

@RestControllerAdvice
@Profile("api & !worker")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MoneyExceptionHandler {
    private final ApiErrors errors;
    private final JsonMapper json;

    public MoneyExceptionHandler(ApiErrors errors, JsonMapper json) {
        this.errors = errors;
        this.json = json;
    }

    @ExceptionHandler(MoneyRejected.class)
    ResponseEntity<Object> rejected(MoneyRejected failure, HttpServletRequest request) {
        ApiErrorCode code = switch (failure.reason()) {
            case STATE -> ApiErrorCode.STATE_CONFLICT;
            case METHOD -> ApiErrorCode.PAYMENT_METHOD_MISMATCH;
            case PAYMENT -> ApiErrorCode.PAYMENT_CONFLICT;
            case NOT_FOUND -> ApiErrorCode.NOT_FOUND;
        };
        var current = failure.orderId() == null
                ? null
                : json.createObjectNode()
                        .put("orderId", failure.orderId().toString())
                        .put("status", failure.status());
        return ResponseEntity.status(failure.reason() == MoneyRejected.Reason.NOT_FOUND ? 404 : 409)
                .body(errors.body(request, code, Map.of(), java.util.List.of(), null, current));
    }

    @ExceptionHandler(OrderMoneyService.ReceiptContention.class)
    ResponseEntity<Object> contention(HttpServletRequest request) {
        return ResponseEntity.status(503).body(errors.body(request, ApiErrorCode.SERVICE_UNAVAILABLE, Map.of()));
    }
}
