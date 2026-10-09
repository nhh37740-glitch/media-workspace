package com.mediaworkspace.api.security;

import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.port.repository.UserRepository;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Service;

/** Creates one pinned, password-free, read-only identity; never grants workspace membership. */
@Service
public class GuestIdentityService {
    public static final String AUTHORITY = "ROLE_GUEST";
    private final UserRepository users;
    private final boolean enabled;
    private final String userId;

    public GuestIdentityService(UserRepository users,
                                @Value("${mediaworkspace.guest.enabled:false}") boolean enabled,
                                @Value("${mediaworkspace.guest.user-id:}") String userId) {
        this.users = users;
        this.enabled = enabled;
        this.userId = userId;
    }

    public Authentication authenticate() {
        if (!enabled || userId == null || userId.isBlank()) {
            throw unavailable();
        }
        var account = users.findById(userId).orElseThrow(GuestIdentityService::unavailable);
        if (!account.id().equals(userId) || !account.username().equals("viewer") || !account.enabled()) {
            throw unavailable();
        }
        // The immutable user id is the principal name, exactly as in password login.
        // The stored password hash never enters the guest session or its response.
        var principal = User.withUsername(account.id()).password("")
                .authorities("ROLE_USER", AUTHORITY).build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    public static boolean isGuest(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> AUTHORITY.equals(authority.getAuthority()));
    }

    private static ApplicationException unavailable() {
        return new ApplicationException(ApiErrorCode.SERVICE_UNAVAILABLE, "guest browsing is not available");
    }
}
