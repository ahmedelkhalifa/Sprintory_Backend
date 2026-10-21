package team.sprintory_backend.notification.email;

import java.time.Year;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;

/** Creates HTML from one reusable layout and a matching plain-text alternative. */
@Component
public class EmailTemplateRenderer {
    private final SpringTemplateEngine templateEngine;

    public EmailTemplateRenderer(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public RenderedEmail render(EmailTemplateModel model) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("subject", model.subject());
        context.setVariable("name", model.recipientName());
        context.setVariable("heading", model.heading());
        context.setVariable("message", model.message());
        context.setVariable("actionLabel", model.actionLabel());
        context.setVariable("actionUrl", model.actionUrl());
        context.setVariable("note", model.note());
        context.setVariable("year", Year.now(ZoneOffset.UTC).getValue());

        String html = templateEngine.process("email/layout", context);
        StringJoiner text = new StringJoiner("\n\n");
        text.add("Hi " + model.recipientName() + ",");
        text.add(model.heading());
        text.add(model.message());
        if (model.actionUrl() != null) {
            text.add(model.actionLabel() + ": " + model.actionUrl());
        }
        if (model.note() != null && !model.note().isBlank()) {
            text.add(model.note());
        }
        text.add("Sprintory");
        return new RenderedEmail(model.subject(), text.toString(), html);
    }
}
