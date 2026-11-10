package vn.cane.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public final class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");
    private final ApiErrors errors;

    public RequestTraceFilter(ApiErrors errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = ApiErrors.requestId(request);
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("Cache-Control", "no-store");
        long started = System.nanoTime();
        String previous = MDC.get("requestId");
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } catch (Exception failure) {
            if (response.isCommitted()) {
                if (failure instanceof IOException io) {
                    throw io;
                }
                throw new ServletException("Request failed after response commit");
            }
            boolean tooLarge = JsonBodyFilter.isTooLarge(failure);
            response.resetBuffer();
            errors.write(
                    request,
                    response,
                    tooLarge ? 413 : 503,
                    tooLarge ? ApiErrorCode.INVALID_INPUT : ApiErrorCode.SERVICE_UNAVAILABLE);
            // Exception text/stack can contain SQL, headers, URLs or rejected values.
            LOG.atWarn()
                    .addKeyValue("component", "http")
                    .addKeyValue("errorCode", request.getAttribute(ApiErrors.ERROR_CODE))
                    .log("Request failed");
        } finally {
            String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern instanceof String template ? template : "unmapped";
            LOG.atInfo()
                    .addKeyValue("component", "http")
                    .addKeyValue("method", method)
                    .addKeyValue("route", route)
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("errorCode", request.getAttribute(ApiErrors.ERROR_CODE))
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log("Request completed");
            if (previous == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previous);
            }
        }
    }
}
