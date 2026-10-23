package team.sprintory_backend.project.dto;

import team.sprintory_backend.project.models.Project;

public record ProjectResponse(Long id, Long workspaceId, String name) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getWorkspace().getId(), project.getName());
    }
}