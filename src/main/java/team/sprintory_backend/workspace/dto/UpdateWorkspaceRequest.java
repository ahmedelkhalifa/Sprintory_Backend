package team.sprintory_backend.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only the name can change; the slug stays as generated so existing links keep working. */
public record UpdateWorkspaceRequest(
        @NotBlank @Size(max = 100) String name
) {}