package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

/**
 * Project settings (story 8). A null field is left unchanged; a blank description clears it.
 * The key and the template are not here: neither can change after creation.
 */
public record UpdateProjectRequest(
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String name,
        String description,
        @Min(2) @Max(90) Integer sprintLengthDays
) {
    public UpdateProjectRequest(String name, String description) {
        this(name, description, null);
    }
}