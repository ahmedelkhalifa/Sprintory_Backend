package team.sprintory_backend.notification.services;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * The display text of every notification (docs/notifications-docs.md §5), in one place so the
 * bell and the emails say the same thing. Only names, keys, titles and short excerpts: never a
 * full comment, a token or a link.
 */
public final class NotificationMessages {

    public static final int MESSAGE_LIMIT = 1000;
    public static final int EXCERPT_LIMIT = 200;
    private static final String ELLIPSIS = "…";

    private NotificationMessages() {
    }

    public static String assigned(User actor, Task task) {
        return name(actor) + " assigned you " + task(task);
    }

    public static String statusChanged(User actor, Task task, String from, String to) {
        return name(actor) + " moved " + task(task) + " from " + from + " to " + to;
    }

    public static String commented(User actor, Task task) {
        return name(actor) + " commented on " + task(task);
    }

    public static String mentioned(User actor, Task task) {
        return name(actor) + " mentioned you in " + task(task);
    }

    public static String mentionedInChat(User actor, Project project) {
        return name(actor) + " mentioned you in the " + quoted(project.getName()) + " chat";
    }

    public static String memberAdded(User actor, Project project) {
        return name(actor) + " added you to the project " + quoted(project.getName());
    }

    public static String rolesChanged(User actor, Project project, Set<ProjectRole> roles) {
        return name(actor) + " changed your roles in " + quoted(project.getName()) + " to " + roles(roles);
    }

    public static String sprintStarted(User actor, Project project, String sprintName) {
        return name(actor) + " started " + quoted(sprintName) + " in " + quoted(project.getName());
    }

    public static String sprintCompleted(User actor, Project project, String sprintName) {
        return name(actor) + " completed " + quoted(sprintName) + " in " + quoted(project.getName());
    }

    public static String sprintCancelled(User actor, Project project, String sprintName) {
        return name(actor) + " cancelled " + quoted(sprintName) + " in " + quoted(project.getName());
    }

    /** "MOB Sprint 3" in "Mobile App" ended 2 days ago and is still active. Close it to carry over unfinished work. */
    public static String sprintOverdue(Project project, String sprintName, long daysOverdue) {
        return quoted(sprintName) + " in " + quoted(project.getName()) + " ended "
                + (daysOverdue == 1 ? "1 day" : daysOverdue + " days")
                + " ago and is still active. Close it to carry over unfinished work.";
    }

    public static String invited(User inviter, Workspace workspace) {
        return name(inviter) + " invited you to the workspace " + quoted(workspace.getName())
                + ". Open the invitation email to join.";
    }

    public static String passwordReset() {
        return "Your password was changed. If this wasn't you, reset it again now and contact your workspace admin.";
    }

    public static String loginFailures(int attempts) {
        return "There were " + attempts + " failed sign-in attempts on your account. "
                + "If this wasn't you, change your password.";
    }

    /**
     * Plain text of at most {@value #EXCERPT_LIMIT} characters, whitespace collapsed, ending in
     * "…" when cut. Null for null or blank text.
     */
    public static String excerpt(String plainText) {
        if (plainText == null) {
            return null;
        }
        String text = plainText.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return limit(text, EXCERPT_LIMIT);
    }

    /** Cuts to {@code max} characters, the last one being "…". */
    public static String limit(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    /** {@code [MEMBER, PROJECT_MANAGER]} → {@code Project Manager, Member}, in declaration order. */
    static String roles(Set<ProjectRole> roles) {
        return roles.stream()
                .sorted(Comparator.naturalOrder())
                .map(NotificationMessages::label)
                .collect(Collectors.joining(", "));
    }

    private static String label(ProjectRole role) {
        String[] words = role.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (label.length() > 0) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private static String name(User user) {
        return AuditTarget.displayName(user);
    }

    private static String task(Task task) {
        return task.getKey() + " " + quoted(task.getTitle());
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }
}