package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Minimal for now; story 8 adds the project key and the Scrum/Kanban template. */
public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String name
) {}