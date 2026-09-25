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
}
