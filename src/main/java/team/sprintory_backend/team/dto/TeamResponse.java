package team.sprintory_backend.team.dto;

import team.sprintory_backend.team.models.Team;

public record TeamResponse(Long id, Long workspaceId, String name) {
    public static TeamResponse from(Team team) {
        return new TeamResponse(team.getId(), team.getWorkspace().getId(), team.getName());
    }
}