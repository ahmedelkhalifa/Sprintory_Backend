package team.sprintory_backend.exceptionHandler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashMap;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ErrorResponse {
    private Instant timestamp;
    private HttpStatus status;
    private ErrorCode errorCode;
    private String message;
    private String path;
    private HashMap<String, String> fieldErrors;
}
