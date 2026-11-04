package team.sprintory_backend.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EmailTemplateFactoryTest {
    private final EmailTemplateFactory factory = new EmailTemplateFactory();

    @Test
    void verificationConstructsCorrectAction() {
        EmailTemplateModel model = factory.verification("Alex", "https://sprintory.example/verify?token=abc");
        assertEquals(EmailTemplate.EMAIL_VERIFICATION, model.type());
        assertEquals("Alex", model.recipientName());
        assertEquals("Verify email", model.actionLabel());
        assertTrue(model.subject().contains("Verify"));
    }

    @Test
    void passwordResetIncludesExpiryNote() {
        EmailTemplateModel model = factory.passwordReset(null, "http://localhost:5173/reset-password?token=abc");
        assertEquals("there", model.recipientName());
        assertTrue(model.note().contains("24 hours"));
    }

    @Test
    void actionLinksMustBeHttpOrHttps() {
        assertThrows(IllegalArgumentException.class, () -> factory.verification("X", "javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> factory.passwordReset("X", "/relative-link"));
        assertThrows(IllegalArgumentException.class, () -> factory.passwordReset("X", "https://user:pass@example.com"));
    }

    @Test
    void notificationRejectsHeaderInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.notification("Alex", "Subject\r\nBcc: attacker@example.com", "Message"));
    }

    @Test
    void notificationDoesNotRequireAnAction() {
        EmailTemplateModel model = factory.notification("", "New task", "Please review this task.");
        assertNull(model.actionUrl());
        assertEquals("New task", model.subject());
    }

    @Test
    void aNotificationCanCarryAButtonAndThePreferencesLink() {
        EmailTemplateModel model = factory.notification("Ada", "[Sprintory] MOB-42 Fix login: you were assigned",
                "Damir assigned you MOB-42", "Open task", "http://localhost:5173/projects/15/tasks/MOB-42",
                "http://localhost:5173/settings/notifications");

        assertEquals("Open task", model.actionLabel());
        assertEquals("http://localhost:5173/projects/15/tasks/MOB-42", model.actionUrl());
        assertTrue(model.note().contains("http://localhost:5173/settings/notifications"), model.note());
    }

    @Test
    void aNotificationButtonNeedsBothLabelAndLink() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.notification("Ada", "S", "M", "Open task", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> factory.notification("Ada", "S", "M", null, "https://example.com", null));
    }

    @Test
    void notificationLinksMustBeHttpOrHttps() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.notification("Ada", "S", "M", "Open", "javascript:alert(1)", null));
        assertThrows(IllegalArgumentException.class,
                () -> factory.notification("Ada", "S", "M", null, null, "/settings/notifications"));
    }
}
