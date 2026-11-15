package vn.cane.catalog.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.cane.catalog.application.PublicPricesService;
import vn.cane.support.web.PublicReadRequest;

@RestController
@Profile("api & !worker")
public final class PublicPricesController {
    private final PublicPricesService prices;

    public PublicPricesController(PublicPricesService prices) {
        this.prices = prices;
    }

    @GetMapping("/api/v1/prices")
    public PublicPricesService.Prices read(HttpServletRequest request) throws IOException {
        PublicReadRequest.requireEmpty(request);
        return prices.read();
    }
}
