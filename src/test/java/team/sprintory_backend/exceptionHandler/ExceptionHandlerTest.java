package team.sprintory_backend.exceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import team.sprintory_backend.project.exceptions.InvalidProjectRolesException;
import team.sprintory_backend.project.exceptions.LastProjectManagerException;
import team.sprintory_backend.workspace.exceptions.LastWorkspaceAdminException;

import static org.junit.jupiter.api.Assertions.*;

/** The error mappings added for workspaces, teams and projects; the body shape is api-conventions §4. */
class ExceptionHandlerTest {

    private ExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new ExceptionHandler();
        request = new MockHttpServletRequest("GET", "/api/v1/teams/42");
    }

    @Test
    void notFoundAnswers404WithTheExceptionsMessage() {
        assertError(handler.handleNotFound(new ResourceNotFoundException("Team not found."), request),
                404, ErrorCode.RESOURCE_NOT_FOUND, "Team not found.");
    }

    @Test
    void badRequestAnswers400WithTheExceptionsMessage() {
        assertError(handler.handleBadRequest(
                        new BadRequestException("Some users are not members of this workspace."), request),
                400, ErrorCode.BAD_REQUEST, "Some users are not members of this workspace.");
    }

    @Test
    void archivedWorkspaceAnswers409() {
        assertError(handler.handleWorkspaceArchived(request),
                409, ErrorCode.WORKSPACE_ARCHIVED, "This workspace is archived. Restore it to make changes.");
    }

    @Test
    void deletingAnActiveWorkspaceAnswers409() {
        assertError(handler.handleWorkspaceNotArchived(request),
                409, ErrorCode.WORKSPACE_NOT_ARCHIVED, "Archive the workspace before deleting it.");
    }

    @Test
    void lastWorkspaceAdminAnswers409WithTheExceptionsMessage() {
        assertError(handler.handleLastWorkspaceAdmin(
                        new LastWorkspaceAdminException("A workspace must have at least one admin."), request),
                409, ErrorCode.LAST_WORKSPACE_ADMIN, "A workspace must have at least one admin.");
    }

    @Test
    void lastProjectManagerAnswers409WithTheExceptionsMessage() {
        assertError(handler.handleLastProjectManager(
                        new LastProjectManagerException("A project must have at least one Project Manager."), request),
                409, ErrorCode.LAST_PROJECT_MANAGER, "A project must have at least one Project Manager.");
    }

    @Test
    void invalidProjectRolesAnswers400WithTheExceptionsMessage() {
        assertError(handler.handleInvalidProjectRoles(
                        new InvalidProjectRolesException("Viewer cannot be combined with other roles."), request),
                400, ErrorCode.INVALID_PROJECT_ROLES, "Viewer cannot be combined with other roles.");
    }

    @Test
    void aNonNumericPathVariableAnswers400InsteadOf500() throws NoSuchMethodException {
        MethodParameter teamId = new MethodParameter(
                ExceptionHandlerTest.class.getDeclaredMethod("endpoint", Long.class), 0);
        var mismatch = new MethodArgumentTypeMismatchException("abc", Long.class, "teamId", teamId, null);

        assertError(handler.handleTypeMismatch(mismatch, request),
                400, ErrorCode.BAD_REQUEST, "Invalid value for 'teamId'.");
    }

    /** Stands in for a controller method with a numeric path variable. */
    @SuppressWarnings("unused")
    private static void endpoint(Long teamId) {
    }

    private static void assertError(ResponseEntity<ErrorResponse> response, int status, ErrorCode code,
                                    String message) {
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(status, response.getStatusCode().value());
        assertEquals(status, body.getStatus());
        assertEquals(code, body.getCode());
        assertEquals(message, body.getMessage());
        assertEquals("/api/v1/teams/42", body.getPath());
        assertNotNull(body.getTimestamp());
        assertNull(body.getFieldErrors());
    }
}