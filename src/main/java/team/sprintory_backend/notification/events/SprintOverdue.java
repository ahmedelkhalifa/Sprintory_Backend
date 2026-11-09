package team.sprintory_backend.notification.events;

import team.sprintory_backend.project.models.Project;

/**
 * The active sprint is past its end date (PMP-41). Published once a day by
 * {@code SprintReminderJob}; only the project's Scrum Masters are told. There is no actor.
 */
public record SprintOverdue(Project project, String sprintName, long daysOverdue) {}