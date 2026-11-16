package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.orders.application.OrderLinkActions;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;

@RestController
@Profile("api & !worker")
public final class AdminOrderLinkController {
    private final OrderLinkActions service;
    private final StaffAuthorization authorization;

    public AdminOrderLinkController(OrderLinkActions service, StaffAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/api/v1/admin/orders/{id}/resend-link")
    OperationReceipt resend(
            @PathVariable String id,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal StaffPrincipal principal,
            HttpServletRequest request) {
        authorization.requireOwner(principal);
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw InputValidationException.malformed("Idempotency-Key");
        var object = JsonInputs.completeObject(body, Set.of("rotate"), "body");
        return service.resend(
                principal,
                InputNormalizer.uuid(id, "id"),
                JsonInputs.bool(object.get("rotate"), "rotate"),
                InputNormalizer.idempotencyKey(keys.getFirst()));
    }
}
