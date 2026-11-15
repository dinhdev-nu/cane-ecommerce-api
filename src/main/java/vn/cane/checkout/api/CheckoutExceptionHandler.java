package vn.cane.checkout.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.checkout.application.CheckoutRejected;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

@RestControllerAdvice
@Profile("api & !worker")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CheckoutExceptionHandler {
    private final ApiErrors errors;
    private final JsonMapper json;

    public CheckoutExceptionHandler(ApiErrors errors, JsonMapper json) {
        this.errors = errors;
        this.json = json;
    }

    @ExceptionHandler(CheckoutRejected.class)
    ResponseEntity<?> rejected(CheckoutRejected failure, HttpServletRequest request) {
        var code = ApiErrorCode.valueOf(failure.code());
        int status = switch (code) {
            case SHOP_NOT_ACCEPTING -> 403;
            case QUOTE_CHANGED, ITEM_UNAVAILABLE, INSUFFICIENT_STOCK -> 409;
            default -> 422;
        };
        var quote = failure.quote();
        var items = quote.itemErrors().stream()
                .map(i -> new ApiErrors.ItemError(
                        i.sku(),
                        i.code(),
                        i.message(),
                        i.code().equals("INSUFFICIENT_STOCK")
                                ? failure.availability().stream()
                                        .filter(a -> a.sku().equals(i.sku()))
                                        .map(a -> a.available())
                                        .findFirst()
                                        .orElse(null)
                                : null))
                .toList();
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .header("X-Request-ID", ApiErrors.requestId(request))
                .body(errors.body(
                        request,
                        code,
                        quote.fieldErrors(),
                        items,
                        code == ApiErrorCode.QUOTE_CHANGED ? json.valueToTree(quote) : null));
    }
}
