package team.sprintory_backend.project.models;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static team.sprintory_backend.project.models.ProjectPermission.*;
import static team.sprintory_backend.project.models.ProjectRole.*;

/** The permission matrix (R03, story 6), checked cell by cell and against the acceptance criteria. */
class ProjectRoleTest {

    // --- the matrix ---

    @Test
    void everyRoleHasExactlyThePermissionsOfTheMatrix() {
        Map<ProjectRole, Set<ProjectPermission>> matrix = Map.of(
                PROJECT_MANAGER, EnumSet.of(VIEW_PROJECT, VIEW_WORK, EDIT_SETTINGS, ARCHIVE_PROJECT,
                        MANAGE_MEMBERS, MANAGE_ROLES, CONFIGURE_BOARD, EDIT_TASKS, COMMENT, LOG_TIME),
                PRODUCT_OWNER, EnumSet.of(VIEW_PROJECT, VIEW_WORK, MANAGE_BACKLOG, EDIT_TASKS, COMMENT, LOG_TIME),
                SCRUM_MASTER, EnumSet.of(VIEW_PROJECT, VIEW_WORK, MANAGE_SPRINTS, EDIT_TASKS, COMMENT, LOG_TIME),
                MEMBER, EnumSet.of(VIEW_PROJECT, VIEW_WORK, EDIT_TASKS, COMMENT, LOG_TIME),
                VIEWER, EnumSet.of(VIEW_PROJECT, VIEW_WORK));

        assertEquals(EnumSet.allOf(ProjectRole.class), matrix.keySet(), "every role is in the matrix");
        matrix.forEach((role, expected) -> assertEquals(expected, role.permissions(), role.name()));
    }

    @Test
    void workspaceAdminsManageProjectsButDoNotSeeTheirWork() {
        assertEquals(EnumSet.of(VIEW_PROJECT, EDIT_SETTINGS, ARCHIVE_PROJECT, MANAGE_MEMBERS, MANAGE_ROLES),
                ProjectPermission.WORKSPACE_ADMIN);
        assertFalse(ProjectPermission.WORKSPACE_ADMIN.contains(VIEW_WORK));
    }

    @Test
    void managerOwnerAndScrumMasterIncludeEveryMemberPermission() {
        for (ProjectRole role : List.of(PROJECT_MANAGER, PRODUCT_OWNER, SCRUM_MASTER)) {
            assertTrue(role.permissions().containsAll(MEMBER.permissions()), role.name());
        }
    }

    @Test
    void everyRoleCanSeeTheProjectAndItsWork() {
        for (ProjectRole role : ProjectRole.values()) {
            assertTrue(role.permissions().containsAll(Set.of(VIEW_PROJECT, VIEW_WORK)), role.name());
        }
    }

    @Test
    void onlyTheProductOwnerOrdersTheBacklogAndOnlyTheScrumMasterRunsSprints() {
        for (ProjectRole role : ProjectRole.values()) {
            assertEquals(role == PRODUCT_OWNER, role.permissions().contains(MANAGE_BACKLOG), role.name());
            assertEquals(role == SCRUM_MASTER, role.permissions().contains(MANAGE_SPRINTS), role.name());
        }
    }

    @Test
    void permissionSetsCannotBeChanged() {
        assertThrows(UnsupportedOperationException.class, () -> MEMBER.permissions().add(MANAGE_ROLES));
        assertThrows(UnsupportedOperationException.class,
                () -> ProjectPermission.WORKSPACE_ADMIN.add(VIEW_WORK));
    }

    // --- combining roles ---

    @Test
    void permissionsOfCombinesAllRoles() {
        Set<ProjectPermission> combined = ProjectRole.permissionsOf(List.of(PRODUCT_OWNER, SCRUM_MASTER));

        assertEquals(EnumSet.of(VIEW_PROJECT, VIEW_WORK, MANAGE_BACKLOG, MANAGE_SPRINTS,
                EDIT_TASKS, COMMENT, LOG_TIME), combined);
    }

    @Test
    void permissionsOfNoRolesIsEmpty() {
        assertTrue(ProjectRole.permissionsOf(List.of()).isEmpty());
    }

    @Test
    void permissionsOfReturnsACopyTheCallerMayChange() {
        Set<ProjectPermission> combined = ProjectRole.permissionsOf(List.of(VIEWER));
        combined.addAll(ProjectPermission.WORKSPACE_ADMIN);

        assertEquals(EnumSet.of(VIEW_PROJECT, VIEW_WORK), VIEWER.permissions(), "the role itself is untouched");
    }

    // --- story 6 acceptance criteria 10-13 ---

    @Test
    void productOwnerAndScrumMasterTogetherCanOrderTheBacklogAndStartASprint() {
        Set<ProjectPermission> both = ProjectRole.permissionsOf(List.of(PRODUCT_OWNER, SCRUM_MASTER));

        assertTrue(both.contains(MANAGE_BACKLOG));
        assertTrue(both.contains(MANAGE_SPRINTS));
    }

    @Test
    void productOwnerCanCreateAndEditTasks() {
        assertTrue(PRODUCT_OWNER.permissions().contains(EDIT_TASKS));
    }

    @Test
    void developerCannotStartOrCloseSprints() {
        assertFalse(MEMBER.permissions().contains(MANAGE_SPRINTS));
    }

    @Test
    void viewerCanOnlyLook() {
        assertFalse(VIEWER.permissions().contains(EDIT_TASKS));
        assertFalse(VIEWER.permissions().contains(COMMENT));
        assertFalse(VIEWER.permissions().contains(LOG_TIME));
    }

    // --- story 6 acceptance criterion 14 ---

    @Test
    void onlyTheProjectManagerChangesRoles() {
        for (ProjectRole role : ProjectRole.values()) {
            assertEquals(role == PROJECT_MANAGER, role.permissions().contains(MANAGE_ROLES), role.name());
        }
    }
}