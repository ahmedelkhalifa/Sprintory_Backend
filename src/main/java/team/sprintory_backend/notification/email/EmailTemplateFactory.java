package team.sprintory_backend.notification.email;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.stereotype.Component;

/** Reusable constructor for the email text, buttons, subject and template variables. */
@Component
public class EmailTemplateFactory {
    public EmailTemplateModel verification(String recipientName, String verificationUrl) {
        return new EmailTemplateModel(
                EmailTemplate.EMAIL_VERIFICATION,
                "Verify your Sprintory email",
                greetingName(recipientName),
                "Verify your email",
                "Thanks for joining Sprintory. Please confirm your email address to finish setting up your account.",
                "Verify email",
                requiredHttpUrl(verificationUrl),
                "If you did not create a Sprintory account, you can ignore this email."
        );
    }

    public EmailTemplateModel passwordReset(String recipientName, String resetUrl) {
        return new EmailTemplateModel(
                EmailTemplate.PASSWORD_RESET,
                "Reset your Sprintory password",
                greetingName(recipientName),
                "Reset your password",
                "We received a request to reset your Sprintory password. Use the button below to choose a new password.",
                "Reset password",
                requiredHttpUrl(resetUrl),
                "This link expires in 24 hours. If you did not request a password reset, you can ignore this email."
        );
    }

    public EmailTemplateModel workspaceInvitation(String inviterName, String workspaceName, String invitationUrl) {
        String inviter = requiredText(inviterName, "inviter name");
        String workspace = requiredText(workspaceName, "workspace name");
        return new EmailTemplateModel(
                EmailTemplate.WORKSPACE_INVITATION,
                requiredSubject("You're invited to join " + workspace + " on Sprintory"),
                greetingName(null),
                "Join " + workspace,
                inviter + " invited you to join the " + workspace + " workspace on Sprintory.",
                "Join workspace",
                requiredHttpUrl(invitationUrl),
                "This invitation expires in 7 days. If you were not expecting it, you can ignore this email."
        );
    }

    public EmailTemplateModel notification(String recipientName, String subject, String message) {
        return notification(recipientName, subject, message, null, null, null);
    }

    /**
     * A notification email (R27, docs/notifications-docs.md §10). The button is optional (label and
     * URL together); with a preferences URL the footer tells the reader where to change what they
     * receive.
     */
    public EmailTemplateModel notification(String recipientName, String subject, String message,
                                           String actionLabel, String actionUrl, String preferencesUrl) {
        if ((actionLabel == null) != (actionUrl == null)) {
            throw new IllegalArgumentException("action label and URL go together");
        }
        String note = (preferencesUrl == null)
                ? "You are receiving this email because you use Sprintory."
                : "You are receiving this email because of your Sprintory notification preferences. "
                        + "Manage them at " + requiredHttpUrl(preferencesUrl);
        return new EmailTemplateModel(
                EmailTemplate.NOTIFICATION,
                requiredSubject(subject),
                greetingName(recipientName),
                "Sprintory notification",
                requiredText(message, "message"),
                actionLabel == null ? null : requiredText(actionLabel, "action label"),
                actionUrl == null ? null : requiredHttpUrl(actionUrl),
                note
        );
    }

    private static String greetingName(String name) {
        return name == null || name.isBlank() ? "there" : name.trim();
    }

    private static String requiredSubject(String value) {
        String subject = requiredText(value, "subject");
        if (subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalArgumentException("subject must not contain newlines");
        }
        return subject;
    }

    private static String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String requiredHttpUrl(String value) {
        String url = requiredText(value, "action URL");
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("action URL must be an absolute HTTP(S) URL");
            }
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("action URL must be a valid HTTP(S) URL", ex);
        }
        return url;
    }
}
