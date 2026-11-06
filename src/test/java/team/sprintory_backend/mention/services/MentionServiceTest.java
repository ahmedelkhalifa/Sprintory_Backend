package team.sprintory_backend.mention.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.dto.MentionablesResponse;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** The mention rules of docs/mentions-docs.md §4 and the suggestion list of §3. */
class MentionServiceTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long WORKSPACE_ID = 8L;
    private static final Long ADA = 42L;
    private static final Long DAMIR = 31L;
    private static final Long MOBEEN = 17L;
    private static final Long OUTSIDER = 99L;
    private static final Long BACKEND_TEAM = 5L;

    private final RichTextParser parser = new RichTextParser(JsonMapper.builder().build());

    private ProjectAccess access;
    private ProjectMemberRepository projectMemberRepository;
    private TeamRepository teamRepository;
    private TeamMemberRepository teamMemberRepository;
    private MentionService service;
    private Project project;
    private User ada;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccess.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        service = new MentionService(access, projectMemberRepository, teamRepository, teamMemberRepository);

        Workspace workspace = Workspace.builder().id(WORKSPACE_ID).name("Acme").build();
        project = Project.builder().id(PROJECT_ID).workspace(workspace).name("Mobile App").key("MOB").build();
        ada = User.builder().id(ADA).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();

        when(projectMemberRepository.findUserIdsByProjectId(PROJECT_ID)).thenReturn(List.of(ADA, DAMIR, MOBEEN));
    }

    // --- newly mentioned people ---

    @Test
    void aNewTextMentionsEveryPersonInIt() {
        assertEquals(Set.of(DAMIR, MOBEEN), service.newlyMentionedUserIds(project, text(user(DAMIR), user(MOBEEN)), null));
    }

    @Test
    void aTextWithoutMentionsNeedsNoQuery() {
        assertEquals(Set.of(), service.newlyMentionedUserIds(project, parser.parse(doc("")), null));
        verifyNoInteractions(projectMemberRepository, teamMemberRepository);
    }

    @Test
    void editingCountsOnlyMentionsThatWereNotThereBefore() {
        RichTextDocument before = text(user(DAMIR));
        RichTextDocument after = text(user(DAMIR), user(MOBEEN));

        assertEquals(Set.of(MOBEEN), service.newlyMentionedUserIds(project, after, before));
    }

    @Test
    void editingWithoutNewMentionsNotifiesNobodyAndQueriesNothing() {
        RichTextDocument before = text(user(DAMIR), user(MOBEEN));

        assertEquals(Set.of(), service.newlyMentionedUserIds(project, text(user(MOBEEN)), before));
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void anOldMentionOfSomeoneWhoLeftIsKept() {
        RichTextDocument before = text(user(OUTSIDER));
        RichTextDocument after = text(user(OUTSIDER), user(DAMIR));

        assertEquals(Set.of(DAMIR), service.newlyMentionedUserIds(project, after, before));
    }

    @Test
    void aNewMentionOfANonMemberIsRefused() {
        InvalidMentionException e = assertThrows(InvalidMentionException.class,
                () -> service.newlyMentionedUserIds(project, text(user(DAMIR), user(OUTSIDER)), null));

        assertEquals("Only members of this project can be mentioned.", e.getMessage());
    }

    @Test
    void mentioningYourselfIsAllowedHereAndDroppedByTheListener() {
        assertEquals(Set.of(ADA), service.newlyMentionedUserIds(project, text(user(ADA)), null));
    }

    // --- @team ---

    @Test
    void aTeamExpandsToItsMembersWhoAreInTheProject() {
        when(teamMemberRepository.findUserIdsByTeamIdAndWorkspaceId(BACKEND_TEAM, WORKSPACE_ID))
                .thenReturn(List.of(DAMIR, OUTSIDER, MOBEEN));

        assertEquals(Set.of(DAMIR, MOBEEN), service.newlyMentionedUserIds(project, text(team(BACKEND_TEAM)), null));
    }

    @Test
    void aPersonMentionedTwiceOrAlsoThroughATeamIsCountedOnce() {
        when(teamMemberRepository.findUserIdsByTeamIdAndWorkspaceId(BACKEND_TEAM, WORKSPACE_ID))
                .thenReturn(List.of(DAMIR, MOBEEN));

        Set<Long> ids = service.newlyMentionedUserIds(project,
                text(user(DAMIR), team(BACKEND_TEAM), user(DAMIR)), null);

        assertEquals(Set.of(DAMIR, MOBEEN), ids);
    }

    @Test
    void aTeamWithNobodyInTheProjectIsRefused() {
        when(teamMemberRepository.findUserIdsByTeamIdAndWorkspaceId(BACKEND_TEAM, WORKSPACE_ID))
                .thenReturn(List.of(OUTSIDER));

        InvalidMentionException e = assertThrows(InvalidMentionException.class,
                () -> service.newlyMentionedUserIds(project, text(team(BACKEND_TEAM)), null));

        assertEquals("This team has no members in this project.", e.getMessage());
    }

    @Test
    void aTeamOfAnotherWorkspaceOrAMissingTeamIsRefusedTheSameWay() {
        when(teamMemberRepository.findUserIdsByTeamIdAndWorkspaceId(anyLong(), anyLong())).thenReturn(List.of());

        InvalidMentionException e = assertThrows(InvalidMentionException.class,
                () -> service.newlyMentionedUserIds(project, text(team(777L)), null));

        assertEquals("This team has no members in this project.", e.getMessage());
    }

    @Test
    void aTeamAlreadyInTheTextIsNotExpandedAgain() {
        RichTextDocument before = text(team(BACKEND_TEAM));

        assertEquals(Set.of(DAMIR), service.newlyMentionedUserIds(project, text(team(BACKEND_TEAM), user(DAMIR)), before));
        verifyNoInteractions(teamMemberRepository);
    }

    // --- suggestions ---

    @Test
    void suggestionsListMatchingPeopleAndTeams() {
        givenVisibleProject();
        User damir = User.builder().id(DAMIR).firstName("Damir").lastName("Gabaidullin").email("damir@example.com").build();
        when(projectMemberRepository.searchMentionableUsers(PROJECT_ID, ADA, "%da%", PageRequest.of(0, 10)))
                .thenReturn(List.of(damir));
        when(teamRepository.searchWithMembersInProject(WORKSPACE_ID, PROJECT_ID, "%da%", PageRequest.of(0, 5)))
                .thenReturn(List.of(new TeamRow(BACKEND_TEAM, "Data team", 3)));

        MentionablesResponse response = service.mentionables(ada, PROJECT_ID, " Da ");

        assertEquals(List.of(new MentionablesResponse.UserItem(DAMIR, "Damir Gabaidullin", "damir@example.com")),
                response.users());
        assertEquals(List.of(new MentionablesResponse.TeamItem(BACKEND_TEAM, "Data team", 3)), response.teams());
    }

    @Test
    void aBlankSearchMatchesEverything() {
        givenVisibleProject();
        when(projectMemberRepository.searchMentionableUsers(any(), any(), any(), any())).thenReturn(List.of());
        when(teamRepository.searchWithMembersInProject(any(), any(), any(), any())).thenReturn(List.of());

        service.mentionables(ada, PROJECT_ID, null);

        verify(projectMemberRepository).searchMentionableUsers(PROJECT_ID, ADA, "%", PageRequest.of(0, 10));
        verify(teamRepository).searchWithMembersInProject(WORKSPACE_ID, PROJECT_ID, "%", PageRequest.of(0, 5));
    }

    @Test
    void suggestionsNeedViewWork() {
        when(access.require(PROJECT_ID, ada, ProjectPermission.VIEW_WORK))
                .thenThrow(new AccessDeniedException("Project permission required: VIEW_WORK"));

        assertThrows(AccessDeniedException.class, () -> service.mentionables(ada, PROJECT_ID, "x"));
        verifyNoInteractions(projectMemberRepository, teamRepository);
    }

    @Test
    void suggestionsForAProjectYouCannotSeeAre404() {
        when(access.require(PROJECT_ID, ada, ProjectPermission.VIEW_WORK))
                .thenThrow(new ResourceNotFoundException("Project not found."));

        assertThrows(ResourceNotFoundException.class, () -> service.mentionables(ada, PROJECT_ID, "x"));
    }

    // --- helpers ---

    private void givenVisibleProject() {
        when(access.require(PROJECT_ID, ada, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.VIEW_WORK)));
    }

    private RichTextDocument text(String... mentions) {
        return parser.parse(doc(String.join(",", mentions)));
    }

    private static String doc(String inline) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[" + inline + "]}]}";
    }

    private static String user(long id) {
        return "{\"type\":\"mention\",\"attrs\":{\"kind\":\"user\",\"id\":\"" + id + "\"}}";
    }

    private static String team(long id) {
        return "{\"type\":\"mention\",\"attrs\":{\"kind\":\"team\",\"id\":\"" + id + "\"}}";
    }

    private record TeamRow(Long id, String name, long membersInProject) implements TeamRepository.TeamInProject {
        @Override public Long getId() { return id; }
        @Override public String getName() { return name; }
        @Override public long getMembersInProject() { return membersInProject; }
    }
}