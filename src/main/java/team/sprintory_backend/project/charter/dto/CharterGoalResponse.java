package team.sprintory_backend.project.charter.dto;

import team.sprintory_backend.project.charter.models.CharterGoal;

public record CharterGoalResponse(Long id, String title, String successMeasure, int order) {
    public static CharterGoalResponse from(CharterGoal goal) {
        return new CharterGoalResponse(goal.getId(), goal.getTitle(), goal.getSuccessMeasure(), goal.getSortOrder());
    }
}
