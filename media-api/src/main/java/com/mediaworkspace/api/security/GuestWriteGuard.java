package com.mediaworkspace.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaworkspace.api.web.RequestContext;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** Denies guest writes before reaching a controller, regardless of workspace role or URL. */
public class GuestWriteGuard extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final ObjectMapper mapper;
    private final RequestContext requestContext;

    public GuestWriteGuard(ObjectMapper mapper, RequestContext requestContext) {
        this.mapper = mapper;
        this.requestContext = requestContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean sessionAction = "POST".equals(request.getMethod())
                && ("/api/v1/auth/login".equals(path) || "/api/v1/auth/logout".equals(path));
        if (GuestIdentityService.isGuest(SecurityContextHolder.getContext().getAuthentication())
                && !SAFE.contains(request.getMethod()) && !sessionAction) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getOutputStream(), ErrorResponse.of(ApiErrorCode.FORBIDDEN,
                    "guest sessions are read-only", requestContext.currentRequestId()));
            return;
        }
        chain.doFilter(request, response);
    }
}
