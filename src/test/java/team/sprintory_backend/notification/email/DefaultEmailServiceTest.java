package team.sprintory_backend.notification.email;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class DefaultEmailServiceTest {
    private DefaultEmailService service(EmailSender sender) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return new DefaultEmailService(new EmailTemplateFactory(), new EmailTemplateRenderer(engine), sender);
    }

    @Test
    void sendsPasswordResetEmailUsingTransportBoundary() {
        EmailSender sender = mock(EmailSender.class);
        service(sender).sendPasswordReset("alex@example.com", "Alex", "https://example.com/reset?token=xyz");
        verify(sender).send(eq("alex@example.com"), argThat(email ->
                email.subject().contains("Reset")
                        && email.html().contains("Reset password")
                        && email.plainText().contains("https://example.com/reset?token=xyz")));
    }

    @Test
    void sendsNotificationThroughTheSameTransport() {
        EmailSender sender = mock(EmailSender.class);
        service(sender).sendNotification("alex@example.com", "Alex", "Project update", "A task changed.");
        verify(sender).send(eq("alex@example.com"), argThat(email ->
                email.subject().equals("Project update") && email.html().contains("A task changed.")));
    }

    @Test
    void rejectsMalformedVerificationUrlBeforeSending() {
        EmailSender sender = mock(EmailSender.class);
        assertThrows(IllegalArgumentException.class,
                () -> service(sender).sendEmailVerification("alex@example.com", "Alex", "ftp://example.com/x"));
        verifyNoInteractions(sender);
    }
}
