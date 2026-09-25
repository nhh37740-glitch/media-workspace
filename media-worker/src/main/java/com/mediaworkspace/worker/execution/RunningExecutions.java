package com.mediaworkspace.worker.execution;

import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.application.port.transcode.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The executions this worker is currently running, with a cancellation flag each.
 *
 * <p>This is what makes cancellation reach a running child process. The user's cancel commits in the
 * database immediately, but the worker is in the middle of an encode and cannot notice a row change
 * by itself. A renewal pass renews every lease here; when a renewal fails the row no longer belongs
 * to this execution, so the flag is set and the encoder reads it and terminates its own process tree.
 *
 * <p>Renewal is a separate pass rather than something the executing thread does, because the
 * executing thread is blocked in the transcode. A lease that could only be renewed between
 * operations would expire in the middle of every long encode.
 */
public class RunningExecutions {

    private static final Logger log = LoggerFactory.getLogger(RunningExecutions.class);

    private final Map<String, Running> running = new ConcurrentHashMap<>();

    /** One execution in progress. */
    public static final class Running {
        private final TaskLease lease;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CancellationToken token = cancelled::get;

        Running(TaskLease lease) {
            this.lease = lease;
        }

        public TaskLease lease() {
            return lease;
        }

        public CancellationToken token() {
            return token;
        }

        /** Signals the encoder to stop. The flag is polled, so the process ends within a second. */
        public boolean cancel(String reason) {
            if (cancelled.compareAndSet(false, true)) {
                log.info("cancelling execution of task {} ({}): {}",
                        lease.task().id(), lease.task().executionEpoch(), reason);
                return true;
            }
            return false;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /** Registers an execution and returns its handle. */
    public Running register(TaskLease lease) {
        Running execution = new Running(lease);
        running.put(lease.task().id(), execution);
        return execution;
    }

    /**
     * Removes an execution.
     *
     * <p>Deliberately does not set the cancellation flag: this is called when the execution is over,
     * and marking it cancelled would misreport a successful run as an interrupted one.
     */
    public void unregister(String taskId) {
        running.remove(taskId);
    }

    public Running find(String taskId) {
        return running.get(taskId);
    }

    /** A snapshot for the renewal pass. */
    public java.util.Collection<Running> snapshot() {
        return java.util.List.copyOf(running.values());
    }

    public int size() {
        return running.size();
    }

    /** Cancels everything, used on shutdown so no encoder outlives the process. */
    public void cancelAll(String reason) {
        running.values().forEach(execution -> execution.cancel(reason));
    }
}
