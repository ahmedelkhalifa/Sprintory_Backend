package team.sprintory_backend.exceptionHandler;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * The single error body for every API error. Shape is fixed by docs/api-conventions.md.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ErrorResponse {
    private Instant timestamp;
    private int status;
    private ErrorCode code;
    private String message;
    private String path;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> fieldErrors;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.List<String> cyclePath;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.List<team.sprintory_backend.dependency.DependencyDtos.TaskSummary> openBlockers;

    public ErrorResponse(Instant timestamp, int status, ErrorCode code, String message, String path, Map<String,String> fieldErrors) {
        this(timestamp,status,code,message,path,fieldErrors,null,null);
    }
}