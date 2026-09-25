package com.mediaworkspace.api.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.mediaworkspace.api.security.DatabaseUserDetailsService;
import com.mediaworkspace.application.port.repository.UserRepository;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;

/**
 * Web-layer details that the contract fixes: how JSON is read, which strings are trimmed, and how
 * credentials are verified.
 */
@Configuration
public class WebConfiguration {

    @Bean
    public DatabaseUserDetailsService databaseUserDetailsService(UserRepository users) {
        return new DatabaseUserDetailsService(users);
    }

    @Bean
    public AuthenticationManager authenticationManager(DatabaseUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // The provider's own message must not distinguish an unknown user from a wrong password.
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    /**
     * JSON settings for HTTP bodies.
     *
     * <p>Unknown members are <b>rejected</b>. The contract fixes the accepted field set, and a
     * silently ignored typo is how a client comes to believe it set something it did not. This
     * differs from the event codec, which tolerates additional members for forward compatibility;
     * the two directions have different requirements, so they are configured separately.
     *
     * <p>Strings are trimmed as they are read, so validation sees the value the client meant rather
     * than one padded with whitespace.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictHttpJson() {
        return builder -> {
            builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            builder.modulesToInstall(trimmingModule());
        };
    }

    private SimpleModule trimmingModule() {
        SimpleModule module = new SimpleModule("http-string-trimming");
        module.addDeserializer(String.class, new JsonDeserializer<>() {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                String value = parser.getValueAsString();
                return value == null ? null : value.trim();
            }
        });
        return module;
    }
}
