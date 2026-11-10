package vn.cane.support.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** One allowlisted error representation for filters, Security and MVC. */
@Component
@Profile("api & !worker")
public final class ApiErrors {
    public static final String REQUEST_ID = ApiErrors.class.getName() + ".requestId";
    public static final String ERROR_CODE = ApiErrors.class.getName() + ".errorCode";
    private final JsonMapper mapper;

    public ApiErrors(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public ErrorBody body(HttpServletRequest request, ApiErrorCode code, Map<String, String> fieldErrors) {
        request.setAttribute(ERROR_CODE, code.name());
        return new ErrorBody(
                new ErrorDetail(code.name(), code.message(), Map.copyOf(fieldErrors), List.of(), requestId(request)));
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, ApiErrorCode code)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-ID", requestId(request));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), body(request, code, Map.of()));
    }

    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID);
        if (existing instanceof String id) {
            return id;
        }
        String id = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, id);
        return id;
    }

    public record ErrorBody(ErrorDetail error) {}

    public record ErrorDetail(
            String code,
            String message,
            Map<String, String> fieldErrors,
            List<ItemError> itemErrors,
            String requestId) {}

    public record ItemError(String sku, String code, String message, Integer available) {}
}
