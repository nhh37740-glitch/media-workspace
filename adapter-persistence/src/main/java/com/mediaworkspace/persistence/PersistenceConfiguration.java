package com.mediaworkspace.persistence;

import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.application.port.repository.AuditRepository;
import com.mediaworkspace.application.port.repository.CapacityRepository;
import com.mediaworkspace.application.port.repository.IdempotencyRepository;
import com.mediaworkspace.application.port.repository.InboxRepository;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.OutboxRepository;
import com.mediaworkspace.application.port.repository.ShareRepository;
import com.mediaworkspace.application.port.repository.ShareSessionRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.repository.UserRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.persistence.mapper.AuditMapper;
import com.mediaworkspace.persistence.mapper.CapacityMapper;
import com.mediaworkspace.persistence.mapper.IdempotencyMapper;
import com.mediaworkspace.persistence.mapper.MediaMapper;
import com.mediaworkspace.persistence.mapper.MessagingMapper;
import com.mediaworkspace.persistence.mapper.ShareMapper;
import com.mediaworkspace.persistence.mapper.TaskMapper;
import com.mediaworkspace.persistence.mapper.UploadMapper;
import com.mediaworkspace.persistence.mapper.UserMapper;
import com.mediaworkspace.persistence.mapper.WorkspaceMapper;
import com.mediaworkspace.persistence.repository.AuditRepositoryAdapter;
import com.mediaworkspace.persistence.repository.CapacityRepositoryAdapter;
import com.mediaworkspace.persistence.repository.IdempotencyRepositoryAdapter;
import com.mediaworkspace.persistence.repository.InboxRepositoryAdapter;
import com.mediaworkspace.persistence.repository.MediaRepositoryAdapter;
import com.mediaworkspace.persistence.repository.OutboxRepositoryAdapter;
import com.mediaworkspace.persistence.repository.ShareRepositoryAdapter;
import com.mediaworkspace.persistence.repository.ShareSessionRepositoryAdapter;
import com.mediaworkspace.persistence.repository.TaskRepositoryAdapter;
import com.mediaworkspace.persistence.repository.UploadRepositoryAdapter;
import com.mediaworkspace.persistence.repository.UserRepositoryAdapter;
import com.mediaworkspace.persistence.repository.WorkspaceRepositoryAdapter;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MyBatis mappers and the repository adapters that implement the application ports.
 *
 * <p>Datasource, connection pool and Flyway come from Spring Boot properties in each application, so
 * every environment states its own connection settings instead of inheriting a default that might
 * point somewhere unintended.
 */
@Configuration
@MapperScan(basePackages = "com.mediaworkspace.persistence.mapper")
public class PersistenceConfiguration {

    @Bean
    public UserRepository userRepository(UserMapper mapper) {
        return new UserRepositoryAdapter(mapper);
    }

    @Bean
    public WorkspaceRepository workspaceRepository(WorkspaceMapper mapper) {
        return new WorkspaceRepositoryAdapter(mapper);
    }

    @Bean
    public UploadRepository uploadRepository(UploadMapper mapper) {
        return new UploadRepositoryAdapter(mapper);
    }

    @Bean
    public MediaRepository mediaRepository(MediaMapper mapper) {
        return new MediaRepositoryAdapter(mapper);
    }

    /**
     * The task repository writes result events in the same transaction as the state change they
     * describe, so it needs the serializer. It still never contacts Kafka: a result becomes visible
     * to the broker only when the outbox publisher picks the row up.
     */
    @Bean
    public TaskRepository taskRepository(TaskMapper tasks, CapacityMapper capacity,
                                         MessagingMapper messaging, EventSerializer serializer) {
        return new TaskRepositoryAdapter(tasks, capacity, messaging, serializer);
    }

    @Bean
    public CapacityRepository capacityRepository(CapacityMapper mapper, TaskMapper tasks) {
        return new CapacityRepositoryAdapter(mapper, tasks);
    }

    @Bean
    public OutboxRepository outboxRepository(MessagingMapper mapper) {
        return new OutboxRepositoryAdapter(mapper);
    }

    @Bean
    public InboxRepository inboxRepository(MessagingMapper mapper) {
        return new InboxRepositoryAdapter(mapper);
    }

    @Bean
    public ShareRepository shareRepository(ShareMapper mapper) {
        return new ShareRepositoryAdapter(mapper);
    }

    @Bean
    public ShareSessionRepository shareSessionRepository(ShareMapper mapper) {
        return new ShareSessionRepositoryAdapter(mapper);
    }

    @Bean
    public IdempotencyRepository idempotencyRepository(IdempotencyMapper mapper) {
        return new IdempotencyRepositoryAdapter(mapper);
    }

    @Bean
    public AuditRepository auditRepository(AuditMapper mapper) {
        return new AuditRepositoryAdapter(mapper);
    }
}
