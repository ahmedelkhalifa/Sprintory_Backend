package team.sprintory_backend.sprint.dto;

import team.sprintory_backend.sprint.models.SprintCommitment;

public record SprintCommitmentResponse(Long taskId, String taskKey, Integer committedEstimate, int position) {
    public static SprintCommitmentResponse from(SprintCommitment value) {
        return new SprintCommitmentResponse(value.getTaskId(), value.getTaskKey(),
                value.getCommittedEstimate(), value.getPosition());
    }
}
