package vn.cane.support.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.cane.support.idempotency.IdempotencyMismatchException;
import vn.cane.support.transaction.RequestInProgressException;
import vn.cane.support.validation.InputValidationException;

@RestControllerAdvice
@Profile("api & !worker")
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiErrors errors;

    public ApiExceptionHandler(ApiErrors errors) {
        this.errors = errors;
    }

    @ExceptionHandler(InputValidationException.class)
    ResponseEntity<Object> invalid(InputValidationException failure, HttpServletRequest request) {
        return response(request, failure.isMalformed() ? 400 : 422, ApiErrorCode.INVALID_INPUT, failure.fieldErrors());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure, HttpServletRequest request) {
        if (JsonBodyFilter.isTooLarge(failure)) {
            return response(request, 413, ApiErrorCode.INVALID_INPUT, Map.of());
        }
        warnUnavailable();
        return response(request, 503, ApiErrorCode.SERVICE_UNAVAILABLE, Map.of());
    }

    @ExceptionHandler(RequestInProgressException.class)
    ResponseEntity<Object> inProgress(RequestInProgressException failure, HttpServletRequest request) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");
        return response(request, 409, ApiErrorCode.REQUEST_IN_PROGRESS, Map.of(), headers);
    }

    @ExceptionHandler(IdempotencyMismatchException.class)
    ResponseEntity<Object> mismatch(IdempotencyMismatchException failure, HttpServletRequest request) {
        return response(request, 409, ApiErrorCode.IDEMPOTENCY_MISMATCH, Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException failure,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest webRequest) {
        var fields = new LinkedHashMap<String, String>();
        failure.getBindingResult()
                .getFieldErrors()
                .forEach(field -> fields.put(field.getField(), "Trường dữ liệu không hợp lệ."));
        return response(
                ((ServletWebRequest) webRequest).getRequest(), 422, ApiErrorCode.INVALID_INPUT, fields, headers);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception failure, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        var request = ((ServletWebRequest) webRequest).getRequest();
        int safeStatus = JsonBodyFilter.isTooLarge(failure) ? 413 : status.value();
        ApiErrorCode code;
        if (safeStatus >= 500) {
            safeStatus = 503;
            code = ApiErrorCode.SERVICE_UNAVAILABLE;
            warnUnavailable();
        } else if (safeStatus == 404) {
            code = ApiErrorCode.NOT_FOUND;
        } else {
            code = ApiErrorCode.INVALID_INPUT;
        }
        return response(request, safeStatus, code, Map.of(), headers);
    }

    private ResponseEntity<Object> response(
            HttpServletRequest request, int status, ApiErrorCode code, Map<String, String> fieldErrors) {
        return response(request, status, code, fieldErrors, HttpHeaders.EMPTY);
    }

    private ResponseEntity<Object> response(
            HttpServletRequest request,
            int status,
            ApiErrorCode code,
            Map<String, String> fieldErrors,
            HttpHeaders frameworkHeaders) {
        return ResponseEntity.status(status)
                .headers(frameworkHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("X-Request-ID", ApiErrors.requestId(request))
                .body(errors.body(request, code, fieldErrors));
    }

    private static void warnUnavailable() {
        LOG.atWarn()
                .addKeyValue("component", "mvc")
                .addKeyValue("errorCode", "SERVICE_UNAVAILABLE")
                .log("Request failed");
    }
}
