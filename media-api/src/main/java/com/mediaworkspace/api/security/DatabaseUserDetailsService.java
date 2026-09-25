package com.mediaworkspace.api.security;

import com.mediaworkspace.application.model.UserAccount;
import com.mediaworkspace.application.port.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

/**
 * Loads sign-in identities from the database.
 *
 * <p>The principal name is the user id rather than the login name, so every later authorization
 * decision keys on an immutable identifier. Workspace roles are not granted here: they are per
 * workspace and are checked inside the transaction that performs each change, not turned into
 * authorities that would go stale the moment a membership is revoked.
 */
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public DatabaseUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("no such user"));
        return new User(account.id(), account.passwordHash(), account.enabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** Resolves a stored id to its login name, for the identity endpoint. */
    public String usernameOf(String userId) {
        return users.findById(userId).map(UserAccount::username).orElse("");
    }
}
