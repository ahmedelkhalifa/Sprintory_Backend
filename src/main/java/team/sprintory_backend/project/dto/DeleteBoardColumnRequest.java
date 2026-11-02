package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.Positive;

/** Required only when the deleted column contains tasks. */
public record DeleteBoardColumnRequest(@Positive Long targetColumnId) { }
