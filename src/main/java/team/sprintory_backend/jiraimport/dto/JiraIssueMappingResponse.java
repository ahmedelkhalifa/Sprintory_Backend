package team.sprintory_backend.jiraimport.dto;

import team.sprintory_backend.jiraimport.models.JiraIssueLink;

/** Source-to-target references are kept separately, since Sprintory task keys are generated. */
public record JiraIssueMappingResponse(String jiraKey, Long sprintoryTaskId,
                                       String sprintoryKey, String jiraEpicKey) {
    public static JiraIssueMappingResponse from(JiraIssueLink link) {
        return new JiraIssueMappingResponse(link.getJiraKey(), link.getTask().getId(),
                link.getTask().getKey(), link.getJiraEpicKey());
    }
}
