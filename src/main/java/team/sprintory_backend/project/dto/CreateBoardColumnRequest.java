package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import team.sprintory_backend.project.models.BoardColumnCategory;

public record CreateBoardColumnRequest(@NotBlank @Size(max = 100) String name,
                                       @NotNull BoardColumnCategory category,
                                       @Positive Long afterColumnId) { }
