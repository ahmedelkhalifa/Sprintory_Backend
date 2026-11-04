package team.sprintory_backend.notification.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.notification.dto.UpdateNotificationPreferencesRequest.Change;
import team.sprintory_backend.notification.models.NotificationCategory;

/** DTO validation for PUT /notification-preferences (duplicates and locked rows are the service's). */
class NotificationRequestValidationTest {

    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void preferencesMustBePresentAndNotEmpty() {
        assertFalse(validator.validate(new UpdateNotificationPreferencesRequest(null)).isEmpty());
        assertFalse(validator.validate(new UpdateNotificationPreferencesRequest(List.of())).isEmpty());
    }

    @Test
    void aNullEntryIsRefused() {
        List<Change> withNull = new ArrayList<>();
        withNull.add(null);
        assertFalse(validator.validate(new UpdateNotificationPreferencesRequest(withNull)).isEmpty());
    }

    @Test
    void aChangeNeedsACategory() {
        assertFalse(validator.validate(new UpdateNotificationPreferencesRequest(
                List.of(new Change(null, true, true)))).isEmpty());
    }

    @Test
    void bothChannelsMayBeOmitted() {
        assertTrue(validator.validate(new UpdateNotificationPreferencesRequest(
                List.of(new Change(NotificationCategory.COMMENT, null, null)))).isEmpty());
        assertTrue(validator.validate(new UpdateNotificationPreferencesRequest(
                List.of(new Change(NotificationCategory.COMMENT, true, false)))).isEmpty());
    }
}