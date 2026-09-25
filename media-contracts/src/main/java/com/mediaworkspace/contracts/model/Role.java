package com.mediaworkspace.contracts.model;

/**
 * Workspace membership role. Ordering matters: a request is allowed when the actor's role rank is
 * at least the rank the action requires. Non-members are not represented here; they see 404.
 */
public enum Role {
    VIEWER(1),
    EDITOR(2),
    OWNER(3);

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(Role required) {
        return this.rank >= required.rank;
    }
}
