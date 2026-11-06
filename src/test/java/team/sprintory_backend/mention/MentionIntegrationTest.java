package team.sprintory_backend.mention;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.mention.dto.MentionablesResponse;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.models.TeamMember;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Mentions (PMP-136) against a real PostgreSQL: the suggestion queries and the team expansion.
 * Docker must be running. Each test is rolled back.
 */
@Transactional
class MentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectService projects;
    @Autowired private MentionService mentions;
    @Autowired private RichTextParser parser;
    @Autowired private TaskApiService tasks;

    private User owner;
    private User grace;
    private User viewer;
    private User linus;
    private Project project;
    private Team backend;
    private Team design;
    private Team otherWorkspaceTeam;

    /**
     * Acme: Owner created the project; Grace (Member) and Vera (Viewer) are in it, Linus is only in
     * the workspace. Team Backend = Grace + Linus, team Design = Linus only. Another workspace has
     * a team holding Grace.
     */
    @BeforeEach
    void setUp() {
        owner = userRepository.save(user("Owner", "Smith", "owner-m@example.com"));
        grace = userRepository.save(user("Grace", "Hopper", "grace-m@example.com"));
        viewer = userRepository.save(user("Vera", "Viewer", "vera-m@example.com"));
        linus = userRepository.save(user("Linus", "Outside", "linus-m@example.com"));
        Workspace acme = workspaceRepository.save(Workspace.builder().name("Acme").slug("acme-mentions").build());
        for (User u : List.of(owner, grace, viewer, linus)) {
            join(acme, u);
        }
        Long projectId = projects.create(owner, acme.getId(),
                new CreateProjectRequest("Mobile App", "MOB", ProjectTemplate.SCRUM, null)).id();
        project = projectRepository.findWithWorkspaceById(projectId).orElseThrow();
        addToProject(grace, ProjectRole.MEMBER);
        addToProject(viewer, ProjectRole.VIEWER);

        backend = team(acme, "Backend", grace, linus);
        design = team(acme, "Design", linus);
        Workspace other = workspaceRepository.save(Workspace.builder().name("Other").slug("other-mentions").build());
        join(other, grace);
        otherWorkspaceTeam = team(other, "Backend elsewhere", grace);
        entityManager.flush();
    }

    // --- suggestions ---

    @Test
    void suggestionsListProjectMembersIncludingViewersButNotTheCaller() {
        MentionablesResponse response = mentions.mentionables(owner, project.getId(), null);

        assertEquals(List.of("Grace Hopper", "Vera Viewer"),
                response.users().stream().map(MentionablesResponse.UserItem::name).toList());
    }

    @Test
    void peopleMatchByFirstNameLastNameFullNameOrEmail() {
        assertEquals(List.of(grace.getId()), userIds(mentions.mentionables(owner, project.getId(), "GRA")));
        assertEquals(List.of(grace.getId()), userIds(mentions.mentionables(owner, project.getId(), "hopper")));
        assertEquals(List.of(grace.getId()), userIds(mentions.mentionables(owner, project.getId(), "grace hop")));
        assertEquals(List.of(viewer.getId()), userIds(mentions.mentionables(owner, project.getId(), "vera-m@")));
        assertEquals(List.of(), userIds(mentions.mentionables(owner, project.getId(), "linus")));
    }

    @Test
    void suggestionsListOnlyThisWorkspacesTeamsWithSomeoneInTheProject() {
        MentionablesResponse response = mentions.mentionables(owner, project.getId(), "");

        assertEquals(List.of(new MentionablesResponse.TeamItem(backend.getId(), "Backend", 1)), response.teams());
    }

    @Test
    void teamsMatchByName() {
        assertEquals(1, mentions.mentionables(owner, project.getId(), "back").teams().size());
        assertEquals(0, mentions.mentionables(owner, project.getId(), "design").teams().size());
    }

    // --- newly mentioned people ---

    @Test
    void aTeamMentionReachesOnlyItsMembersInTheProject() {
        assertEquals(Set.of(grace.getId()),
                mentions.newlyMentionedUserIds(project, text(mention("team", backend.getId())), null));
    }

    @Test
    void aViewerCanBeMentioned() {
        assertEquals(Set.of(viewer.getId()),
                mentions.newlyMentionedUserIds(project, text(mention("user", viewer.getId())), null));
    }

    @Test
    void outsidersAndTeamsWithNobodyHereOrFromAnotherWorkspaceAreRefused() {
        assertThrows(InvalidMentionException.class,
                () -> mentions.newlyMentionedUserIds(project, text(mention("user", linus.getId())), null));
        assertThrows(InvalidMentionException.class,
                () -> mentions.newlyMentionedUserIds(project, text(mention("team", design.getId())), null));
        assertThrows(InvalidMentionException.class,
                () -> mentions.newlyMentionedUserIds(project, text(mention("team", otherWorkspaceTeam.getId())), null));
    }

    // --- task descriptions, end to end (PMP-136 → R27) ---

    @Test
    void mentioningInADescriptionNotifiesEachPersonOnceAndEditingNotifiesOnlyNewOnes() {
        String first = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + mention("user", grace.getId()) + "," + mention("team", backend.getId()) + "]}]}";
        TaskResponse task = tasks.create(owner, project.getId(),
                new CreateTaskRequest("Fix login", null, null, null, null, null, first));
        entityManager.flush();

        assertEquals(1, mentionsOf(grace), "named and through the team, still one notification");
        assertEquals(0, mentionsOf(owner), "the author is never told");

        String second = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + mention("user", grace.getId()) + "," + mention("user", viewer.getId()) + "]}]}";
        tasks.update(owner, project.getId(), task.id(), new UpdateTaskRequest(null, null, null, null, null, null,
                null, second, false, false, false));
        entityManager.flush();

        assertEquals(1, mentionsOf(grace), "already mentioned before the edit");
        assertEquals(1, mentionsOf(viewer));
    }

    @Test
    void aDescriptionMentioningAnOutsiderOrInPlainTextIsRefused() {
        String outsider = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + mention("user", linus.getId()) + "]}]}";

        assertThrows(InvalidMentionException.class, () -> tasks.create(owner, project.getId(),
                new CreateTaskRequest("Fix login", null, null, null, null, null, outsider)));
        assertThrows(InvalidRichTextException.class, () -> tasks.create(owner, project.getId(),
                new CreateTaskRequest("Fix login", null, null, null, null, null, "Steps to reproduce")));
    }

    // --- helpers ---

    private long mentionsOf(User recipient) {
        return ((Number) entityManager.createNativeQuery(
                        "select count(*) from notifications where recipient_id = :id and type = 'MENTIONED'")
                .setParameter("id", recipient.getId()).getSingleResult()).longValue();
    }

    private static List<Long> userIds(MentionablesResponse response) {
        return response.users().stream().map(MentionablesResponse.UserItem::id).toList();
    }

    private RichTextDocument text(String mention) {
        return parser.parse("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[" + mention + "]}]}");
    }

    private static String mention(String kind, Long id) {
        return "{\"type\":\"mention\",\"attrs\":{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\"}}";
    }

    private void addToProject(User user, ProjectRole role) {
        entityManager.persist(ProjectMember.builder().project(project).user(user)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private Team team(Workspace workspace, String name, User... members) {
        Team team = Team.builder().workspace(workspace).name(name).build();
        entityManager.persist(team);
        for (User member : members) {
            entityManager.persist(TeamMember.builder().team(team).user(member).build());
        }
        return team;
    }

    private void join(Workspace target, User user) {
        workspaceMemberRepository.save(WorkspaceMember.builder().workspace(target).user(user)
                .role(Role.WORKSPACE_MEMBER).build());
    }

    private static User user(String firstName, String lastName, String email) {
        return User.builder().firstName(firstName).lastName(lastName).email(email).enabled(true).build();
    }
}