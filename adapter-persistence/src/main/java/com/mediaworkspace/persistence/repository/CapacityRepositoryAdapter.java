package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.CapacitySnapshot;
import com.mediaworkspace.application.port.repository.CapacityRepository;
import com.mediaworkspace.persistence.mapper.CapacityMapper;
import com.mediaworkspace.persistence.mapper.TaskMapper;

import java.util.Optional;

/** MyBatis implementation of {@link CapacityRepository}. */
public class CapacityRepositoryAdapter implements CapacityRepository {

    private final CapacityMapper mapper;
    private final TaskMapper tasks;

    public CapacityRepositoryAdapter(CapacityMapper mapper, TaskMapper tasks) {
        this.mapper = mapper;
        this.tasks = tasks;
    }

    @Override
    public Optional<CapacitySnapshot> lock(String name) {
        return Optional.ofNullable(mapper.lock(name));
    }

    @Override
    public void increment(String name) {
        mapper.increment(name);
    }

    @Override
    public void decrement(String name) {
        mapper.decrement(name);
    }

    @Override
    public Optional<CapacitySnapshot> read(String name) {
        return Optional.ofNullable(mapper.read(name));
    }

    @Override
    public CapacityDrift checkDrift(String name) {
        CapacitySnapshot snapshot = mapper.read(name);
        int counterValue = snapshot == null ? -1 : snapshot.activeCount();
        int actualValue = tasks.countUnfinished();
        return new CapacityDrift(name, counterValue, actualValue);
    }
}
