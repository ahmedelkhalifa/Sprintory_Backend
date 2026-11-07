package team.sprintory_backend.task.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.notification.events.Mentioned;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The description and label rules shared by tasks ({@link TaskApiService}) and subtasks
 * ({@link SubtaskService}): rich-text descriptions, their @mentions (PMP-136) and label cleanup.
 */
@Component
@RequiredArgsConstructor
class TaskContent {

    private final RichTextParser richText;
    private final MentionService mentions;
    private final ApplicationEventPublisher events;

    /** Rich text is kept as sent; a blank or empty description means none. */
    RichTextDocument parseDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        RichTextDocument document = richText.parse(description);
        return document.isEmpty() ? null : document;
    }

    /**
     * The people newly mentioned in {@code description}, compared with the description stored on
     * {@code existing} (null for a new task). Checks them against the project's members, so call
     * it before anything is written.
     */
    Set<Long> newMentions(Project project, RichTextDocument description, Task existing) {
        if (description == null) {
            return Set.of();
        }
        return mentions.newlyMentionedUserIds(project, description,
                existing == null ? null : storedDescription(existing));
    }

    /** Tells the newly mentioned people; the listener drops the author and non-members. */
    void publishMentions(Task task, User user, Set<Long> mentioned, RichTextDocument description) {
        if (!mentioned.isEmpty()) {
            events.publishEvent(new Mentioned(task, user, mentioned, description.plainText()));
        }
    }

    /** Trimmed; duplicates that differ only in case collapse into the first spelling. */
    static Set<String> normalizeLabels(List<String> labels) {
        Set<String> result = new LinkedHashSet<>();
        if (labels == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (String label : labels) {
            String trimmed = label.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * The stored description, to tell new mentions from old ones. A description saved before
     * 4 Nov 2026 may be plain text; it holds no mentions, so every mention in the new one is new.
     */
    private RichTextDocument storedDescription(Task task) {
        if (task.getDescription() == null) {
            return null;
        }
        try {
            return richText.parse(task.getDescription());
        } catch (InvalidRichTextException e) {
            return null;
        }
    }
}