package vn.cane.catalog.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.catalog.application.AdminCatalogService;
import vn.cane.catalog.application.AdminCatalogView;
import vn.cane.catalog.application.StockAdjustment;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.validation.NumericInputs;
import vn.cane.support.web.PublicReadRequest;

@RestController
@Profile("api & !worker")
public final class AdminCatalogController {
    private final AdminCatalogService service;
    private final StaffAuthorization authorization;
    private final JsonMapper json;

    public AdminCatalogController(AdminCatalogService service, StaffAuthorization authorization, JsonMapper json) {
        this.service = service;
        this.authorization = authorization;
        this.json = json;
    }

    @GetMapping("/api/v1/admin/skus")
    AdminCatalogView.Skus skus(@AuthenticationPrincipal StaffPrincipal principal, HttpServletRequest request)
            throws IOException {
        PublicReadRequest.requireEmpty(request);
        return service.read(principal);
    }

    @PatchMapping("/api/v1/admin/skus/{sku}")
    AdminCatalogView.Sku patch(
            @AuthenticationPrincipal StaffPrincipal principal,
            @PathVariable String sku,
            @RequestBody JsonNode input,
            HttpServletRequest request) {
        authorization.requireOwner(principal);
        noQuery(request);
        var body = JsonInputs.object(input, Set.of("priceVnd", "isActive"), "body");
        if (body.isEmpty()) throw InputValidationException.invalid("body", "Cần giá hoặc trạng thái bán.");
        Long price = body.has("priceVnd")
                ? NumericInputs.vnd(JsonInputs.integer(body.get("priceVnd"), "priceVnd"), true, "priceVnd")
                : null;
        Boolean active = body.has("isActive") ? JsonInputs.bool(body.get("isActive"), "isActive") : null;
        return service.update(principal, InputNormalizer.requiredText(sku, 100, "sku"), price, active);
    }

    @PostMapping("/api/v1/admin/stock-adjustments")
    OperationReceipt adjust(
            @AuthenticationPrincipal StaffPrincipal principal, @RequestBody JsonNode body, HttpServletRequest request) {
        authorization.requireOwner(principal);
        noQuery(request);
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw InputValidationException.malformed("Idempotency-Key");
        return service.adjust(principal, StockAdjustment.parse(body, keys.getFirst(), json));
    }

    @GetMapping("/api/v1/admin/stock-movements")
    AdminCatalogView.Movements movements(@AuthenticationPrincipal StaffPrincipal principal, HttpServletRequest request)
            throws IOException {
        if (!Set.of("sku", "orderId", "reason", "page", "pageSize")
                        .containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)
                || request.getInputStream().read() != -1)
            throw InputValidationException.invalid("query", "Invalid ledger filters.");
        var reason = request.getParameter("reason");
        if (reason != null
                && !Set.of("order", "cancel", "delivery_return", "restock", "adjust")
                        .contains(reason)) throw InputValidationException.invalid("reason", "Invalid movement kind.");
        var sku = request.getParameter("sku");
        var order = request.getParameter("orderId");
        return service.movements(
                principal,
                new AdminCatalogView.Query(
                        sku == null ? null : InputNormalizer.requiredText(sku, 100, "sku"),
                        order == null ? null : InputNormalizer.uuid(order, "orderId"),
                        reason,
                        page(request, "page", 1, Integer.MAX_VALUE),
                        page(request, "pageSize", 20, 100)));
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
    }

    private static int page(HttpServletRequest request, String key, int fallback, int max) {
        var value = request.getParameter(key);
        if (value == null) return fallback;
        try {
            int n = Integer.parseInt(value);
            if (n >= 1 && n <= max && value.matches("[0-9]+")) return n;
        } catch (NumberFormatException ignored) {
        }
        throw InputValidationException.invalid(key, "Invalid ledger page.");
    }
}
