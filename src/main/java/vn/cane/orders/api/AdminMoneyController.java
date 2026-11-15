package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;
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
import vn.cane.orders.application.OrderMoneyService;
import vn.cane.payments.application.MoneyCommands;
import vn.cane.support.idempotency.OperationReceipt;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

/** HTTP adapter for the order/receipt coordinator, including receipts without an order. */
@RestController
@Profile("api & !worker")
public final class AdminMoneyController {
    private final OrderMoneyService service;
    private final JsonMapper json;
    private final StaffAuthorization authorization;

    public AdminMoneyController(OrderMoneyService service, JsonMapper json, StaffAuthorization authorization) {
        this.service = service;
        this.json = json;
        this.authorization = authorization;
    }

    @PostMapping("/api/v1/admin/orders/{id}/confirm-payment")
    OperationReceipt confirm(
            @PathVariable String id,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal StaffPrincipal principal,
            HttpServletRequest request) {
        if (body != null
                && body.path("method").isString()
                && body.path("method").asString().strip().equals("bank"))
            authorization.requireBankConfirmation(principal);
        else authorization.requireCurrent(principal);
        return service.execute(principal, MoneyCommands.confirm(id(id), body, key(request), json));
    }

    @PostMapping("/api/v1/admin/payments")
    OperationReceipt record(
            @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal, HttpServletRequest request) {
        authorization.requireOwner(principal);
        return service.execute(principal, MoneyCommands.record(body, key(request), json));
    }

    @PostMapping("/api/v1/admin/payments/{id}/resolve")
    OperationReceipt resolve(
            @PathVariable String id,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal StaffPrincipal principal,
            HttpServletRequest request) {
        authorization.requireOwner(principal);
        return service.execute(principal, MoneyCommands.resolve(id(id), body, key(request), json));
    }

    @PostMapping("/api/v1/admin/payments/{id}/confirm-refund")
    OperationReceipt refund(
            @PathVariable String id,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal StaffPrincipal principal,
            HttpServletRequest request) {
        authorization.requireOwner(principal);
        return service.execute(principal, MoneyCommands.refund(id(id), body, key(request), json));
    }

    private static UUID id(String id) {
        return InputNormalizer.uuid(id, "id");
    }

    private static String key(HttpServletRequest request) {
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        var values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw InputValidationException.malformed("Idempotency-Key");
        return InputNormalizer.idempotencyKey(values.getFirst());
    }
}
