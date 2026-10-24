package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.NotNull;
import team.sprintory_backend.project.models.ProjectRole;

import java.util.Set;

/**
 * Replaces all of a project member's roles. An empty set reaches the service so that its
 * INVALID_PROJECT_ROLES error describes the at-least-one-role rule consistently.
 */
public record SetProjectRolesRequest(@NotNull Set<@NotNull ProjectRole> roles) {}
