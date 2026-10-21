package team.sprintory_backend.notification.email;

import java.nio.charset.StandardCharsets;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** SMTP transport. The application starts without an SMTP server; calling send fails clearly. */
@Component
public class SmtpEmailSender implements EmailSender {
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                           @Value("${app.mail.from:}") String fromAddress) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String recipientEmail, RenderedEmail email) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null || fromAddress == null || fromAddress.isBlank()) {
            throw new EmailDeliveryException("Email delivery is not configured");
        }
        if (recipientEmail == null || recipientEmail.isBlank()) {
            throw new IllegalArgumentException("recipientEmail must not be blank");
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(recipientEmail);
            helper.setSubject(email.subject());
            helper.setText(email.plainText(), email.html());
            mailSender.send(mimeMessage);
        } catch (MessagingException | MailException ex) {
            throw new EmailDeliveryException("Unable to deliver email", ex);
        }
    }
}
