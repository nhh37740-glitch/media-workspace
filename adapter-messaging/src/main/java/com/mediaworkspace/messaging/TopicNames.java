package com.mediaworkspace.messaging;

import com.mediaworkspace.contracts.event.EventTopics;

/**
 * Resolves logical topic names to the physical names of one environment.
 *
 * <p>A prefix lets a single broker host the demonstration topics and several isolated integration
 * runs without their consumer groups and offsets colliding. Callers always pass the logical name, so
 * the prefix appears in exactly one place and no module can forget it.
 */
public class TopicNames {

    private final String prefix;

    public TopicNames(String prefix) {
        this.prefix = prefix == null ? "" : prefix.trim();
    }

    public String requested() {
        return resolve(EventTopics.TASK_REQUESTED);
    }

    public String result() {
        return resolve(EventTopics.TASK_RESULT);
    }

    public String deadLetter() {
        return resolve(EventTopics.EVENTS_DLQ);
    }

    /** Applies the environment prefix to a logical topic name. */
    public String resolve(String logicalName) {
        return prefix.isEmpty() ? logicalName : prefix + logicalName;
    }

    /** The consumer group of this environment, prefixed the same way. */
    public String group(String baseGroup) {
        return prefix.isEmpty() ? baseGroup : prefix + baseGroup;
    }

    public String prefix() {
        return prefix;
    }
}
