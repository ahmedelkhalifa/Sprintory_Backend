package team.sprintory_backend.project.dto;

import java.time.LocalDate;

/**
 * The header of a Scrum board (PMP-40): the active sprint at a glance. Points are story points of
 * its top-level items; done means a DONE-category column (no partial credit).
 *
 * @param daysLeft calendar days from today (UTC) to {@code endDate}; 0 on the last day and after it
 * @param overdue  the end date has passed and the sprint is still active
 */
public record ActiveSprintSummary(Long id, String name, String goal, LocalDate startDate, LocalDate endDate,
                                  int daysLeft, boolean overdue, int totalPoints, int donePoints) {}