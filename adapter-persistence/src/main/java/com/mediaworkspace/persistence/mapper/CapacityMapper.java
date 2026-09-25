package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.CapacitySnapshot;
import org.apache.ibatis.annotations.Param;

/** SQL for {@code capacity_counter}. */
public interface CapacityMapper {

    /**
     * Locks the counter row and reads it.
     *
     * <p>This row is the serialization point for task admission: two uploads finalizing at the same
     * moment block here instead of both observing a free slot and both taking it.
     */
    CapacitySnapshot lock(@Param("name") String name);

    CapacitySnapshot read(@Param("name") String name);

    int increment(@Param("name") String name);

    int decrement(@Param("name") String name);

    /** The configured maximum, kept on the row so the limit is data rather than a constant. */
    Integer maxCount(@Param("name") String name);
}
