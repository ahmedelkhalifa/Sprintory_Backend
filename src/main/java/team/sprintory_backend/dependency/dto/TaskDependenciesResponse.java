package team.sprintory_backend.dependency.dto;



import java.util.List;

public record TaskDependenciesResponse(boolean blocked, List<DependencyLinkResponse> blockedBy,
                                       List<DependencyLinkResponse> blocks,
                                       List<DependencyTaskSummary> openBlockers, boolean canManage) {
}
