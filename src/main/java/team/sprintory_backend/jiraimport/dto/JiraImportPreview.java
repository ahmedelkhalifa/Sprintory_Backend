package team.sprintory_backend.jiraimport.dto;

import java.util.List;

/** Non-mutating estimate of an import into one existing Scrum project. */
public record JiraImportPreview(Long projectId, String jiraProjectKey, int sourceIssues,
                                int epicsSkipped, int orphanSubtasksSkipped,
                                int alreadyImported, int newTopLevelItems, int newSubtasks,
                                int newSprints, List<JiraAssignee> assignees,
                                List<String> warnings, List<String> blockers, boolean ready) {
    public record JiraAssignee(String jiraAccountId, String displayName, int issueCount) {}
}
