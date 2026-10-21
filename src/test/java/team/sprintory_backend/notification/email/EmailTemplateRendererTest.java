package team.sprintory_backend.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

class EmailTemplateRendererTest {
    private EmailTemplateRenderer renderer;
    private EmailTemplateFactory factory;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        renderer = new EmailTemplateRenderer(engine);
        factory = new EmailTemplateFactory();
    }

    @Test
    void rendersVerificationHtmlAndPlainText() {
        RenderedEmail email = renderer.render(factory.verification("Alex", "https://example.com/verify?token=abc"));
        assertTrue(email.html().contains("Hi Alex,"));
        assertTrue(email.html().contains("https://example.com/verify?token=abc"));
        assertTrue(email.html().contains("Sprintory"));
        assertTrue(email.plainText().contains("Verify email: https://example.com/verify?token=abc"));
    }

    @Test
    void escapesUserSuppliedContentInHtml() {
        RenderedEmail email = renderer.render(factory.notification("<script>alert(1)</script>", "New task", "<img src=x onerror=alert(1)>"));
        assertFalse(email.html().contains("<script>alert(1)</script>"));
        assertFalse(email.html().contains("<img src=x onerror=alert(1)>"));
        assertTrue(email.html().contains("&lt;script&gt;"));
        assertTrue(email.html().contains("&lt;img"));
        assertFalse(email.html().contains("href=\"null\""));
    }

    @Test
    void notificationHasNoActionButton() {
        RenderedEmail email = renderer.render(factory.notification("Alex", "Update", "You have a new update."));
        assertFalse(email.html().contains("Open Sprintory</a>"));
        assertFalse(email.plainText().contains("null"));
    }
}
