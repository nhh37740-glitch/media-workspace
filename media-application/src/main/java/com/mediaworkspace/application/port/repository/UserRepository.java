package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.UserAccount;

import java.util.Optional;

/** Reads and writes {@code app_user}. */
public interface UserRepository {

    /** Looks up an account by login name, including its password hash. */
    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(String id);

    /** Whether a user id exists; used to reject a membership change for an unknown user. */
    boolean existsById(String id);
}
