package team.sprintory_backend.exceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import team.sprintory_backend.notification.email.EmailDeliveryException;
import team.sprintory_backend.security.exceptions.EmailAlreadyRegisteredException;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.security.exceptions.ProviderMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExceptionHandler.class);

    /**
     * Both cases return the same body on purpose, so the response never reveals
     * whether an email is registered.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler({
            BadCredentialsException.class, UsernameNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleBadCredentials(HttpServletRequest request) {
        return body(HttpStatus.UNAUTHORIZED.value(), ErrorCode.AUTHENTICATION_ERROR,
                "Invalid email or password.", request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabled(HttpServletRequest request) {
        return body(HttpStatus.FORBIDDEN.value(), ErrorCode.ACCOUNT_NOT_ACTIVATED,
                "This account is not activated. Use the activation link we emailed you.", request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(HttpServletRequest request) {
        return body(HttpStatus.FORBIDDEN.value(), ErrorCode.ACCESS_DENIED,
                "You do not have access to this resource.", request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(HttpServletRequest request) {
        return body(HttpStatus.UNAUTHORIZED.value(), ErrorCode.INVALID_TOKEN,
                "This token is no longer valid. Please sign in again.", request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ProviderMismatchException.class)
    public ResponseEntity<ErrorResponse> handleProviderMismatch(HttpServletRequest request) {
        return body(HttpStatus.CONFLICT.value(), ErrorCode.PROVIDER_MISMATCH,
                "This account uses Google sign-in. Continue with Google.", request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered(HttpServletRequest request) {
        return body(HttpStatus.CONFLICT.value(), ErrorCode.CONFLICT,
                "An account with this email already exists.", request);
    }

    /** SMTP details go to the log only; the client gets a generic retry message. */
    @org.springframework.web.bind.annotation.ExceptionHandler(EmailDeliveryException.class)
    public ResponseEntity<ErrorResponse> handleEmailDelivery(EmailDeliveryException e,
                                                             HttpServletRequest request) {
        log.error("Email delivery failed on {} {}", request.getMethod(), request.getRequestURI(), e);
        return body(HttpStatus.SERVICE_UNAVAILABLE.value(), ErrorCode.SERVICE_UNAVAILABLE,
                "We couldn't send the email. Please try again later.", request);
    }

    /**
     * Broken JSON or a value of the wrong type. This exception is not a Spring ErrorResponse,
     * so without this handler it would be reported as a 500.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST.value(), ErrorCode.BAD_REQUEST,
                "Malformed request body.", request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException e,
                                                        HttpServletRequest request) {
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI(), e);
        return body(HttpStatus.CONFLICT.value(), ErrorCode.CONFLICT,
                "That value is already in use.", request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .code(ErrorCode.VALIDATION_ERROR)
                .message("Request validation failed.")
                .path(request.getRequestURI())
                .fieldErrors(fieldErrors)
                .build());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        // Spring MVC's own exceptions (unknown path, wrong method, wrong content type) already
        // carry the correct status; without this branch they would all be reported as 500.
        if (e instanceof org.springframework.web.ErrorResponse spring) {
            int status = spring.getStatusCode().value();
            HttpStatus resolved = HttpStatus.resolve(status);
            ErrorCode code = (status == HttpStatus.NOT_FOUND.value())
                    ? ErrorCode.RESOURCE_NOT_FOUND
                    : (status == HttpStatus.NOT_IMPLEMENTED.value()
                        ? ErrorCode.NOT_IMPLEMENTED : ErrorCode.BAD_REQUEST);
            return body(status, code,
                    (resolved != null) ? resolved.getReasonPhrase() : "Request failed.", request);
        }

        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL_ERROR,
                "Something went wrong. Please try again.", request);
    }

    private ResponseEntity<ErrorResponse> body(int status, ErrorCode code, String message,
                                               HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status)
                .code(code)
                .message(message)
                .path(request.getRequestURI())
                .build());
    }
}