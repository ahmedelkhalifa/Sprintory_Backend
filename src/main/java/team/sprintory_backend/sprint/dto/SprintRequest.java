package team.sprintory_backend.sprint.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** Null means no change on PATCH; clearCapacity explicitly resets the optional choice. */
public record SprintRequest(
        @Size(max = 100) String name,
        @Size(max = 5000) String goal,
        @Min(0) Integer capacity,
        LocalDate startDate,
        LocalDate endDate,
        Boolean clearCapacity
) {
    public SprintRequest(String name, String goal, Integer capacity, LocalDate startDate, LocalDate endDate) {
        this(name, goal, capacity, startDate, endDate, false);
    }
}
