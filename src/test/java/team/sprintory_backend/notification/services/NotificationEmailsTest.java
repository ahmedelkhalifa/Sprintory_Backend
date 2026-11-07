package team.sprintory_backend.notification.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import team.sprintory_backend.notification.email.EmailTemplateFactory;
import team.sprintory_backend.notification.email.EmailTemplateRenderer;
import team.sprintory_backend.notification.email.RenderedEmail;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;

/** The emails as they are queued: real template and renderer, the outbox mocked. */
class NotificationEmailsTest {

    private UserRepository users;
    private EmailOutboxService outbox;
    private NotificationEmails emails;
    private final User ada = User.builder().id(17L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
    private final User damir = User.builder().id(31L).firstName("Damir").lastName("Gabaidullin").build();
    private Workspace workspace;
    private Project project;
    private Task task;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        users = mock(UserRepository.class);
        outbox = mock(EmailOutboxService.class);
        emails = new NotificationEmails(users, new EmailTemplateFactory(), new EmailTemplateRenderer(engine), outbox);
        ReflectionTestUtils.setField(emails, "frontendUrl", "http://front");
        ReflectionTestUtils.setField(emails, "taskPath", "/w/{workspaceId}/projects/{projectId}/tasks/{taskKey}");
        ReflectionTestUtils.setField(emails, "projectPath", "/projects/{projectId}");
        ReflectionTestUtils.setField(emails, "preferencesPath", "/settings/notifications");
        when(users.findById(17L)).thenReturn(Optional.of(ada));

        workspace = Workspace.builder().id(4L).name("Acme").slug("acme").build();
        project = Project.builder().id(15L).workspace(workspace).name("Mobile App").key("MOB").build();
        task = Task.builder().id(230L).project(project).key("MOB-42").title("Fix login").build();
    }

    private RenderedEmail queued() {
        ArgumentCaptor<RenderedEmail> email = ArgumentCaptor.forClass(RenderedEmail.class);
        verify(outbox).enqueue(eq("ada@example.com"), email.capture());
        return email.getValue();
    }

    @Test
    void aTaskEmailHasTheKeyTitleExcerptAndLinks() {
        emails.queue(17L, NotificationType.MENTIONED, NotificationSubject.task(task, damir),
                "Damir Gabaidullin mentioned you in MOB-42 \"Fix login\"", "@Ada Lovelace please check");

        RenderedEmail email = queued();
        assertEquals("[Sprintory] MOB-42 Fix login: you were mentioned", email.subject());
        assertTrue(email.plainText().contains("Hi Ada,"), email.plainText());
        assertTrue(email.plainText().contains("mentioned you in MOB-42"));
        assertTrue(email.plainText().contains("@Ada Lovelace please check"));
        assertTrue(email.plainText().contains("http://front/w/4/projects/15/tasks/MOB-42"), email.plainText());
        assertTrue(email.plainText().contains("http://front/settings/notifications"));
        assertTrue(email.html().contains("Open task"));
    }

    @Test
    void aProjectEmailLinksToTheProject() {
        emails.queue(17L, NotificationType.PROJECT_MEMBER_ADDED, NotificationSubject.project(project, damir),
                "Damir Gabaidullin added you to the project \"Mobile App\"", null);

        RenderedEmail email = queued();
        assertEquals("[Sprintory] You were added to Mobile App", email.subject());
        assertTrue(email.plainText().contains("http://front/projects/15"));
        assertTrue(email.html().contains("Open project"));
    }

    @Test
    void aSecurityEmailLinksToTheAppItself() {
        emails.queue(17L, NotificationType.PASSWORD_RESET, NotificationSubject.system(),
                NotificationMessages.passwordReset(), null);

        RenderedEmail email = queued();
        assertEquals("[Sprintory] Your password was changed", email.subject());
        assertTrue(email.html().contains("Open Sprintory"));
        assertTrue(email.html().contains("href=\"http://front\""), email.html());
    }

    @Test
    void aTitleWithANewlineCannotInjectMailHeaders() {
        task.setTitle("Fix login\r\nBcc: attacker@example.com");

        emails.queue(17L, NotificationType.TASK_ASSIGNED, NotificationSubject.task(task, damir), "assigned", null);

        assertFalse(queued().subject().contains("\n"));
    }

    @Test
    void aVeryLongTitleGivesASubjectThatFitsTheColumn() {
        task.setTitle("x".repeat(255));

        emails.queue(17L, NotificationType.TASK_ASSIGNED, NotificationSubject.task(task, damir), "assigned", null);

        assertEquals(NotificationEmails.SUBJECT_LIMIT, queued().subject().length());
    }

    @Test
    void anEmailThatCannotBeBuiltIsSkippedQuietly() {
        ReflectionTestUtils.setField(emails, "frontendUrl", "not a url");

        emails.queue(17L, NotificationType.TASK_ASSIGNED, NotificationSubject.task(task, damir), "assigned", null);

        verifyNoInteractions(outbox);
    }

    @Test
    void aRecipientThatNoLongerExistsGetsNothing() {
        when(users.findById(99L)).thenReturn(Optional.empty());

        emails.queue(99L, NotificationType.TASK_ASSIGNED, NotificationSubject.task(task, damir), "assigned", null);

        verify(users).findById(99L);
        verifyNoInteractions(outbox);
    }

    @Test
    void aChatMentionEmailIsAboutTheProjectAndLinksToIt() {
        emails.queue(17L, NotificationType.MENTIONED, NotificationSubject.project(project, damir),
                "Damir Gabaidullin mentioned you in the \"Mobile App\" chat", "@Ada Lovelace standup?");

        RenderedEmail email = queued();
        assertEquals("[Sprintory] You were mentioned in the Mobile App chat", email.subject());
        assertTrue(email.plainText().contains("mentioned you in the \"Mobile App\" chat"), email.plainText());
        assertTrue(email.plainText().contains("@Ada Lovelace standup?"));
        assertTrue(email.plainText().contains("http://front/projects/15"), email.plainText());
    }

    @Test
    void everyTypeHasASubject() {
        for (NotificationType type : NotificationType.values()) {
            NotificationSubject subject = new NotificationSubject(workspace, project, task, damir);
            assertTrue(NotificationEmails.subjectLine(type, subject).startsWith("[Sprintory] "), type.name());
        }
        verify(outbox, never()).enqueue(any(), any());
    }
}