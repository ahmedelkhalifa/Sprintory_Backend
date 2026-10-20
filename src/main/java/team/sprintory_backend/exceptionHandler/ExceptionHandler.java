package team.sprintory_backend.exceptionHandler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@ControllerAdvice
@RestController
public class ExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUsernameNotFoundException(UsernameNotFoundException e) {
        ErrorResponse response = ErrorResponse.builder()
                .message(e.getMessage())
                .status(HttpStatus.NOT_FOUND)
                .errorCode(ErrorCode.RESOURCE_NOT_FOUND)
                .path(e.getMessage())
                .timestamp(Instant.now())
                .build();
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }
}
