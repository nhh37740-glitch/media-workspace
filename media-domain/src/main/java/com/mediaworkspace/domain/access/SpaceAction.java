package com.mediaworkspace.domain.access;

import com.mediaworkspace.contracts.model.Role;

/** Actions a request may attempt inside a workspace, with the minimum role they require. */
public enum SpaceAction {
    /** List media, read media detail, stream content or poster, read tasks and attempts. */
    VIEW_MEDIA(Role.VIEWER),
    /** Create an upload session, upload chunks, complete or abort an own upload. */
    UPLOAD(Role.EDITOR),
    /** Cancel or retry a task. */
    CONTROL_TASK(Role.EDITOR),
    /** Create a share link for a ready media. */
    SHARE(Role.EDITOR),
    /** Rename a media. */
    EDIT_MEDIA(Role.EDITOR),
    /** Delete a media, which cancels its unfinished task. */
    DELETE_MEDIA(Role.OWNER),
    /** Add, change or remove members. */
    MANAGE_MEMBERS(Role.OWNER);

    private final Role required;

    SpaceAction(Role required) {
        this.required = required;
    }

    public Role required() {
        return required;
    }
}
