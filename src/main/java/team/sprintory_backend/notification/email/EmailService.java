package team.sprintory_backend.notification.email;

/** Public entry point for transactional email; other features must not construct SMTP messages. */
public interface EmailService {
    void sendEmailVerification(String recipientEmail, String recipientName, String verificationUrl);

    void sendPasswordReset(String recipientEmail, String recipientName, String resetUrl);

    void sendNotification(String recipientEmail, String recipientName, String subject, String message);
}
