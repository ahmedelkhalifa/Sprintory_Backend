package team.sprintory_backend.task.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private CreateTaskRequest create(String title, Integer estimate, Long assigneeId, List<String> labels) {
        return new CreateTaskRequest(title, TaskType.TASK, TaskPriority.MEDIUM,
                assigneeId, estimate, labels, null);
    }

    @Test
    void acceptsMinimumCreateRequest() {
        assertTrue(validator.validate(new CreateTaskRequest("Fix login", null, null,
                null, null, null, null)).isEmpty());
    }

    @Test
    void rejectsEmptyAndOversizedTitles() {
        assertFalse(validator.validate(create("   ", null, null, null)).isEmpty());
        assertFalse(validator.validate(create("x".repeat(256), null, null, null)).isEmpty());
    }

    @Test
    void rejectsNegativeEstimateAndInvalidAssigneeAndBlankLabel() {
        assertFalse(validator.validate(create("Valid", -1, null, null)).isEmpty());
        assertFalse(validator.validate(create("Valid", null, 0L, null)).isEmpty());
        assertFalse(validator.validate(create("Valid", null, null, List.of("backend", " "))).isEmpty());
        assertTrue(validator.validate(create("Valid", 0, 7L, List.of("backend"))).isEmpty());
    }

    @Test
    void partialUpdateValidatesNonBlankTitle() {
        var invalid = update(" ", null, null, null, false, false, false);
        assertFalse(validator.validate(invalid).isEmpty());
        var valid = update(null, null, null, List.of(), false, false, false);
        assertTrue(validator.validate(valid).isEmpty());
    }

    @Test
    void updateSupportsExplicitClearsButNotBothSetAndClear() {
        assertTrue(validator.validate(update(null, null, null, null, true, true, true)).isEmpty());
        assertFalse(validator.validate(update(null, 7L, null, null, true, false, false)).isEmpty());
        assertFalse(validator.validate(update(null, null, 0, null, false, true, false)).isEmpty());
        assertTrue(validator.validate(update(null, null, null, null, false, false, false)).isEmpty());
    }

    private UpdateTaskRequest update(String title, Long assigneeId, Integer estimate,
                                     List<String> labels, boolean clearAssignee,
                                     boolean clearEstimate, boolean clearDescription) {
        return new UpdateTaskRequest(title, null, null, null, assigneeId, estimate,
                labels, null, clearAssignee, clearEstimate, clearDescription);
    }
}
