package team.sprintory_backend.project.charter.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Complete ordered list of goal ids. A partial list is rejected. */
public record CharterGoalOrderRequest(@NotNull List<@NotNull Long> goalIds) { }
