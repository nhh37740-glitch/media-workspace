package com.mediaworkspace.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Enables the scheduled maintenance passes and bounds the pool that runs them.
 *
 * <p>A single thread. The passes are infrequent and short, they share a two-core host with the
 * worker and the database, and running two of them at once would only make them contend. Naming the
 * pool also makes it visible in a thread dump, which a default pool is not.
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("mw-api-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
