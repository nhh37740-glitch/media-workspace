package com.mediaworkspace.api.web;

import com.mediaworkspace.api.security.DatabaseUserDetailsService;
import com.mediaworkspace.contracts.dto.UserView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.error.ErrorResponse;
import com.mediaworkspace.application.error.ApplicationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the acting user for a request.
 *
 * <p>The identifier comes from the session's authentication, never from a path, a body or a header.
 * A request that wants to act as somebody else has no way to say so.
 */
@Component
public class CurrentUser {

    private final DatabaseUserDetailsService userDetailsService;

    public CurrentUser(DatabaseUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    /**
     * The signed-in user's id.
     *
     * <p>{@code getName()} is used, not the principal object. The principal is a framework
     * {@code UserDetails} whose {@code toString()} renders something like
     * {@code User [Username=…, Password=[PROTECTED], …]}; passing that as an identifier produced a
     * value far too long for the column it was written to, and the failure surfaced as a database
     * truncation rather than as an authentication problem. The name is the identifier this project
     * put into the principal in the first place.
     *
     * @throws ApplicationException with {@code AUTH_REQUIRED} when there is no session
     */
    public String requireId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ApplicationException(ApiErrorCode.AUTH_REQUIRED, "authentication is required");
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()) {
            throw new ApplicationException(ApiErrorCode.AUTH_REQUIRED, "authentication is required");
        }
        return name;
    }

    /** The signed-in user as the identity endpoint reports it. */
    public UserView requireView() {
        String id = requireId();
        return new UserView(id, userDetailsService.usernameOf(id));
    }
}
