package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.UserAccount;
import com.mediaworkspace.application.port.repository.UserRepository;
import com.mediaworkspace.persistence.mapper.UserMapper;

import java.util.Optional;

/** MyBatis implementation of {@link UserRepository}. */
public class UserRepositoryAdapter implements UserRepository {

    private final UserMapper mapper;

    public UserRepositoryAdapter(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username));
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    @Override
    public boolean existsById(String id) {
        return mapper.countById(id) > 0;
    }
}
