package team.sprintory_backend.project.charter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CharterGoalRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 1000) String successMeasure) { }
