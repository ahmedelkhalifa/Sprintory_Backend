package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import team.sprintory_backend.project.models.ProjectTemplate;

/** The key is stored upper-case; uniqueness within the workspace is checked by the service. */
public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank
        @Pattern(regexp = "^\\s*[A-Za-z][A-Za-z0-9]{1,9}\\s*$",
                message = "must be 2 to 10 letters or digits, starting with a letter")
        String key,
        @NotNull ProjectTemplate template,
        String description
) {}