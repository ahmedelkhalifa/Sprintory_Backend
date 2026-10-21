# Sprintory — Email service

## Scope

Reusable, SMTP-provider-independent **transactional email** infrastructure. Implemented on the `features/email_service` branch, independently of `features/auth_config`.

- HTML emails are rendered with Thymeleaf from a **single table-based, email-client-friendly layout** at `src/main/resources/templates/email/layout.html`.
- Matching plain-text alternatives are generated from the same typed model.
- `EmailService` is the application-facing API. Other backend features **must not call `JavaMailSender` directly**.
- `EmailTemplateFactory` is the template constructor: it builds the subject, copy, optional action button/URL, and footer note for email verification, password reset, and generic notifications.
- `EmailSender` and `SmtpEmailSender` separate delivery from rendering. SMTP is synchronous in this version; use an outbox/queue when reliable asynchronous delivery becomes necessary.

## Usage from authentication services

```java
private final EmailService emailService;

// After a verification token has been persisted by the authentication service:
emailService.sendEmailVerification(user.getEmail(), user.getFirstName(), verificationUrl);

// After a reset token has been persisted:
emailService.sendPasswordReset(user.getEmail(), user.getFirstName(), resetUrl);
```

**Token creation, persistence, expiry, rate limiting, enumeration-resistant HTTP responses, and retries are NOT implemented here.** The authentication/service layer owns them. The reset template mentions 24 hours to match the R01 requirement; the backend must enforce that expiry.

Generic notifications:

```java
emailService.sendNotification("alex@example.com", "Alex", "Project update", "A task was assigned to you.");
```

HTML dynamic values are escaped by Thymeleaf. Action URLs must be absolute `http` or `https` URLs (including localhost for development). Never pass user-authored HTML as executable markup.

## No SMTP account yet

**No SMTP settings are required to compile, render templates, or run unit tests.** Spring Boot will not auto-create a `JavaMailSender` without `spring.mail.host`; attempting to deliver a real email throws `EmailDeliveryException("Email delivery is not configured")`. Nothing is silently discarded.

For future Gmail SMTP setup, add settings in a **local environment or deployment config**, not Git:

```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${SMTP_USERNAME}
spring.mail.password=${SMTP_APP_PASSWORD}
spring.mail.properties[mail.smtp.auth]=true
spring.mail.properties[mail.smtp.starttls.enable]=true
spring.mail.properties[mail.smtp.starttls.required]=true
spring.mail.properties[mail.smtp.connectiontimeout]=5000
spring.mail.properties[mail.smtp.timeout]=5000
spring.mail.properties[mail.smtp.writetimeout]=5000
app.mail.from=${SMTP_USERNAME}
```

A personal Gmail account can use **2-Step Verification + an eligible App Password** for SMTP; no Google Cloud project is required for this path. Google's Gmail API with OAuth2 would require a separate setup. Use a dedicated account and do not commit secrets.

For local SMTP preview, Mailpit can later be configured as the SMTP host with port 1025 and its web UI at port 8025. This is optional and not required for the current tests.

## Tests and boundaries

Unit tests cover template model construction, allowed URL schemes, HTML escaping, plain-text alternatives, service delegation, explicit failure without SMTP, and MIME construction with a mocked mail transport. They make no network calls and require no database or Gmail account.

The email service intentionally does not provide REST endpoints. Authentication and notification services will call its Java interface when those features are integrated. Exceptions from email delivery must be handled appropriately by the calling workflow: for signup/password reset, avoid losing a token or leaving inconsistent state when the SMTP server is unavailable.

The layout uses the frontend's Sprintory brand color values at the time of implementation; email HTML uses inline CSS for compatibility with email clients.
