package team.sprintory_backend.notification.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailSenderTest {
    @Test
    @SuppressWarnings("unchecked")
    void missingSmtpConfigurationFailsExplicitly() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        SmtpEmailSender sender = new SmtpEmailSender(provider, "");
        assertThrows(EmailDeliveryException.class,
                () -> sender.send("alex@example.com", new RenderedEmail("Subject", "Text", "<p>Text</p>")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsMimeMessageWithoutOpeningSmtpConnection() throws Exception {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new JavaMailSenderImpl().createMimeMessage();
        when(provider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(message);
        SmtpEmailSender sender = new SmtpEmailSender(provider, "sprintory@gmail.com");
        sender.send("alex@example.com", new RenderedEmail("Sprintory test", "Plain message", "<p>HTML message</p>"));
        verify(mailSender).send(message);
        assertEquals("Sprintory test", message.getSubject());
        assertEquals("alex@example.com", message.getAllRecipients()[0].toString());
        assertEquals("sprintory@gmail.com", message.getFrom()[0].toString());
    }
}
