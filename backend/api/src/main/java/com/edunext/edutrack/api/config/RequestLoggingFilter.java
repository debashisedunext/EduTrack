package com.edunext.edutrack.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * One line per HTTP request — method, path, status, duration — so a
 * developer running the API in a terminal can watch what the frontend is
 * actually calling without attaching a debugger.
 *
 * <p>4xx/5xx responses also log the response body (ProblemDetail's {@code
 * detail}, truncated), and an exception that escapes the exception handlers
 * entirely is logged with its stack trace before being rethrown, so it is
 * never silently swallowed.
 *
 * <p>Registered as a plain servlet filter ahead of Spring Security (see
 * {@link RequestLoggingConfig}), not inside the security filter chain, so a
 * request rejected by authentication or the scope guard is logged too.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.edunext.edutrack.http");

    /** Past this many bytes of an error body, the rest adds noise, not signal. */
    private static final int MAX_LOGGED_BODY = 500;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        String method = request.getMethod();
        String path = requestPath(request);
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(request, wrapped);
        } catch (Exception ex) {
            log.error("{} {} -> EXCEPTION after {}ms: {}",
                    method, path, elapsedMillis(startNanos), ex.toString(), ex);
            throw ex;
        } finally {
            logOutcome(method, path, wrapped, elapsedMillis(startNanos));
            wrapped.copyBodyToResponse();
        }
    }

    private void logOutcome(String method, String path, ContentCachingResponseWrapper wrapped, long ms) {
        int status = wrapped.getStatus();
        if (status >= 500) {
            log.error("{} {} -> {} ({}ms) {}", method, path, status, ms, errorBody(wrapped));
        } else if (status >= 400) {
            log.warn("{} {} -> {} ({}ms) {}", method, path, status, ms, errorBody(wrapped));
        } else {
            log.info("{} {} -> {} ({}ms)", method, path, status, ms);
        }
    }

    private String errorBody(ContentCachingResponseWrapper wrapped) {
        byte[] buf = wrapped.getContentAsByteArray();
        if (buf.length == 0) {
            return "";
        }
        int length = Math.min(buf.length, MAX_LOGGED_BODY);
        String body = new String(buf, 0, length, StandardCharsets.UTF_8);
        return buf.length > length ? body + "…" : body;
    }

    private String requestPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
