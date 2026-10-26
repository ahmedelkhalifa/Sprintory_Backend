package team.sprintory_backend.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.ProjectResponse;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.search.dto.SearchResultType;
import team.sprintory_backend.search.dto.UniversalSearchFilters;
import team.sprintory_backend.search.dto.UniversalSearchResult;
import team.sprintory_backend.search.services.UniversalSearchService;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.models.TeamMember;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Universal search against a real PostgreSQL: who sees what, scopes, order and paging.
 * Docker must be running. Each test is rolled back.
 *
 * <p>Data, all matching "auth" (rank: 0 exact, 1 prefix, 2 contains):
 * <ul>
 *   <li>workspace "The auth guild" (2): Ada admin; Grace and Linus members</li>
 *   <li>team "Auth" (0) with Grace; team "Oauth crew" (2) with Linus</li>
 *   <li>project "Auth service" (1), key SVC, Grace is its Project Manager:
 *       task SVC-1 "Fix auth login" (2), task SVC-2 "Auth cleanup" (deleted)</li>
 *   <li>project "Payments auth" (2), key PAY, Linus is its Project Manager: task PAY-1 "Auth tokens" (1)</li>
 *   <li>workspace "Auth" (0): Eve only</li>
 * </ul>
 * Ada is in neither project. Names within one rank start with different letters, so the order
 * does not depend on the database's collation.
 */
@Transactional
class UniversalSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamMemberRepository teamMemberRepository;
    @Autowired private ProjectService projects;
    @Autowired private TaskApiService tasks;
    @Autowired private UniversalSearchService search;

    private User ada;
    private User grace;
    private User linus;
    private User eve;
    private Workspace guild;
    private Workspace eveSpace;
    private ProjectResponse service;
    private ProjectResponse payments;

    @BeforeEach
    void setUp() {
        ada = userRepository.save(user("Ada", "ada@example.com"));
        grace = userRepository.save(user("Grace", "grace@example.com"));
        linus = userRepository.save(user("Linus", "linus@example.com"));
        eve = userRepository.save(user("Eve", "eve@example.com"));

        guild = workspaceRepository.save(Workspace.builder().name("The auth guild").slug("the-auth-guild").build());
        join(guild, ada, Role.WORKSPACE_ADMIN);
        join(guild, grace, Role.WORKSPACE_MEMBER);
        join(guild, linus, Role.WORKSPACE_MEMBER);
        eveSpace = workspaceRepository.save(Workspace.builder().name("Auth").slug("auth-eve").build());
        join(eveSpace, eve, Role.WORKSPACE_ADMIN);

        Team auth = teamRepository.save(Team.builder().workspace(guild).name("Auth").build());
        Team oauth = teamRepository.save(Team.builder().workspace(guild).name("Oauth crew").build());
        teamMemberRepository.save(TeamMember.builder().team(auth).user(grace).build());
        teamMemberRepository.save(TeamMember.builder().team(oauth).user(linus).build());

        service = projects.create(grace, guild.getId(),
                new CreateProjectRequest("Auth service", "SVC", ProjectTemplate.SCRUM, null));
        payments = projects.create(linus, guild.getId(),
                new CreateProjectRequest("Payments auth", "PAY", ProjectTemplate.KANBAN, null));
        task(grace, service, "Fix auth login");                                                       // SVC-1
        TaskResponse cleanup = task(grace, service, "Auth cleanup");                                   // SVC-2
        tasks.delete(grace, service.id(), cleanup.id());
        task(linus, payments, "Auth tokens");                                                          // PAY-1
    }

    // --- who sees what ---

    @Test
    void aPlainMemberSeesOnlyTheirOwnTeamsProjectsAndTasks() {
        assertEquals(List.of("TEAM Auth", "PROJECT Auth service", "TASK Fix auth login", "WORKSPACE The auth guild"),
                hits(grace, "auth", null, null, null));
        assertEquals(List.of("TASK Auth tokens", "TEAM Oauth crew", "PROJECT Payments auth", "WORKSPACE The auth guild"),
                hits(linus, "auth", null, null, null));
    }

    @Test
    void aWorkspaceAdminSeesEveryProjectAndTeamButNoTasksOfProjectsTheyAreNotIn() {
        assertEquals(List.of("TEAM Auth", "PROJECT Auth service", "TEAM Oauth crew", "PROJECT Payments auth",
                        "WORKSPACE The auth guild"),
                hits(ada, "auth", null, null, null));
    }

    @Test
    void someoneOutsideTheWorkspaceSeesNothingOfIt() {
        assertEquals(List.of("WORKSPACE Auth"), hits(eve, "auth", null, null, null));
        assertEquals(List.of(), hits(eve, "auth", null, guild.getId(), null));
        assertEquals(List.of(), hits(grace, "auth", null, eveSpace.getId(), null));
    }

    @Test
    void deletedTasksAreNeverFound() {
        assertEquals(List.of(), hits(grace, "cleanup", null, null, null));
        assertEquals(List.of(), hits(grace, "svc-2", null, null, null));
    }

    // --- matching, scopes and types ---

    @Test
    void tasksMatchOnTheirKeyAndCarryIt() {
        PageResponse<UniversalSearchResult> page = search.search(grace,
                UniversalSearchFilters.fromQuery("SVC-1", null, null, null), PageRequest.of(0, 20));

        assertEquals(1, page.totalElements());
        UniversalSearchResult hit = page.content().get(0);
        assertEquals(SearchResultType.TASK, hit.type());
        assertEquals("SVC-1", hit.taskKey());
        assertEquals("SVC · Auth service", hit.subtitle());
        assertEquals("/projects/" + service.id() + "/tasks/" + hit.id(), hit.path());
        assertEquals(guild.getId(), hit.workspaceId());
        assertEquals(service.id(), hit.projectId());
    }

    @Test
    void projectsMatchOnTheirKeyToo() {
        assertEquals(List.of("PROJECT Payments auth"), hits(ada, "pay", "project", null, null));
    }

    @Test
    void scopesAndTypesRestrictTheResults() {
        assertEquals(List.of("PROJECT Auth service", "TASK Fix auth login"),
                hits(grace, "auth", null, null, service.id()));
        assertEquals(List.of(), hits(grace, "auth", null, null, payments.id()), "Grace is not in Payments");
        assertEquals(List.of("TASK Fix auth login"), hits(grace, "auth", "task", null, null));
        assertEquals(List.of("TEAM Auth", "WORKSPACE The auth guild"), hits(grace, "auth", "team,workspace", null, null));
        assertEquals(List.of(), hits(grace, "auth", "team,workspace", null, service.id()));
    }

    @Test
    void wildcardsInTheQueryAreLiteral() {
        assertEquals(List.of(), hits(grace, "%", null, null, null));
        assertEquals(List.of(), hits(grace, "a_th", null, null, null));
    }

    // --- paging ---

    @Test
    void pagesFollowOneStableOrderWithTheRightTotals() {
        PageResponse<UniversalSearchResult> first = page(ada, 0);
        PageResponse<UniversalSearchResult> second = page(ada, 1);
        PageResponse<UniversalSearchResult> last = page(ada, 2);

        assertEquals(5, first.totalElements());
        assertEquals(3, first.totalPages());
        assertEquals(List.of("Auth", "Auth service"), titles(first));
        assertEquals(List.of("Oauth crew", "Payments auth"), titles(second));
        assertEquals(List.of("The auth guild"), titles(last));
        assertEquals(List.of(), titles(page(ada, 3)));
    }

    // --- helpers ---

    private List<String> hits(User user, String q, String types, Long workspaceId, Long projectId) {
        return search.search(user, UniversalSearchFilters.fromQuery(q, types, workspaceId, projectId),
                        PageRequest.of(0, 20)).content().stream()
                .map(hit -> hit.type() + " " + hit.title()).toList();
    }

    private PageResponse<UniversalSearchResult> page(User user, int page) {
        return search.search(user, UniversalSearchFilters.fromQuery("auth", null, null, null), PageRequest.of(page, 2));
    }

    private static List<String> titles(PageResponse<UniversalSearchResult> page) {
        return page.content().stream().map(UniversalSearchResult::title).toList();
    }

    private TaskResponse task(User user, ProjectResponse project, String title) {
        return tasks.create(user, project.id(), new CreateTaskRequest(title, null, null, null, null, null, null));
    }

    private void join(Workspace workspace, User user, Role role) {
        workspaceMemberRepository.save(WorkspaceMember.builder().workspace(workspace).user(user).role(role).build());
    }

    private static User user(String firstName, String email) {
        return User.builder().firstName(firstName).lastName("Tester").email(email).enabled(true).build();
    }
}