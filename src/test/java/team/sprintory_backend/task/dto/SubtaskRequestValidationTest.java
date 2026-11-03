package team.sprintory_backend.task.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubtaskRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void creationRequiresTitleAndPositiveEstimateMinutes() {
        assertTrue(validator.validate(new CreateSubtaskRequest("Write tests", null, null, 30)).isEmpty());
        assertFalse(validator.validate(new CreateSubtaskRequest("  ", null, null, null)).isEmpty());
        assertFalse(validator.validate(new CreateSubtaskRequest("x".repeat(256), null, null, null)).isEmpty());
        assertFalse(validator.validate(new CreateSubtaskRequest("Valid", null, null, 0)).isEmpty());
        assertFalse(validator.validate(new CreateSubtaskRequest("Valid", null, null, -2)).isEmpty());
        assertFalse(validator.validate(new CreateSubtaskRequest("Valid", -1L, null, null)).isEmpty());
    }

    @Test
    void updateSupportsClearingAndRejectsConflictingFlags() {
        assertTrue(validator.validate(new UpdateSubtaskRequest(null, null, null, null,
                true, true)).isEmpty());
        assertFalse(validator.validate(new UpdateSubtaskRequest(null, null, null, 30,
                false, true)).isEmpty());
        assertFalse(validator.validate(new UpdateSubtaskRequest(" ", null, null, null,
                false, false)).isEmpty());
    }
}
