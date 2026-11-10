package vn.cane.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after Security; bounds both declared-length and streamed/chunked JSON bodies. */
public final class JsonBodyFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    private final ApiErrors errors;

    public JsonBodyFilter(ApiErrors errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            errors.write(request, response, 413, ApiErrorCode.INVALID_INPUT);
            return;
        }
        boolean hasBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
        if (hasBody && !isJson(request.getContentType())) {
            errors.write(request, response, 415, ApiErrorCode.INVALID_INPUT);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    public static boolean isTooLarge(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJson(String contentType) {
        try {
            MediaType media = contentType == null ? null : MediaType.parseMediaType(contentType);
            return media != null
                    && media.getType().equalsIgnoreCase("application")
                    && media.getSubtype().equalsIgnoreCase("json");
        } catch (InvalidMediaTypeException ignored) {
            return false;
        }
    }

    private static final class BodyTooLargeException extends RuntimeException {
        private BodyTooLargeException() {
            super("Request body exceeds the configured limit");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream input;

        private LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (input == null) {
                input = new LimitedStream(super.getInputStream());
            }
            return input;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset =
                    getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private int consumed;

        private LimitedStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            checkLimit();
            int value = delegate.read();
            if (value >= 0) {
                addBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkLimit();
            int count = delegate.read(bytes, offset, Math.min(length, MAX_BODY_BYTES - consumed + 1));
            if (count > 0) {
                addBytes(count);
            }
            return count;
        }

        private void addBytes(int count) {
            consumed += count;
            checkLimit();
        }

        private void checkLimit() {
            if (consumed > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
