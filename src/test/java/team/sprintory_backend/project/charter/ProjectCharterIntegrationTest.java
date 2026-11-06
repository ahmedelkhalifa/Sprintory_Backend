package team.sprintory_backend.project.charter;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.charter.dto.*;
import team.sprintory_backend.project.charter.exceptions.CharterReadOnlyException;
import team.sprintory_backend.project.charter.models.*;
import team.sprintory_backend.project.charter.repositories.ProjectCharterRepository;
import team.sprintory_backend.project.charter.services.ProjectCharterService;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.*;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** PMP-116 against real PostgreSQL + Flyway. Requires Docker, follows the existing test profile. */
@Transactional
class ProjectCharterIntegrationTest extends AbstractIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private ProjectCharterService charter;
    @Autowired private ProjectCharterRepository charters;
    @Autowired private ProjectService projects;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository members;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;

    private User pm, po, member, viewer, outsider, workspaceAdmin;
    private Long projectId;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        pm = user("Parker", "Manager", "charter-pm@example.com");
        po = user("Pat", "Owner", "charter-po@example.com");
        member = user("Mira", "Member", "charter-member@example.com");
        viewer = user("Victor", "Viewer", "charter-viewer@example.com");
        outsider = user("Omar", "Other", "charter-other@example.com");
        workspaceAdmin = user("Wendy", "Admin", "charter-admin@example.com");
        workspace = workspaces.save(Workspace.builder().name("Charter Tests").slug("charter-tests").build());
        for (User u : List.of(pm, po, member, viewer, outsider)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(u)
                    .role(Role.WORKSPACE_MEMBER).build());
        }
        workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(workspaceAdmin)
                .role(Role.WORKSPACE_ADMIN).build());
        projectId = projects.create(pm, workspace.getId(),
                new CreateProjectRequest("Charter project", "CHA", ProjectTemplate.SCRUM, null)).id();
        add(po, ProjectRole.PRODUCT_OWNER);
        add(member, ProjectRole.MEMBER);
        add(viewer, ProjectRole.VIEWER);
        em.flush();
    }

    @Test
    void charterIsEmptyAtCreationAndViewersCanReadIt() {
        assertTrue(charters.existsById(projectId));
        CharterResponse read = charter.get(viewer, projectId);
        assertTrue(read.goals().isEmpty());
        assertTrue(read.stakeholders().isEmpty());
        assertNull(read.inScope());
        assertNull(read.outOfScope());
        assertTrue(read.readOnly());
        assertFalse(read.canEdit());
        assertTrue(charter.get(pm, projectId).canEdit());
        assertTrue(charter.get(po, projectId).canEdit());
    }

    @Test
    void goalsCanBeCreatedAndVisibleToAllMembersWithMeasureAndHistory() {
        CharterGoalResponse added = charter.addGoal(pm, projectId,
                new CharterGoalRequest("  Reduce onboarding time ", "under 1 day"));
        assertEquals("Reduce onboarding time", added.title());
        assertEquals("under 1 day", charter.get(viewer, projectId).goals().get(0).successMeasure());
        var history = charter.history(viewer, projectId, PageRequest.of(0, 10)).content();
        assertEquals(1, history.size());
        assertEquals(CharterChangeType.GOAL_ADDED, history.get(0).changeType());
        assertEquals(pm.getId(), history.get(0).actorId());
        assertNull(history.get(0).oldValue());
        assertTrue(history.get(0).newValue().contains("Reduce onboarding time"));
    }

    @Test
    void poCanEditGoalAndHistoryCapturesBothVersions() {
        Long goalId = charter.addGoal(pm, projectId, new CharterGoalRequest("Old", null)).id();
        charter.updateGoal(po, projectId, goalId, new CharterGoalRequest("New", "under 2 weeks"));
        var history = charter.history(pm, projectId, PageRequest.of(0, 10)).content();
        assertEquals(CharterChangeType.GOAL_UPDATED, history.get(0).changeType());
        assertEquals(po.getId(), history.get(0).actorId());
        assertTrue(history.get(0).oldValue().contains("Old"));
        assertTrue(history.get(0).newValue().contains("New"));
    }

    @Test
    void reorderPersistsOrderAndRejectsPartialOrCrossProjectIds() {
        var a = charter.addGoal(pm, projectId, new CharterGoalRequest("A", null));
        var b = charter.addGoal(pm, projectId, new CharterGoalRequest("B", null));
        var c = charter.addGoal(pm, projectId, new CharterGoalRequest("C", null));
        assertThrows(BadRequestException.class, () -> charter.reorderGoals(pm, projectId,
                new CharterGoalOrderRequest(List.of(c.id(), b.id()))));
        charter.reorderGoals(pm, projectId, new CharterGoalOrderRequest(List.of(c.id(), a.id(), b.id())));
        assertEquals(List.of("C", "A", "B"), charter.get(viewer, projectId).goals()
                .stream().map(CharterGoalResponse::title).toList());
        assertEquals(CharterChangeType.GOALS_REORDERED,
                charter.history(pm, projectId, PageRequest.of(0, 10)).content().get(0).changeType());
        charter.removeGoal(pm, projectId, a.id());
        var newGoal = charter.addGoal(pm, projectId, new CharterGoalRequest("D", null));
        assertEquals(2, newGoal.order());
    }

    @Test
    void scopeIsNormalizedUnsafeMarkupIsRejectedAndOldVersionsRemain() {
        String safe = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Web app\",\"marks\":[{\"type\":\"bold\"}]}]}]}";
        String newSafe = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Mobile app\"}]}]}";
        CharterResponse first = charter.replaceScope(po, projectId, new CharterScopeRequest(safe, newSafe));
        assertTrue(first.inScope().contains("bold"));
        assertThrows(InvalidRichTextException.class, () -> charter.replaceScope(po, projectId,
                new CharterScopeRequest("{\"type\":\"doc\",\"content\":[{\"type\":\"script\"}]}", null)));
        charter.replaceScope(pm, projectId, new CharterScopeRequest(newSafe, null));
        assertNull(charter.get(viewer, projectId).outOfScope());
        var history = charter.history(pm, projectId, PageRequest.of(0, 10)).content();
        assertEquals(4, history.size());
        assertEquals(CharterChangeType.OUT_OF_SCOPE_CHANGED, history.get(0).changeType());
        assertEquals(newSafe, history.get(0).oldValue());
        assertNull(history.get(0).newValue());
    }

    @Test
    void externalStakeholderAndLinkedWorkspaceUserAreSupportedWithoutNotifications() {
        CharterStakeholderResponse external = charter.addStakeholder(pm, projectId,
                new CharterStakeholderRequest("Acme Corp", "Acme", "Sponsor",
                        StakeholderLevel.HIGH, StakeholderLevel.HIGH, "sponsor@acme.test", null));
        assertNull(external.linkedUserId());
        assertEquals("sponsor@acme.test", charter.get(viewer, projectId).stakeholders().get(0).contactEmail());
        CharterStakeholderResponse linked = charter.addStakeholder(po, projectId,
                new CharterStakeholderRequest("Pat Owner", null, "Product owner",
                        null, null, null, outsider.getId()));
        assertEquals(outsider.getId(), linked.linkedUserId());
        assertEquals("Omar Other", linked.linkedUserName());
        User notInWorkspace = user("Not", "Here", "outside-workspace@example.com");
        assertThrows(BadRequestException.class, () -> charter.addStakeholder(pm, projectId,
                new CharterStakeholderRequest("Outside", null, null, null, null, null, notInWorkspace.getId())));
        // Goal/scope history never includes contact email.
        assertTrue(charter.history(pm, projectId, PageRequest.of(0, 10)).content().isEmpty());
    }

    @Test
    void membersAndViewersCannotMutateAndOutsidersGetNotFoundIncludingWorkspaceAdmin() {
        CharterGoalRequest request = new CharterGoalRequest("No", null);
        assertThrows(AccessDeniedException.class, () -> charter.addGoal(member, projectId, request));
        assertThrows(AccessDeniedException.class, () -> charter.addGoal(viewer, projectId, request));
        assertThrows(ResourceNotFoundException.class, () -> charter.get(outsider, projectId));
        assertThrows(ResourceNotFoundException.class, () -> charter.get(workspaceAdmin, projectId));
        assertThrows(ResourceNotFoundException.class, () -> charter.addGoal(outsider, projectId, request));
        assertThrows(ResourceNotFoundException.class, () -> charter.history(outsider, projectId, PageRequest.of(0, 10)));
    }

    @Test
    void projectCompletedAndArchivedAndWorkspaceArchivedAreReadOnly() {
        Project project = projectRepository.findById(projectId).orElseThrow();
        for (ProjectLifecycleState state : List.of(ProjectLifecycleState.COMPLETED, ProjectLifecycleState.ARCHIVED)) {
            project.setLifecycleStatus(state);
            em.flush();
            assertTrue(charter.get(pm, projectId).readOnly());
            assertThrows(CharterReadOnlyException.class,
                    () -> charter.addGoal(pm, projectId, new CharterGoalRequest("Cannot", null)));
        }
        project.setLifecycleStatus(ProjectLifecycleState.ACTIVE);
        workspace.setArchivedAt(java.time.Instant.now());
        em.flush();
        assertTrue(charter.get(pm, projectId).readOnly());
    }

    private User user(String first, String last, String email) {
        return users.save(User.builder().firstName(first).lastName(last).email(email).enabled(true).build());
    }

    private void add(User user, ProjectRole role) {
        members.save(ProjectMember.builder().project(projectRepository.getReferenceById(projectId)).user(user)
                .roles(new java.util.HashSet<>(Set.of(role))).build());
    }
}
