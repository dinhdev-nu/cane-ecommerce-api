package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.orders.application.OrderLifecycleCommand;
import vn.cane.orders.application.OrderLifecycleService;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

@RestController
@Profile("api & !worker")
public final class OrderLifecycleController {
    private static final Map<String, OrderLifecycleCommand.Action> ACTIONS = java.util.Arrays.stream(
                    OrderLifecycleCommand.Action.values())
            .collect(java.util.stream.Collectors.toUnmodifiableMap(OrderLifecycleCommand.Action::route, a -> a));
    private final OrderLifecycleService service;
    private final StaffAuthorization authorization;
    private final JsonMapper json;

    public OrderLifecycleController(OrderLifecycleService service, StaffAuthorization authorization, JsonMapper json) {
        this.service = service;
        this.authorization = authorization;
        this.json = json;
    }

    @PostMapping(
            "/api/v1/admin/orders/{id}/{action:prepare|ship|ready-for-pickup|hand-over|deliver|delivery-issue|close-delivery-issue|cancel}")
    OperationReceipt execute(
            @PathVariable String id,
            @PathVariable String action,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal StaffPrincipal principal,
            HttpServletRequest request) {
        var operation = ACTIONS.get(action);
        if (operation == OrderLifecycleCommand.Action.CLOSE_ISSUE) authorization.requireOwner(principal);
        else authorization.requireCurrent(principal);
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw InputValidationException.malformed("Idempotency-Key");
        return service.execute(
                principal,
                OrderLifecycleCommand.parse(InputNormalizer.uuid(id, "id"), operation, body, keys.getFirst(), json));
    }
}
