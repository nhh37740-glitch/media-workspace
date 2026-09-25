package com.mediaworkspace.api.bootstrap;

import com.mediaworkspace.application.port.repository.UserRepository;
import com.mediaworkspace.application.port.security.PasswordHasher;
import com.mediaworkspace.persistence.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates the demonstration accounts.
 *
 * <p>Off unless explicitly switched on with {@code --mediaworkspace.bootstrap.enabled=true}, so a
 * normal start never writes users. There is no registration endpoint: accounts exist only because an
 * operator ran this command.
 *
 * <p>Passwords come from the environment, never from this file and never from a default. A missing
 * password for a requested account stops the run with a message naming the variable, rather than
 * creating an account with a guessable password. Nothing here logs a password, and the BCrypt hash
 * is the only form that reaches the database.
 *
 * <p>The four accounts exist because the acceptance cases need four roles: an owner, an editor, a
 * viewer and a person who is not a member at all.
 */
@Component
@ConditionalOnProperty(name = "mediaworkspace.bootstrap.enabled", havingValue = "true")
public class UserBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapRunner.class);

    /** Login name to the environment variable holding its password. */
    private static final Map<String, String> ACCOUNTS = new LinkedHashMap<>();

    static {
        ACCOUNTS.put("owner", "MW_PASSWORD_OWNER");
        ACCOUNTS.put("editor", "MW_PASSWORD_EDITOR");
        ACCOUNTS.put("viewer", "MW_PASSWORD_VIEWER");
        ACCOUNTS.put("outsider", "MW_PASSWORD_OUTSIDER");
    }

    private final UserRepository users;
    private final UserMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final boolean resetExisting;

    public UserBootstrapRunner(UserRepository users, UserMapper userMapper, PasswordHasher passwordHasher,
                               @Value("${mediaworkspace.bootstrap.reset-passwords:false}")
                               boolean resetExisting) {
        this.users = users;
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.resetExisting = resetExisting;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, String> account : ACCOUNTS.entrySet()) {
            String username = account.getKey();
            String password = System.getenv(account.getValue());
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("cannot create the account '" + username
                        + "': the environment variable " + account.getValue() + " is not set");
            }
            if (password.length() < 12) {
                throw new IllegalStateException("the password for '" + username
                        + "' is shorter than 12 characters");
            }
            var existing = users.findByUsername(username);
            if (existing.isPresent()) {
                if (resetExisting) {
                    // Explicitly requested, because rotating a password silently on every start
                    // would invalidate a session set up by hand minutes earlier.
                    userMapper.updatePassword(existing.get().id(), passwordHasher.hash(password));
                    log.info("account '{}' already exists; its password was replaced as requested",
                            username);
                } else {
                    log.info("account '{}' already exists; leaving it unchanged", username);
                }
                continue;
            }
            userMapper.insert(UUID.randomUUID().toString(), username,
                    passwordHasher.hash(password), true);
            log.info("created the account '{}'", username);
        }
        log.info("bootstrap finished; {} account(s) present", ACCOUNTS.size());
    }
}
