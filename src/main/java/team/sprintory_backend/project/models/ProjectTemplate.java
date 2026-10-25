package team.sprintory_backend.project.models;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static team.sprintory_backend.project.models.ProjectRole.*;

/**
 * The methodology a project is created with (R06). Chosen once at creation and never changed.
 * It decides which project roles the project offers (story 6) and, with story 8, its board.
 */
public enum ProjectTemplate {
    SCRUM(EnumSet.allOf(ProjectRole.class)),
    KANBAN(EnumSet.of(PROJECT_MANAGER, MEMBER, VIEWER));

    private final Set<ProjectRole> roles;

    ProjectTemplate(Set<ProjectRole> roles) {
        this.roles = Collections.unmodifiableSet(roles);
    }

    /** The roles that can be given in a project of this template. */
    public Set<ProjectRole> roles() {
        return roles;
    }

    public boolean offers(ProjectRole role) {
        return roles.contains(role);
    }
}