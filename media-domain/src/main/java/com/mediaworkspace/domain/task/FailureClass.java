package com.mediaworkspace.domain.task;

/** How the task state machine must react to a failure code. */
public enum FailureClass {
    /** Retrying cannot help: the input or the environment is permanently unusable. */
    PERMANENT,
    /** The same input may succeed later, subject to the attempt budget. */
    TRANSIENT
}
