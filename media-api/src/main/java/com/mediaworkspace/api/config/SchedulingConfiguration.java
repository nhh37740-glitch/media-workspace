package com.mediaworkspace.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>The whole configuration is conditional on the scheduler switch, and that conditionality is not
 * cosmetic. A thread pool created by this class holds non-daemon threads, so a process that merely
 * loaded the bean would stay alive after its work was done - which is exactly the failure that made
 * the bootstrap command hang until its timeout. Switching the feature off therefore has to remove
 * the scheduling infrastructure, not only the jobs that use it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mediaworkspace.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("mw-api-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setRemoveOnCancelPolicy(true);
        // Marked as daemon as well, so even an orderly shutdown that is somehow skipped cannot keep
        // the process alive. The explicit shutdown in the scheduler's own lifecycle is the primary
        // mechanism; this is the belt to its braces.
        scheduler.setDaemon(true);
        return scheduler;
    }
}
