package team.sprintory_backend.dependency.exceptions;

import team.sprintory_backend.dependency.dto.DependencyTaskSummary;
import team.sprintory_backend.exceptionHandler.ErrorCode;

import java.util.List;

public class DependencyConflict extends RuntimeException {
    private final ErrorCode code;
    private final List<String> cyclePath;
    private final List<DependencyTaskSummary> openBlockers;
    public DependencyConflict(ErrorCode code, String message, List<String> cyclePath, List<DependencyTaskSummary> openBlockers) {
        super(message);
        this.code = code;
        this.cyclePath = cyclePath;
        this.openBlockers = openBlockers;
    }
    public ErrorCode code() { return code; }
    public List<String> cyclePath() { return cyclePath; }
    public List<DependencyTaskSummary> openBlockers() { return openBlockers; }
}
