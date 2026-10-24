package team.sprintory_backend.project.models;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The actions inside a project that roles grant (R03). Code checks a permission, never a role
 * name; which role holds which permission is defined once, in {@link ProjectRole}.
 * Several of these belong to features of later stories and are defined here ahead of them.
 */
public enum ProjectPermission {
    /** See the project itself and its member list. */
    VIEW_PROJECT,
    /** See the work inside it: tasks, comments, attachments, sprints, reports. */
    VIEW_WORK,
    /** Edit the name, description and other settings. */
    EDIT_SETTINGS,
    /** Complete and archive the project (R30). */
    ARCHIVE_PROJECT,
    /** Add people or a team, remove people. */
    MANAGE_MEMBERS,
    /** Change the members' project roles. */
    MANAGE_ROLES,
    /** Board columns and WIP limits (R16). */
    CONFIGURE_BOARD,
    /** Order and prioritize the product backlog (R13). */
    MANAGE_BACKLOG,
    /** Plan, start and close sprints (R14, R15). */
    MANAGE_SPRINTS,
    /** Create, edit and delete tasks (story 9). */
    EDIT_TASKS,
    /** Comment on tasks (R10). */
    COMMENT,
    /** Log time on tasks (R19). */
    LOG_TIME;

    /**
     * What a workspace admin may do in every project of their workspace, member or not: manage
     * it, but not see its work unless they are a member (story 7).
     */
    public static final Set<ProjectPermission> WORKSPACE_ADMIN = Collections.unmodifiableSet(EnumSet.of(
            VIEW_PROJECT, EDIT_SETTINGS, ARCHIVE_PROJECT, MANAGE_MEMBERS, MANAGE_ROLES));
}