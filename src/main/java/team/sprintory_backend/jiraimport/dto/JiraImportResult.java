package team.sprintory_backend.jiraimport.dto;

import java.util.List;

public record JiraImportResult(Long projectId, int createdTopLevel, int createdSubtasks,
                               int createdSprints, int skippedExisting, int skippedEpics,
                               int skippedOrphanSubtasks, int commentsImported,
                               List<String> warnings) {}
