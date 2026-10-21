package team.sprintory_backend.notification.email;

import org.springframework.stereotype.Service;

/** Application-facing email service. Authentication services can call these methods later. */
@Service
public class DefaultEmailService implements EmailService {
    private final EmailTemplateFactory templateFactory;
    private final EmailTemplateRenderer renderer;
    private final EmailSender sender;

    public DefaultEmailService(EmailTemplateFactory templateFactory, EmailTemplateRenderer renderer,
                               EmailSender sender) {
        this.templateFactory = templateFactory;
        this.renderer = renderer;
        this.sender = sender;
    }

    @Override
    public void sendEmailVerification(String recipientEmail, String recipientName, String verificationUrl) {
        sender.send(recipientEmail, renderer.render(templateFactory.verification(recipientName, verificationUrl)));
    }

    @Override
    public void sendPasswordReset(String recipientEmail, String recipientName, String resetUrl) {
        sender.send(recipientEmail, renderer.render(templateFactory.passwordReset(recipientName, resetUrl)));
    }

    @Override
    public void sendNotification(String recipientEmail, String recipientName, String subject, String message) {
        sender.send(recipientEmail, renderer.render(templateFactory.notification(recipientName, subject, message)));
    }
}
