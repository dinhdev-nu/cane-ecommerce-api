package vn.cane.catalog.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.catalog.domain.CatalogRejected;
import vn.cane.support.web.ApiErrorCode;
import vn.cane.support.web.ApiErrors;

@RestControllerAdvice
@Profile("api & !worker")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CatalogExceptionHandler {
    private final ApiErrors errors;
    private final JsonMapper json;

    public CatalogExceptionHandler(ApiErrors errors, JsonMapper json) {
        this.errors = errors;
        this.json = json;
    }

    @ExceptionHandler(CatalogRejected.class)
    ResponseEntity<Object> rejected(CatalogRejected failure, HttpServletRequest request) {
        boolean missing = failure.currentStock() == null;
        var current = missing ? null : json.createObjectNode().put("stockQty", failure.currentStock());
        return ResponseEntity.status(missing ? 404 : 409)
                .header("Cache-Control", "no-store")
                .body(errors.body(
                        request,
                        missing ? ApiErrorCode.NOT_FOUND : ApiErrorCode.STOCK_CONFLICT,
                        Map.of(),
                        java.util.List.of(),
                        null,
                        current));
    }
}
