package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.UserAccount;
import org.apache.ibatis.annotations.Param;

/** SQL for {@code app_user}. */
public interface UserMapper {

    UserAccount findByUsername(@Param("username") String username);

    UserAccount findById(@Param("id") String id);

    int countById(@Param("id") String id);

    int insert(@Param("id") String id, @Param("username") String username,
               @Param("passwordHash") String passwordHash, @Param("enabled") boolean enabled);

    /**
     * Replaces a stored password hash.
     *
     * <p>Used only by the bootstrap command with an explicit opt-in, because silently rewriting a
     * password on every start would invalidate a session an operator set up by hand.
     */
    int updatePassword(@Param("id") String id, @Param("passwordHash") String passwordHash);
}
