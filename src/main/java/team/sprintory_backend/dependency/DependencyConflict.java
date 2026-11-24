package team.sprintory_backend.dependency;

import team.sprintory_backend.exceptionHandler.ErrorCode;
import java.util.List;
import team.sprintory_backend.dependency.DependencyDtos.TaskSummary;

public class DependencyConflict extends RuntimeException {
    private final ErrorCode code;
    private final List<String> cyclePath;
    private final List<TaskSummary> openBlockers;
    public DependencyConflict(ErrorCode code, String message, List<String> cyclePath, List<TaskSummary> openBlockers) {
        super(message);
        this.code = code;
        this.cyclePath = cyclePath;
        this.openBlockers = openBlockers;
    }
    public ErrorCode code() { return code; }
    public List<String> cyclePath() { return cyclePath; }
    public List<TaskSummary> openBlockers() { return openBlockers; }
}
