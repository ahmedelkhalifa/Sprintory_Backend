package team.sprintory_backend.project;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectBoardColumnRepository;
import team.sprintory_backend.project.repositories.ProjectBoardRepository;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL/Flyway and JPA test; each method rolls back its changes. */
@Transactional
class ProjectBoardCreationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private EntityManager entityManager;
    @Autowired private ProjectService projects;
    @Autowired private ProjectBoardService boards;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectBoardRepository boardRepository;
    @Autowired private ProjectBoardColumnRepository columnRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;

    @Test
    void newScrumAndKanbanProjectsHaveIndependentPersistedEmptyBoards() {
        User creator = userRepository.save(User.builder().firstName("Ada").lastName("Lovelace")
                .email("boards-creator@example.com").enabled(true).build());
        Workspace workspace = workspaceRepository.save(Workspace.builder()
                .name("Boards").slug("boards-integration-test").build());
        workspaceMemberRepository.save(WorkspaceMember.builder().workspace(workspace).user(creator)
                .role(Role.WORKSPACE_MEMBER).build());

        var scrum = projects.create(creator, workspace.getId(),
                new CreateProjectRequest("Scrum Pilot", ProjectTemplate.SCRUM));
        var kanban = projects.create(creator, workspace.getId(),
                new CreateProjectRequest("Kanban Pilot", ProjectTemplate.KANBAN));
        entityManager.flush();
        entityManager.clear();

        assertEquals(Set.of(ProjectRole.PROJECT_MANAGER), scrum.roles());
        assertEquals(Set.of(ProjectRole.PROJECT_MANAGER), kanban.roles());
        assertEquals(Set.of(ProjectRole.PROJECT_MANAGER), projectMemberRepository
                .findWithRolesByProjectIdAndUserId(scrum.id(), creator.getId()).orElseThrow().getRoles());
        assertTrue(boardRepository.existsById(scrum.id()));
        assertTrue(boardRepository.existsById(kanban.id()));

        var scrumBoard = boards.get(creator, scrum.id());
        assertTrue(scrumBoard.backlogEnabled());
        assertTrue(scrumBoard.sprintsEnabled());
        assertFalse(scrumBoard.wipLimitsEnabled());
        assertNull(scrumBoard.activeSprintId());
        assertEquals(List.of("To Do", "In Progress", "Done"),
                scrumBoard.columns().stream().map(c -> c.name()).toList());
        assertEquals(List.of(0, 1, 2), scrumBoard.columns().stream().map(c -> c.position()).toList());
        assertTrue(scrumBoard.columns().stream().allMatch(c -> c.id() != null && c.wipLimit() == null));

        var kanbanBoard = boards.get(creator, kanban.id());
        assertFalse(kanbanBoard.backlogEnabled());
        assertFalse(kanbanBoard.sprintsEnabled());
        assertTrue(kanbanBoard.wipLimitsEnabled());
        assertNull(kanbanBoard.activeSprintId());
        assertEquals(List.of("To Do", "In Progress", "In Review", "Done"),
                kanbanBoard.columns().stream().map(c -> c.name()).toList());
        assertTrue(kanbanBoard.columns().stream().allMatch(c -> c.wipLimit() == null));
        assertEquals(3, columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(scrum.id()).size());
        assertEquals(4, columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(kanban.id()).size());

        // Deleting a project must cascade to its board and all columns.
        projectRepository.deleteById(scrum.id());
        entityManager.flush();
        entityManager.clear();
        assertFalse(boardRepository.existsById(scrum.id()));
        assertTrue(columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(scrum.id()).isEmpty());
        assertTrue(boardRepository.existsById(kanban.id()));
    }
}
