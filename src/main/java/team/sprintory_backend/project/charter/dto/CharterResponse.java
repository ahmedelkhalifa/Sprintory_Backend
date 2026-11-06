package team.sprintory_backend.project.charter.dto;

import java.util.List;

/** Empty charter comes back as empty lists and null scope fields. */
public record CharterResponse(Long projectId, boolean canEdit, boolean readOnly,
        String inScope, String outOfScope,
        List<CharterGoalResponse> goals, List<CharterStakeholderResponse> stakeholders) { }
