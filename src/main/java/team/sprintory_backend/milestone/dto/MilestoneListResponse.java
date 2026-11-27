package team.sprintory_backend.milestone.dto;

import java.util.List;

/**
 * A project's milestones (docs/milestones-docs.md §3.1). The flags are here too so an empty list
 * still tells the frontend whether to offer "Add milestone".
 */
public record MilestoneListResponse(List<MilestoneResponse> milestones, boolean canManage, boolean canLink,
                                    int limit) {
}
