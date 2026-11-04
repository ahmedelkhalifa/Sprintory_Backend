package team.sprintory_backend.task.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import team.sprintory_backend.notification.events.TaskStatusChanged;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoard;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskStatusHistory;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.repositories.TaskStatusHistoryRepository;
import team.sprintory_backend.user.models.User;

/** Every status change goes through record(): it writes history and tells the watchers (R27). */
class TaskStatusHistoryServiceTest {

    private TaskStatusHistoryRepository historyRepository;
    private ApplicationEventPublisher events;
    private TaskStatusHistoryService service;
    private final User user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").build();
    private Task task;
    private ProjectBoardColumn todo;
    private ProjectBoardColumn done;

    @BeforeEach
    void setUp() {
        historyRepository = mock(TaskStatusHistoryRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TaskStatusHistoryService(historyRepository, mock(ProjectAccess.class),
                mock(TaskRepository.class), events);
        Project project = Project.builder().id(3L).name("Mobile App").key("MOB").build();
        ProjectBoard board = new ProjectBoard(project);
        todo = column(board, 12L, "To Do", 1);
        done = column(board, 14L, "Done", 3);
        task = Task.builder().id(5L).project(project).key("MOB-5").title("Fix login").status(todo).build();
    }

    private static ProjectBoardColumn column(ProjectBoard board, Long id, String name, int position) {
        ProjectBoardColumn column = new ProjectBoardColumn(board, name, position);
        column.setId(id);
        return column;
    }

    @Test
    void aMoveToAnotherColumnWritesHistoryAndPublishesTheChange() {
        service.record(task, todo, done, user);

        ArgumentCaptor<TaskStatusHistory> saved = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepository).save(saved.capture());
        assertEquals("To Do", saved.getValue().getFromColumnName());
        assertEquals("Done", saved.getValue().getToColumnName());
        verify(events).publishEvent(new TaskStatusChanged(task, user, "To Do", "Done"));
    }

    @Test
    void stayingInTheSameColumnWritesAndPublishesNothing() {
        service.record(task, todo, todo, user);

        verifyNoInteractions(historyRepository, events);
    }
}