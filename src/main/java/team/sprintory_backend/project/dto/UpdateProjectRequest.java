package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Project settings (story 8). A null field is left unchanged; a blank description clears it.
 * The key and the template are not here: neither can change after creation.
 */
public record UpdateProjectRequest(
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String name,
        String description
) {}