package com.mediaworkspace.api.web;

import com.mediaworkspace.contracts.trace.TraceFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes the correlation context for one request and tears it down afterwards.
 *
 * <p>The cleanup in {@code finally} is the point: a thread from the container pool serves many
 * requests, and an MDC entry left behind would attach one user's identifiers to the next user's log
 * records. That is a correctness problem, not tidiness - it makes the logs actively misleading.
 *
 * <p>The response echoes the generated id so a client can quote it in a report. A client-supplied
 * {@code X-Request-Id} is deliberately ignored; the request context explains why.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private final RequestContext requestContext;

    public RequestIdFilter(RequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = requestContext.begin(request);
        response.setHeader(TraceFields.REQUEST_ID_RESPONSE_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            requestContext.end();
            // Anything the application layer set for this request must not survive it.
            MDC.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return false;
    }
}
