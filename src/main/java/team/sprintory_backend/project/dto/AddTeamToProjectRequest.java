package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.NotNull;

/** A team of the same workspace whose current members are copied into the project. */
public record AddTeamToProjectRequest(@NotNull Long teamId) {}