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
}
