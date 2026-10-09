package com.mediaworkspace.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaworkspace.api.config.SecurityConfiguration;
import com.mediaworkspace.api.web.AuthController;
import com.mediaworkspace.api.web.CurrentUser;
import com.mediaworkspace.api.web.RequestContext;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.UserAccount;
import com.mediaworkspace.application.port.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Runs the actual Spring security chain, not a frontend permission simulation. */
class GuestSessionSecurityTest {
    private static final String VIEWER = "ee679d14-ed10-4ea6-b136-11eaf945da9a";
    private static final String OWNER = "3bda23af-e08e-4b60-8fb2-0d880a123b68";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private UserRepository users;
    private AtomicInteger mutations;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("guest-test", Map.of(
                "mediaworkspace.guest.enabled", "true", "mediaworkspace.guest.user-id", VIEWER)));
        context.register(TestConfiguration.class);
        context.refresh();
        users = context.getBean(UserRepository.class);
        mutations = context.getBean(AtomicInteger.class);
        when(users.findById(VIEWER)).thenReturn(Optional.of(new UserAccount(VIEWER, "viewer", "private-hash-sentinel", true)));
        when(users.findById(OWNER)).thenReturn(Optional.of(new UserAccount(OWNER, "owner", "private-owner-hash", true)));
        var owner = User.withUsername(OWNER).password("").authorities("ROLE_USER").build();
        when(context.getBean(AuthenticationManager.class).authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities()));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void guestRequiresCsrfAndAnonymousCannotReadPrivateEndpoints() throws Exception {
        mvc.perform(post("/api/v1/auth/guest")).andExpect(status().isForbidden());
        verifyNoInteractions(users);
        mvc.perform(get("/api/v1/probe/read")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestRotatesSessionAndMarkerSurvivesSessionSerialization() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession();
        String originalId = session.getId();
        String token = token(csrf);
        mvc.perform(post("/api/v1/auth/guest").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guest").value(true))
                .andExpect(jsonPath("$.userId").value(VIEWER));
        assertThat(session.getId()).isNotEqualTo(originalId);
        String key = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
        SecurityContext saved = (SecurityContext) session.getAttribute(key);
        assertThat(GuestIdentityService.isGuest(saved.getAuthentication())).isTrue();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(saved); }
        MockHttpSession restored = new MockHttpSession();
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored.setAttribute(key, input.readObject());
        }
        MvcResult identity = mvc.perform(get("/api/v1/auth/me").session(restored))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guest").value(true))
                .andExpect(jsonPath("$.username").value("viewer")).andReturn();
        assertThat(identity.getResponse().getContentAsString()).doesNotContain("private-hash-sentinel");
        mvc.perform(get("/api/v1/probe/read").session(restored)).andExpect(status().isOk());
    }

    @Test
    void validCsrfDoesNotAllowAnyGuestMutation() throws Exception {
        MockHttpSession session = guestSession();
        String token = token(mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn());
        for (String path : new String[]{"/api/v1/spaces", "/api/v1/uploads", "/api/v1/uploads/id/chunks/0",
                "/api/v1/media/id", "/api/v1/media/id/shares", "/api/v1/spaces/id/members/user",
                "/api/v1/tasks/id/retry", "/api/v1/tasks/id/cancel", "/api/v1/future-write"}) {
            for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
                mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path)
                        .session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
        assertThat(mutations).hasValue(0);
    }

    @Test
    void traceIsRejectedByDefaultFirewallBeforeController() throws Exception {
        MockHttpSession session = guestSession();
        String token = token(mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn());
        // StrictHttpFirewall rejects TRACE before the guest guard can return its JSON 403.
        mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.TRACE, "/api/v1/probe/write")
                        .session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isBadRequest());
        assertThat(mutations).hasValue(0);
    }

    @Test
    void passwordLoginUpgradesGuestAndLogoutInvalidatesSession() throws Exception {
        MockHttpSession session = guestSession();
        String oldId = session.getId();
        String token = token(mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn());
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content("{\"username\":\"owner\",\"password\":\"test-only-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guest").value(false));
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(jsonPath("$.userId").value(OWNER))
                .andExpect(jsonPath("$.guest").value(false));
        mvc.perform(post("/api/v1/probe/write").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isOk());
        assertThat(mutations).hasValue(1);
        mvc.perform(post("/api/v1/auth/logout").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void disabledMismatchedAndDisabledAccountPinsAreRefused() {
        assertThatThrownBy(() -> new GuestIdentityService(users, false, VIEWER).authenticate())
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> new GuestIdentityService(users, true, "").authenticate())
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> new GuestIdentityService(users, true, OWNER).authenticate())
                .isInstanceOf(ApplicationException.class);
        when(users.findById(VIEWER)).thenReturn(Optional.of(new UserAccount(VIEWER, "viewer", "unused", false)));
        assertThatThrownBy(() -> context.getBean(GuestIdentityService.class).authenticate())
                .isInstanceOf(ApplicationException.class);
    }

    private MockHttpSession guestSession() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession();
        mvc.perform(post("/api/v1/auth/guest").session(session).header("X-CSRF-TOKEN", token(csrf)))
                .andExpect(status().isOk());
        return session;
    }

    private static String token(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfiguration.class, AuthController.class, CurrentUser.class, GuestIdentityService.class, Probe.class})
    static class TestConfiguration {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean AuthenticationManager authenticationManager() { return mock(AuthenticationManager.class); }
        @Bean DatabaseUserDetailsService details(UserRepository users) { return new DatabaseUserDetailsService(users); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean RequestContext requestContext() { return new RequestContext(); }
        @Bean AtomicInteger mutations() { return new AtomicInteger(); }
    }

    @RestController
    static class Probe {
        private final AtomicInteger mutations;
        Probe(AtomicInteger mutations) { this.mutations = mutations; }
        @RequestMapping("/api/v1/**")
        Map<String, String> request(jakarta.servlet.http.HttpServletRequest request) {
            if (!request.getMethod().equals("GET")) { mutations.incrementAndGet(); }
            return Map.of("status", "allowed");
        }
    }
}
