package vn.cane.checkout.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.cane.checkout.application.CheckoutInputs;
import vn.cane.checkout.application.QuoteService;
import vn.cane.checkout.domain.Quote;
import vn.cane.support.validation.InputValidationException;

@RestController
@Profile("api & !worker")
public final class QuoteController {
    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/checkout/quote")
    Quote quote(@RequestBody JsonNode body, HttpServletRequest request) {
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        return service.quote(CheckoutInputs.parse(body, false));
    }
}
