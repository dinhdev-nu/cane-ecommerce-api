package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.cane.orders.application.GuestOrderView;
import vn.cane.orders.application.OrderLinkActions;
import vn.cane.orders.domain.InvalidOrderLinkException;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;

@RestController
@Profile("api & !worker")
public final class GuestOrderController {
    private final OrderLinkActions service;

    public GuestOrderController(OrderLinkActions service) {
        this.service = service;
    }

    @GetMapping("/api/v1/guest/order")
    GuestOrderView read(HttpServletRequest request) {
        return service.read(bearer(request));
    }

    @PostMapping("/api/v1/guest/order/cancel-request")
    OrderLinkActions.CancelResult cancel(@RequestBody JsonNode body, HttpServletRequest request) {
        String token = bearer(request);
        var object = JsonInputs.object(body, Set.of("reason"), "body");
        var reason = object.get("reason");
        return service.requestCancel(
                token,
                InputNormalizer.optionalNote(
                        reason == null || reason.isNull() ? null : JsonInputs.text(reason, "reason"), "reason"));
    }

    private static String bearer(HttpServletRequest request) {
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        var values = Collections.list(request.getHeaders("Authorization"));
        if (values.size() != 1) throw new InvalidOrderLinkException();
        String value = values.getFirst();
        if (value.length() < 8
                || !value.regionMatches(true, 0, "Bearer ", 0, 7)
                || value.substring(7).contains(" ")) throw new InvalidOrderLinkException();
        return value.substring(7);
    }
}
