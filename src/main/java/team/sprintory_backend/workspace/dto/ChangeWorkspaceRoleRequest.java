package team.sprintory_backend.workspace.dto;

import jakarta.validation.constraints.NotNull;
import team.sprintory_backend.user.models.Role;

/** Changes exactly one member's role in the addressed workspace. */
public record ChangeWorkspaceRoleRequest(@NotNull Role role) {}
