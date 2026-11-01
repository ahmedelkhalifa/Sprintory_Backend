package team.sprintory_backend.project.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.project.models.BoardColumnCategory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardColumnValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsMissingNamesInvalidIdsAndInvalidWip() {
        assertFalse(validator.validate(new CreateBoardColumnRequest(" ", BoardColumnCategory.TODO, null)).isEmpty());
        assertFalse(validator.validate(new CreateBoardColumnRequest("Test", null, null)).isEmpty());
        assertFalse(validator.validate(new CreateBoardColumnRequest("Test", BoardColumnCategory.TODO, -1L)).isEmpty());
        assertTrue(validator.validate(new CreateBoardColumnRequest("Test", BoardColumnCategory.TODO, 1L)).isEmpty());
        assertFalse(validator.validate(new UpdateBoardColumnRequest(null, null, 0, null)).isEmpty());
        assertFalse(validator.validate(new UpdateBoardColumnRequest(null, null, -1, null)).isEmpty());
        assertTrue(validator.validate(new UpdateBoardColumnRequest(null, null, 1, null)).isEmpty());
        assertFalse(validator.validate(new ReorderBoardColumnsRequest(List.of())).isEmpty());
        assertFalse(validator.validate(new DeleteBoardColumnRequest(0L)).isEmpty());
    }
}
