package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import team.sprintory_backend.project.models.BoardColumnCategory;

/** Omitted values are unchanged; clearWipLimit removes the limit. */
public record UpdateBoardColumnRequest(@Size(max = 100) String name,
                                       BoardColumnCategory category,
                                       @Positive Integer wipLimit,
                                       Boolean clearWipLimit) { }
