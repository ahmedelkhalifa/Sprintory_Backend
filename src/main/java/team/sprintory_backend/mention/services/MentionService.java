package team.sprintory_backend.mention.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.mention.dto.MentionablesResponse;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.User;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @mentions inside a project (PMP-136, docs/mentions-docs.md §3–§4): who can be mentioned, and
 * who a saved text newly mentions. Only members of the project can be mentioned, people directly
 * or through a team of the project's workspace.
 */
@Service
@RequiredArgsConstructor
public class MentionService {

    static final int MAX_USERS = 10;
    static final int MAX_TEAMS = 5;

    static final String NOT_A_MEMBER = "Only members of this project can be mentioned.";
    static final String TEAM_NOT_IN_PROJECT = "This team has no members in this project.";

    private final ProjectAccess projectAccess;
    private final ProjectMemberRepository projectMemberRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * The people a saved text newly mentions, teams expanded to their members who are in the
     * project now. Only mentions that are not in {@code previous} count (null when the text is
     * new), so editing never notifies anyone twice and an old mention of someone who left is kept.
     * The author is not removed here: the notification listener drops the actor.
     *
     * <p>Call it inside the action's transaction, before saving the text.
     *
     * @throws InvalidMentionException if a new mention is a non-member, or a team with no member in the project
     */
    @Transactional(readOnly = true)
    public Set<Long> newlyMentionedUserIds(Project project, RichTextDocument text, RichTextDocument previous) {
        Set<RichTextMention> added = new LinkedHashSet<>(text.mentions());
        if (previous != null) {
            added.removeAll(previous.mentions());
        }
        if (added.isEmpty()) {
            return Set.of();
        }

        Set<Long> members = new HashSet<>(projectMemberRepository.findUserIdsByProjectId(project.getId()));
        Set<Long> recipients = new LinkedHashSet<>();
        for (RichTextMention mention : added) {
            if (mention.kind() == RichTextMention.Kind.USER) {
                if (!members.contains(mention.id())) {
                    throw new InvalidMentionException(NOT_A_MEMBER);
                }
                recipients.add(mention.id());
            } else {
                recipients.addAll(teamMembersInProject(project, mention.id(), members));
            }
        }
        return recipients;
    }

    /** The @ suggestions for the caller: people and teams of this project whose name matches {@code q}. */
    @Transactional(readOnly = true)
    public MentionablesResponse mentionables(User user, Long projectId, String q) {
        Project project = projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK).project();
        String pattern = Pageables.containsPattern(q);

        List<MentionablesResponse.UserItem> users = projectMemberRepository
                .searchMentionableUsers(projectId, user.getId(), pattern, PageRequest.of(0, MAX_USERS)).stream()
                .map(u -> new MentionablesResponse.UserItem(u.getId(), AuditTarget.displayName(u), u.getEmail()))
                .toList();
        List<MentionablesResponse.TeamItem> teams = teamRepository
                .searchWithMembersInProject(project.getWorkspace().getId(), projectId, pattern,
                        PageRequest.of(0, MAX_TEAMS)).stream()
                .map(t -> new MentionablesResponse.TeamItem(t.getId(), t.getName(), t.getMembersInProject()))
                .toList();
        return new MentionablesResponse(users, teams);
    }

    /**
     * The team's members who are in the project. A team of another workspace, a missing team and
     * a team with nobody in the project are refused alike, so nothing is revealed about them.
     */
    private List<Long> teamMembersInProject(Project project, long teamId, Set<Long> members) {
        List<Long> inProject = teamMemberRepository
                .findUserIdsByTeamIdAndWorkspaceId(teamId, project.getWorkspace().getId()).stream()
                .filter(members::contains)
                .toList();
        if (inProject.isEmpty()) {
            throw new InvalidMentionException(TEAM_NOT_IN_PROJECT);
        }
        return inProject;
    }
}