package vn.cane.orders.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.orders.application.PlaceOrderCommand;
import vn.cane.orders.application.PlaceOrderService;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

@RestController
@Profile("api & !worker")
public final class PlaceOrderController {
    private final PlaceOrderService service;
    private final OrderPlacementResponses responses;
    private final InputNormalizer normalizer;
    private final JsonMapper json;

    public PlaceOrderController(
            PlaceOrderService service, OrderPlacementResponses responses, InputNormalizer normalizer, JsonMapper json) {
        this.service = service;
        this.responses = responses;
        this.normalizer = normalizer;
        this.json = json;
    }

    @PostMapping("/api/v1/orders")
    ResponseEntity<OrderPlacementResponses.Response> place(@RequestBody JsonNode body, HttpServletRequest request) {
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        var headers = Collections.list(request.getHeaders("Idempotency-Key"));
        if (headers.size() != 1) throw InputValidationException.malformed("Idempotency-Key");
        var result = service.place(PlaceOrderCommand.parse(body, headers.getFirst(), normalizer, json));
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(responses.response(result.order()));
    }
}
