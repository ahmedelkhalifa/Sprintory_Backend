package team.sprintory_backend.workspace;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.security.auth.services.OpaqueTokens;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.models.TeamMember;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceInvitation;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceInvitationRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the hand-written queries and the ON DELETE CASCADE rules against a real PostgreSQL
 * (Testcontainers, so Docker must be running). The mocked service tests cannot check either.
 * Each test runs in a transaction that is rolled back, so tests do not see each other's data.
 */
@Transactional
class WorkspaceDataIntegrationTest extends AbstractIntegrationTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private WorkspaceInvitationRepository invitationRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamMemberRepository teamMemberRepository;
    @Autowired private ProjectRepository projectRepository;

    private User ada;
    private User grace;
    private User linus;
    private Workspace workspace;
    private Team backend;
    private Team design;
    private Project mobileApp;
    private Project website;

    /**
     * Acme: Ada is admin; Grace and Linus are members. Grace is in team Backend and project
     * Mobile App; Linus is in team Design and no project. Website has no members.
     */
    @BeforeEach
    void setUp() {
        ada = userRepository.save(user("Ada", "Lovelace", "ada@example.com"));
        grace = userRepository.save(user("Grace", "Hopper", "grace@example.com"));
        linus = userRepository.save(user("Linus", "Torvalds", "linus@sample.org"));

        workspace = workspaceRepository.save(Workspace.builder().name("Acme").slug("acme-it").build());
        join(ada, Role.WORKSPACE_ADMIN);
        join(grace, Role.WORKSPACE_MEMBER);
        join(linus, Role.WORKSPACE_MEMBER);

        backend = teamRepository.save(Team.builder().workspace(workspace).name("Backend").build());
        design = teamRepository.save(Team.builder().workspace(workspace).name("Design").build());
        teamMemberRepository.save(TeamMember.builder().team(backend).user(grace).build());
        teamMemberRepository.save(TeamMember.builder().team(design).user(linus).build());

        mobileApp = projectRepository.save(Project.builder().workspace(workspace).name("Mobile App").build());
        website = projectRepository.save(Project.builder().workspace(workspace).name("Website").build());
        entityManager.persist(ProjectMember.builder().project(mobileApp).user(grace).build());

        invitationRepository.save(invitation("new@example.com", "old-token"));
        entityManager.flush();
    }

    // --- search and pagination ---

    @Test
    void memberSearchMatchesFirstNameLastNameAndEmailIgnoringCase() {
        assertEquals(List.of("ada@example.com"), searchMembers("ada"));
        assertEquals(List.of("ada@example.com"), searchMembers("LOVELACE"));
        assertEquals(List.of("ada@example.com", "grace@example.com"), searchMembers("@example"));
        assertEquals(List.of("ada@example.com", "grace@example.com", "linus@sample.org"), searchMembers(null));
        assertEquals(List.of(), searchMembers("nobody"));
    }

    @Test
    void memberListIsPagedInNameOrderWithCorrectTotals() {
        Page<WorkspaceMember> first = workspaceMemberRepository
                .searchByWorkspaceId(workspace.getId(), "%", PageRequest.of(0, 2));
        Page<WorkspaceMember> second = workspaceMemberRepository
                .searchByWorkspaceId(workspace.getId(), "%", PageRequest.of(1, 2));

        assertEquals(3, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(List.of("Ada", "Grace"), first.map(m -> m.getUser().getFirstName()).getContent());
        assertEquals(List.of("Linus"), second.map(m -> m.getUser().getFirstName()).getContent());
    }

    @Test
    void teamAndProjectMemberSearchesStayInsideTheirTeamOrProject() {
        assertEquals(1, teamMemberRepository.searchByTeamId(backend.getId(), "%", FIRST_PAGE).getTotalElements());
        assertEquals(0, teamMemberRepository.searchByTeamId(backend.getId(), "%linus%", FIRST_PAGE)
                .getTotalElements());
        assertEquals(List.of("grace@example.com"), teamMemberRepository
                .searchByTeamId(backend.getId(), "%hop%", FIRST_PAGE).map(m -> m.getUser().getEmail()).getContent());
    }

    // --- "my teams" and "my projects" ---

    @Test
    void plainMembersSeeOnlyTheirOwnTeamsAndAdminsSeeAll() {
        Page<Team> graceTeams = teamRepository
                .searchByWorkspaceIdAndMemberId(workspace.getId(), grace.getId(), "%", FIRST_PAGE);
        Page<Team> allTeams = teamRepository.searchByWorkspaceId(workspace.getId(), "%", FIRST_PAGE);

        assertEquals(List.of("Backend"), graceTeams.map(Team::getName).getContent());
        assertEquals(1, graceTeams.getTotalElements());
        assertEquals(List.of("Backend", "Design"), allTeams.map(Team::getName).getContent());
        assertEquals(List.of("Design"), teamRepository
                .searchByWorkspaceId(workspace.getId(), Pageables.containsPattern("DES"), FIRST_PAGE)
                .map(Team::getName).getContent());
    }

    @Test
    void plainMembersSeeOnlyTheirOwnProjectsAndAdminsSeeAll() {
        Page<Project> graceProjects = projectRepository
                .searchByWorkspaceIdAndMemberId(workspace.getId(), grace.getId(), "%", FIRST_PAGE);
        Page<Project> linusProjects = projectRepository
                .searchByWorkspaceIdAndMemberId(workspace.getId(), linus.getId(), "%", FIRST_PAGE);
        Page<Project> allProjects = projectRepository.searchByWorkspaceId(workspace.getId(), "%", FIRST_PAGE);

        assertEquals(List.of("Mobile App"), graceProjects.map(Project::getName).getContent());
        assertEquals(0, linusProjects.getTotalElements());
        assertEquals(List.of("Mobile App", "Website"), allProjects.map(Project::getName).getContent());
    }

    @Test
    void membershipLookupReturnsOnlyWorkspaceMembers() {
        User outsider = userRepository.save(user("Out", "Sider", "out@example.com"));

        List<WorkspaceMember> found = workspaceMemberRepository.findAllByWorkspaceIdAndUserIdIn(
                workspace.getId(), List.of(grace.getId(), outsider.getId()));

        assertEquals(List.of(grace.getId()), found.stream().map(m -> m.getUser().getId()).toList());
    }

    // --- invitations ---

    @Test
    void replacingAnInvitationDoesNotHitTheUniqueConstraint() {
        invitationRepository.deleteByWorkspaceIdAndEmail(workspace.getId(), "new@example.com");
        invitationRepository.save(invitation("new@example.com", "new-token"));

        assertDoesNotThrow(() -> entityManager.flush());
        entityManager.clear();
        assertTrue(invitationRepository.findByTokenHash(OpaqueTokens.hash("old-token")).isEmpty());
        assertTrue(invitationRepository.findByTokenHash(OpaqueTokens.hash("new-token")).isPresent());
    }

    // --- permanent delete ---

    @Test
    void deletingAWorkspaceRemovesEverythingInsideItButNotTheUsers() {
        Long workspaceId = workspace.getId();
        assertTrue(count("workspace_members", "workspace_id", workspaceId) > 0);
        assertTrue(count("team_members", "team_id", backend.getId()) > 0);
        assertTrue(count("project_members", "project_id", mobileApp.getId()) > 0);
        entityManager.clear();

        // The same path as WorkspaceService.delete: load the admin's membership, delete its workspace.
        Workspace loaded = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, ada.getId())
                .orElseThrow().getWorkspace();
        workspaceRepository.delete(loaded);
        entityManager.flush();
        entityManager.clear();

        assertEquals(0, count("workspaces", "id", workspaceId));
        assertEquals(0, count("workspace_members", "workspace_id", workspaceId));
        assertEquals(0, count("workspace_invitations", "workspace_id", workspaceId));
        assertEquals(0, count("teams", "workspace_id", workspaceId));
        assertEquals(0, count("team_members", "team_id", backend.getId()));
        assertEquals(0, count("team_members", "team_id", design.getId()));
        assertEquals(0, count("projects", "workspace_id", workspaceId));
        assertEquals(0, count("project_members", "project_id", mobileApp.getId()));
        assertTrue(userRepository.findById(grace.getId()).isPresent());
    }

    @Test
    void deletingATeamRemovesItsMemberRowsOnly() {
        entityManager.clear();

        teamRepository.delete(teamRepository.findWithWorkspaceById(backend.getId()).orElseThrow());
        entityManager.flush();

        assertEquals(0, count("team_members", "team_id", backend.getId()));
        assertEquals(1, count("workspace_members", "user_id", grace.getId()));
        assertEquals(1, count("project_members", "user_id", grace.getId()));
    }

    // --- fixtures ---

    private List<String> searchMembers(String q) {
        return workspaceMemberRepository
                .searchByWorkspaceId(workspace.getId(), Pageables.containsPattern(q), FIRST_PAGE)
                .map(m -> m.getUser().getEmail()).getContent();
    }

    private void join(User user, Role role) {
        workspaceMemberRepository.save(WorkspaceMember.builder().workspace(workspace).user(user).role(role).build());
    }

    private WorkspaceInvitation invitation(String email, String rawToken) {
        return WorkspaceInvitation.builder().workspace(workspace).email(email)
                .tokenHash(OpaqueTokens.hash(rawToken)).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Counts rows straight from the table, past the persistence context. */
    private long count(String table, String column, Long id) {
        return ((Number) entityManager
                .createNativeQuery("select count(*) from " + table + " where " + column + " = :id")
                .setParameter("id", id)
                .getSingleResult()).longValue();
    }

    private static User user(String firstName, String lastName, String email) {
        return User.builder().firstName(firstName).lastName(lastName).email(email).enabled(true).build();
    }
}