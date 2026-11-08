package team.sprintory_backend.sprint;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.sprint.dto.*;
import static org.junit.jupiter.api.Assertions.*;

class SprintRequestValidationTest {
    @Test void invalidCapacityAndNameAreRejectedBeforeService() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new SprintRequest("x".repeat(101), null, null, null, null)).isEmpty());
            assertFalse(validator.validate(new SprintRequest(null, null, -1, null, null)).isEmpty());
            assertFalse(validator.validate(new AvailabilityRequest(-1)).isEmpty());
            assertFalse(validator.validate(new MoveSprintTaskRequest(null, null, null)).isEmpty());
            assertFalse(validator.validate(new SprintLengthRequest(1)).isEmpty());
        }
    }
}
