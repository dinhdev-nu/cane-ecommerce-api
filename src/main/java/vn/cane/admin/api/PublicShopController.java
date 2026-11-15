package vn.cane.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.cane.admin.application.PublicShopService;
import vn.cane.support.web.PublicReadRequest;

@RestController
@Profile("api & !worker")
public final class PublicShopController {
    private final PublicShopService shop;

    public PublicShopController(PublicShopService shop) {
        this.shop = shop;
    }

    @GetMapping("/api/v1/shop")
    public PublicShopService.Shop read(HttpServletRequest request) throws IOException {
        PublicReadRequest.requireEmpty(request);
        return shop.read();
    }
}
