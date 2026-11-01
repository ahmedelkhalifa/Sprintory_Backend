package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** Complete active column ID list, left to right. */
public record ReorderBoardColumnsRequest(@NotEmpty List<@NotNull @Positive Long> columnIds) { }
