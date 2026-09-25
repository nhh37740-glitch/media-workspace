package com.mediaworkspace.domain.access;

import com.mediaworkspace.contracts.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/** ACL-02, ACL-04: the role matrix is enforced in one place and denies by default. */
class RolePolicyTest {

    private final RolePolicy policy = new RolePolicy();

    @Test
    @DisplayName("a null role is a non-member for every action")
    void nonMemberIsDeniedEverything() {
        for (SpaceAction action : SpaceAction.values()) {
            assertThat(policy.allows(null, action)).as("%s", action).isFalse();
        }
        assertThat(policy.isVisible(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SpaceAction.class)
    @DisplayName("OWNER may perform every workspace action")
    void ownerMayDoEverything(SpaceAction action) {
        assertThat(policy.allows(Role.OWNER, action)).isTrue();
    }

    @Test
    @DisplayName("VIEWER may read but never upload, control a task, share, edit or delete")
    void viewerIsReadOnly() {
        assertThat(policy.allows(Role.VIEWER, SpaceAction.VIEW_MEDIA)).isTrue();
        assertThat(policy.allows(Role.VIEWER, SpaceAction.UPLOAD)).isFalse();
        assertThat(policy.allows(Role.VIEWER, SpaceAction.CONTROL_TASK)).isFalse();
        assertThat(policy.allows(Role.VIEWER, SpaceAction.SHARE)).isFalse();
        assertThat(policy.allows(Role.VIEWER, SpaceAction.EDIT_MEDIA)).isFalse();
        assertThat(policy.allows(Role.VIEWER, SpaceAction.DELETE_MEDIA)).isFalse();
        assertThat(policy.allows(Role.VIEWER, SpaceAction.MANAGE_MEMBERS)).isFalse();
    }

    @Test
    @DisplayName("EDITOR may upload, control, share and rename, but not delete or manage members")
    void editorCannotDeleteOrManageMembers() {
        assertThat(policy.allows(Role.EDITOR, SpaceAction.UPLOAD)).isTrue();
        assertThat(policy.allows(Role.EDITOR, SpaceAction.CONTROL_TASK)).isTrue();
        assertThat(policy.allows(Role.EDITOR, SpaceAction.SHARE)).isTrue();
        assertThat(policy.allows(Role.EDITOR, SpaceAction.EDIT_MEDIA)).isTrue();
        assertThat(policy.allows(Role.EDITOR, SpaceAction.DELETE_MEDIA)).isFalse();
        assertThat(policy.allows(Role.EDITOR, SpaceAction.MANAGE_MEMBERS)).isFalse();
    }

    @Test
    @DisplayName("an upload session belongs to its uploader, plus any OWNER")
    void uploadOwnership() {
        assertThat(policy.mayManageUpload(Role.EDITOR, "u1", "u1")).isTrue();
        assertThat(policy.mayManageUpload(Role.EDITOR, "u2", "u1")).isFalse();
        assertThat(policy.mayManageUpload(Role.OWNER, "u9", "u1")).isTrue();
        assertThat(policy.mayManageUpload(Role.VIEWER, "u1", "u1")).isFalse();
        assertThat(policy.mayManageUpload(null, "u1", "u1")).isFalse();
    }

    @Test
    @DisplayName("a share is revocable by its creator, plus any OWNER")
    void shareRevocation() {
        assertThat(policy.mayRevokeShare(Role.EDITOR, "u1", "u1")).isTrue();
        assertThat(policy.mayRevokeShare(Role.EDITOR, "u2", "u1")).isFalse();
        assertThat(policy.mayRevokeShare(Role.OWNER, "u9", "u1")).isTrue();
        assertThat(policy.mayRevokeShare(Role.VIEWER, "u1", "u1")).isFalse();
    }
}
