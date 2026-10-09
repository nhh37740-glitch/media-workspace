package com.mediaworkspace.api.web;

import com.mediaworkspace.api.config.SecurityConfiguration;
import com.mediaworkspace.api.security.GuestIdentityService;
import com.mediaworkspace.contracts.dto.CsrfResponse;
import com.mediaworkspace.contracts.dto.LoginRequest;
import com.mediaworkspace.contracts.dto.UserView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.application.error.ApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in, sign-out and the CSRF token.
 *
 * <p>Login is an ordinary controller method rather than the framework's form login, because the
 * contract exchanges JSON and expects a JSON error body on failure. The framework still performs
 * the credential check and the session handling; only the request and response shapes differ.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final CurrentUser currentUser;
    private final GuestIdentityService guestIdentity;

    public AuthController(AuthenticationManager authenticationManager, CurrentUser currentUser,
                           GuestIdentityService guestIdentity) {
        this.authenticationManager = authenticationManager;
        this.currentUser = currentUser;
        this.guestIdentity = guestIdentity;
    }

    /**
     * Hands the browser a CSRF token.
     *
     * <p>Available without a session: the token is needed to log in, so it cannot require being
     * logged in. The token still comes from the framework's own repository, not from a value this
     * class invents.
     */
    @GetMapping("/csrf")
    public CsrfResponse csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new IllegalStateException("the security filter chain did not supply a CSRF token");
        }
        return new CsrfResponse(token.getToken(), SecurityConfiguration.CSRF_HEADER);
    }

    /**
     * Signs in.
     *
     * <p>A wrong password and an unknown user are answered identically: the response must not tell
     * an attacker which of the two it was. The session id is changed on success, which is what stops
     * a session fixed before the login from being valid after it.
     */
    @PostMapping("/login")
    public ResponseEntity<UserView> login(@Valid @RequestBody LoginRequest request,
                                          HttpServletRequest httpRequest) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            // BadCredentialsException, DisabledException and LockedException all land here. The
            // response must not distinguish "no such user" from "wrong password", and it must not
            // reveal whether an account is disabled either.
            throw new ApplicationException(ApiErrorCode.INVALID_CREDENTIALS, "invalid credentials");
        }

        establishSession(authentication, httpRequest);

        // The principal's name is the user id, which is what every later authorization decision
        // keys on; the principal object itself is a framework type whose string form is not an
        // identifier.
        return ResponseEntity.ok(new UserView(authentication.getName(), request.username()));
    }

    /** Uses the configured viewer identity; the normal security chain still requires CSRF. */
    @PostMapping("/guest")
    public ResponseEntity<UserView> guest(HttpServletRequest request) {
        establishSession(guestIdentity.authenticate(), request);
        return ResponseEntity.ok(currentUser.requireView());
    }

    private static void establishSession(Authentication authentication, HttpServletRequest request) {
        request.getSession(true);
        // Authentication occurs inside this controller, after the session filter has run.
        // Rotate explicitly for guest creation and for upgrading a guest to password login.
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /** Signs out: the session is invalidated, so the cookie is useless afterwards. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserView me() {
        return currentUser.requireView();
    }
}
