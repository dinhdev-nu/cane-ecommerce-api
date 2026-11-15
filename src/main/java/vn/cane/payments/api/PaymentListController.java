package vn.cane.payments.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.payments.application.PaymentQuery;
import vn.cane.payments.application.PaymentReadService;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

@RestController
@Profile("api & !worker")
public final class PaymentListController {
    private final PaymentReadService service;
    private final StaffAuthorization authorization;

    public PaymentListController(PaymentReadService service, StaffAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/api/v1/admin/payments")
    PaymentReadService.Page list(@AuthenticationPrincipal StaffPrincipal principal, HttpServletRequest request) {
        authorization.requireOwner(principal);
        var parameters = request.getParameterMap();
        if (!Set.of("needsReview", "orderId", "page", "pageSize").containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(v -> v.length != 1))
            throw InputValidationException.invalid("query", "Invalid payment filters.");
        String review = request.getParameter("needsReview");
        if (review != null && !Set.of("true", "false").contains(review))
            throw InputValidationException.invalid("needsReview", "Expected true or false.");
        UUID order = request.getParameter("orderId") == null
                ? null
                : InputNormalizer.uuid(request.getParameter("orderId"), "orderId");
        return service.list(
                principal,
                new PaymentQuery(
                        review == null ? null : Boolean.valueOf(review),
                        order,
                        number(request, "page", 1, Integer.MAX_VALUE),
                        number(request, "pageSize", 20, 100)));
    }

    private static int number(HttpServletRequest request, String field, int defaultValue, int max) {
        String value = request.getParameter(field);
        if (value == null) return defaultValue;
        try {
            int number = Integer.parseInt(value);
            if (number >= 1 && number <= max && value.matches("[0-9]+")) return number;
        } catch (NumberFormatException ignored) {
        }
        throw InputValidationException.invalid(field, "Invalid payment page.");
    }
}
