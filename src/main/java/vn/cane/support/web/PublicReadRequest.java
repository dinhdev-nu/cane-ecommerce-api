package vn.cane.support.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import vn.cane.support.validation.InputValidationException;

public final class PublicReadRequest {
    private PublicReadRequest() {}

    public static void requireEmpty(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw InputValidationException.malformed("query");
        if (request.getInputStream().read() != -1) throw InputValidationException.malformed("body");
    }
}
