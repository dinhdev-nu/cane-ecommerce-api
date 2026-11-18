package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.orders.application.AdminOrderQuery;
import vn.cane.orders.application.AdminOrderReadService;
import vn.cane.orders.application.AdminOrderView;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.TimeInputs;
import vn.cane.support.web.PublicReadRequest;

@RestController
@Profile("api & !worker")
public final class AdminOrderReadController {
    private static final Set<String> FILTERS = Set.of(
            "status",
            "paymentStatus",
            "fulfillmentMethod",
            "paymentMethod",
            "deliveryIssue",
            "cancelRequested",
            "search",
            "updatedSince",
            "page",
            "pageSize");
    private final AdminOrderReadService service;

    public AdminOrderReadController(AdminOrderReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/orders")
    AdminOrderView.Page list(@AuthenticationPrincipal StaffPrincipal principal, HttpServletRequest request)
            throws IOException {
        if (!FILTERS.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)
                || request.getInputStream().read() != -1)
            throw InputValidationException.invalid("query", "Invalid order filters.");
        return service.list(
                principal,
                new AdminOrderQuery(
                        option(
                                request,
                                "status",
                                Set.of(
                                        "awaiting_payment",
                                        "confirmed",
                                        "preparing",
                                        "shipping",
                                        "ready_for_pickup",
                                        "completed",
                                        "cancelled")),
                        option(request, "paymentStatus", Set.of("unpaid", "paid", "refund_pending", "refunded")),
                        option(request, "fulfillmentMethod", Set.of("delivery", "pickup")),
                        option(request, "paymentMethod", Set.of("cod", "bank_transfer")),
                        bool(request, "deliveryIssue"),
                        bool(request, "cancelRequested"),
                        request.getParameter("search") == null
                                ? null
                                : InputNormalizer.requiredText(request.getParameter("search"), 100, "search"),
                        request.getParameter("updatedSince") == null
                                ? null
                                : TimeInputs.utc(request.getParameter("updatedSince"), "updatedSince"),
                        number(request, "page", 1, Integer.MAX_VALUE),
                        number(request, "pageSize", 20, 100)));
    }

    @GetMapping("/api/v1/admin/orders/{id}")
    AdminOrderView.Detail detail(
            @AuthenticationPrincipal StaffPrincipal principal, @PathVariable String id, HttpServletRequest request)
            throws IOException {
        PublicReadRequest.requireEmpty(request);
        return service.detail(principal, InputNormalizer.uuid(id, "id"));
    }

    private static String option(HttpServletRequest request, String key, Set<String> allowed) {
        var value = request.getParameter(key);
        if (value != null && !allowed.contains(value))
            throw InputValidationException.invalid(key, "Invalid order filter.");
        return value;
    }

    private static Boolean bool(HttpServletRequest request, String key) {
        var value = option(request, key, Set.of("true", "false"));
        return value == null ? null : Boolean.valueOf(value);
    }

    private static int number(HttpServletRequest request, String key, int fallback, int max) {
        var value = request.getParameter(key);
        if (value == null) return fallback;
        try {
            int n = Integer.parseInt(value);
            if (n >= 1 && n <= max && value.matches("[0-9]+")) return n;
        } catch (NumberFormatException ignored) {
        }
        throw InputValidationException.invalid(key, "Invalid order page.");
    }
}
