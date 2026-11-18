package vn.cane.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.cane.admin.application.AdminSettingsService;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.support.validation.InputValidationException;
import vn.cane.support.validation.JsonInputs;
import vn.cane.support.web.PublicReadRequest;

@RestController
@Profile("api & !worker")
public final class AdminSettingsController {
    private final AdminSettingsService service;
    private final StaffAuthorization authorization;

    public AdminSettingsController(AdminSettingsService service, StaffAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/api/v1/admin/settings")
    AdminSettingsService.Values read(@AuthenticationPrincipal StaffPrincipal principal, HttpServletRequest request)
            throws IOException {
        authorization.requireOwner(principal);
        PublicReadRequest.requireEmpty(request);
        return service.read(principal);
    }

    @PutMapping("/api/v1/admin/settings")
    AdminSettingsService.Values update(
            @AuthenticationPrincipal StaffPrincipal principal,
            @RequestBody JsonNode input,
            HttpServletRequest request) {
        authorization.requireOwner(principal);
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        var body = JsonInputs.completeObject(input, Set.of("values"), "body");
        return service.update(principal, body.get("values"));
    }
}
