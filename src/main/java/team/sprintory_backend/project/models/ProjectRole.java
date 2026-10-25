package team.sprintory_backend.project.models;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static team.sprintory_backend.project.models.ProjectPermission.*;

/**
 * A person's role in one project (R03). This enum is the permission matrix: each role lists
 * what it allows, and a person holding several roles gets all of them combined.
 *
 * <p>Project Manager, Product Owner and Scrum Master include every Member permission, so they
 * can work on tasks too. {@code MEMBER} is shown as "Developer" in Scrum projects. Which roles
 * a project offers depends on its {@link ProjectTemplate}.
 */
public enum ProjectRole {
    PROJECT_MANAGER(VIEW_PROJECT, VIEW_WORK, EDIT_SETTINGS, ARCHIVE_PROJECT, MANAGE_MEMBERS, MANAGE_ROLES,
            CONFIGURE_BOARD, EDIT_TASKS, COMMENT, LOG_TIME),
    PRODUCT_OWNER(VIEW_PROJECT, VIEW_WORK, MANAGE_BACKLOG, EDIT_TASKS, COMMENT, LOG_TIME),
    SCRUM_MASTER(VIEW_PROJECT, VIEW_WORK, MANAGE_SPRINTS, EDIT_TASKS, COMMENT, LOG_TIME),
    MEMBER(VIEW_PROJECT, VIEW_WORK, EDIT_TASKS, COMMENT, LOG_TIME),
    /** Read-only. Cannot be combined with other roles. */
    VIEWER(VIEW_PROJECT, VIEW_WORK);

    private final Set<ProjectPermission> permissions;

    ProjectRole(ProjectPermission... permissions) {
        this.permissions = Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(permissions)));
    }

    public Set<ProjectPermission> permissions() {
        return permissions;
    }

    /** Everything the given roles allow together; empty for no roles. */
    public static Set<ProjectPermission> permissionsOf(Collection<ProjectRole> roles) {
        Set<ProjectPermission> combined = EnumSet.noneOf(ProjectPermission.class);
        roles.forEach(role -> combined.addAll(role.permissions));
        return combined;
    }
}