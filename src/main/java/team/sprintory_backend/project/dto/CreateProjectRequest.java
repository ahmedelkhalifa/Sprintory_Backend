package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import team.sprintory_backend.project.models.ProjectTemplate;

/** Story 8 adds the project key. */
public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull ProjectTemplate template
) {}