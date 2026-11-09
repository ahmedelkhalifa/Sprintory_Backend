package team.sprintory_backend.sprint.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record StartSprintRequest(LocalDate startDate, LocalDate endDate, @Size(max = 5000) String goal) {}
