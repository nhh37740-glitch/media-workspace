package com.mediaworkspace.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaworkspace.api.web.RequestContext;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;

/**
 * HTTP security for the API.
 *
 * <p>Decisions worth naming:
 * <ul>
 *   <li><b>Sessions are stored in MySQL</b>, so a restart or a second API instance does not sign
 *       everybody out, and a logout is visible to every instance.</li>
 *   <li><b>CSRF is on, through the framework's own token mechanism.</b> The token is exposed at
 *       {@code GET /auth/csrf} and echoed in {@code X-CSRF-TOKEN} on every state-changing request.
 *       No hand-written filter bypasses the framework's checks.</li>
 *   <li><b>Authorization here is coarse on purpose.</b> This chain only separates public, anonymous
 *       and authenticated traffic. Every workspace-level decision is made again inside the
 *       transaction that performs the change, because a role checked only here would still be
 *       honoured after the membership was revoked.</li>
 *   <li><b>Error bodies are the same shape as everywhere else</b>, so a client parses one error
 *       format rather than one per layer.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    /** Header the browser must echo the CSRF token in. */
    public static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** Cookie holding a share-derived session; distinct from the sign-in session. */
    public static final String SHARE_COOKIE = "mw_share";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                                      RequestContext requestContext) throws Exception {
        http
                // No CORS configuration is added at all. The browser talks to the same origin
                // through Nginx, so a wildcard would only widen the attack surface.
                .cors(cors -> cors.disable())
                .csrf(csrf -> csrf
                        // Session-backed tokens: the value is returned by /auth/csrf and checked on
                        // every unsafe method, including login and logout. Share access is not
                        // exempt: the browser reaches /auth/csrf before exchanging a share token,
                        // so requiring the header there costs one extra request and removes a
                        // class of cross-site request that would otherwise reach a public route.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                // The context is written to the session as soon as the login endpoint sets it.
                .securityContext(context -> context.requireExplicitSave(false))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                        // Share access authenticates with its own cookie, not with a sign-in session.
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, objectMapper, requestContext,
                                        ApiErrorCode.AUTH_REQUIRED, "authentication is required"))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(response, objectMapper, requestContext,
                                        ApiErrorCode.FORBIDDEN, "this operation is not permitted")))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                                   RequestContext requestContext, ApiErrorCode code, String message)
            throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(code, message, requestContext.currentRequestId()));
    }
}
