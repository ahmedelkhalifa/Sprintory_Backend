package team.sprintory_backend.project.charter.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.charter.dto.*;
import team.sprintory_backend.project.charter.exceptions.CharterReadOnlyException;
import team.sprintory_backend.project.charter.models.*;
import team.sprintory_backend.project.charter.repositories.*;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.services.WorkspaceAccess;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** PMP-116: project charter. All mutating operations lock the project to serialize goal ordering. */
@Service
@RequiredArgsConstructor
public class ProjectCharterService {
    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final ProjectCharterRepository charters;
    private final CharterGoalRepository goals;
    private final CharterStakeholderRepository stakeholders;
    private final CharterHistoryRepository histories;
    private final WorkspaceMemberRepository workspaceMembers;
    private final UserRepository users;
    private final RichTextParser richText;
    private final ObjectMapper json;

    /** Workspace admins outside a project cannot read its stakeholder contact details (R04). */
    private ProjectAccessResult readAccess(User caller, Long projectId) {
        ProjectAccessResult checked = access.requireVisible(projectId, caller);
        if (checked.projectMember() == null) {
            throw new ResourceNotFoundException("Project charter not found.");
        }
        return checked;
    }

    private Project writeAccess(User caller, Long projectId) {
        ProjectAccessResult checked = readAccess(caller, projectId);
        if (!checked.can(ProjectPermission.EDIT_CHARTER)) {
            throw new AccessDeniedException("Project permission required: EDIT_CHARTER");
        }
        WorkspaceAccess.requireNotArchived(checked.workspaceMember());
        // A common project-row lock prevents competing edits/reorders racing or getting partial history.
        Project locked = projects.lockById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found."));
        if (locked.getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw new CharterReadOnlyException();
        }
        return locked;
    }

    private static boolean editable(ProjectAccessResult checked) {
        return checked.can(ProjectPermission.EDIT_CHARTER)
                && !checked.project().getWorkspace().isArchived()
                && checked.project().getLifecycleStatus() == ProjectLifecycleState.ACTIVE;
    }

    @Transactional(readOnly = true)
    public CharterResponse get(User caller, Long projectId) {
        ProjectAccessResult checked = readAccess(caller, projectId);
        ProjectCharter charter = charters.findById(projectId).orElse(null);
        boolean canEdit = editable(checked);
        return new CharterResponse(projectId, canEdit, !canEdit,
                charter == null ? null : charter.getInScope(),
                charter == null ? null : charter.getOutOfScope(),
                goals.findByProjectIdOrderBySortOrderAscIdAsc(projectId)
                        .stream().map(CharterGoalResponse::from).toList(),
                stakeholders.findByProjectIdOrderByIdAsc(projectId)
                        .stream().map(CharterStakeholderResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<CharterHistoryResponse> history(User caller, Long projectId, Pageable pageable) {
        readAccess(caller, projectId);
        return PageResponse.from(histories.findByProjectIdOrderByOccurredAtDescIdDesc(projectId, pageable)
                .map(CharterHistoryResponse::from));
    }

    @Transactional
    public CharterGoalResponse addGoal(User caller, Long projectId, CharterGoalRequest request) {
        Project project = writeAccess(caller, projectId);
        List<CharterGoal> existing = goals.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        CharterGoal saved = goals.save(CharterGoal.builder()
                .project(project).title(request.title().trim())
                .successMeasure(blankToNull(request.successMeasure()))
                .sortOrder(existing.size()).build());
        change(project, caller, CharterChangeType.GOAL_ADDED, null, snapshot(saved));
        return CharterGoalResponse.from(saved);
    }

    @Transactional
    public CharterGoalResponse updateGoal(User caller, Long projectId, Long goalId, CharterGoalRequest request) {
        Project project = writeAccess(caller, projectId);
        CharterGoal goal = goal(goalId, projectId);
        String before = snapshot(goal);
        goal.setTitle(request.title().trim());
        goal.setSuccessMeasure(blankToNull(request.successMeasure()));
        if (!before.equals(snapshot(goal))) {
            change(project, caller, CharterChangeType.GOAL_UPDATED, before, snapshot(goal));
        }
        return CharterGoalResponse.from(goal);
    }

    @Transactional
    public void removeGoal(User caller, Long projectId, Long goalId) {
        Project project = writeAccess(caller, projectId);
        CharterGoal removed = goal(goalId, projectId);
        change(project, caller, CharterChangeType.GOAL_DELETED, snapshot(removed), null);
        goals.delete(removed);
        // No gaps: the next append starts exactly after the last goal.
        List<CharterGoal> remaining = goals.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        remaining.removeIf(g -> g.getId().equals(goalId));
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setSortOrder(i);
        }
    }

    @Transactional
    public List<CharterGoalResponse> reorderGoals(User caller, Long projectId, CharterGoalOrderRequest request) {
        Project project = writeAccess(caller, projectId);
        List<CharterGoal> existing = goals.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        List<Long> oldOrder = existing.stream().map(CharterGoal::getId).toList();
        List<Long> requested = request.goalIds();
        if (requested.size() != oldOrder.size() || new HashSet<>(requested).size() != requested.size()
                || !new HashSet<>(oldOrder).equals(new HashSet<>(requested))) {
            throw new BadRequestException("Provide each goal in this project exactly once.");
        }
        if (oldOrder.equals(requested)) {
            return existing.stream().map(CharterGoalResponse::from).toList();
        }
        java.util.Map<Long, CharterGoal> byId = new java.util.HashMap<>();
        existing.forEach(goal -> byId.put(goal.getId(), goal));
        List<CharterGoalResponse> result = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            CharterGoal goal = byId.get(requested.get(i));
            goal.setSortOrder(i);
            result.add(CharterGoalResponse.from(goal));
        }
        change(project, caller, CharterChangeType.GOALS_REORDERED, json.writeValueAsString(oldOrder),
                json.writeValueAsString(requested));
        return result;
    }

    @Transactional
    public CharterResponse replaceScope(User caller, Long projectId, CharterScopeRequest request) {
        Project project = writeAccess(caller, projectId);
        ProjectCharter charter = charters.findById(projectId).orElseGet(() -> new ProjectCharter(project));
        String incomingIn = normalizeScope(request.inScope());
        String incomingOut = normalizeScope(request.outOfScope());
        if (!Objects.equals(charter.getInScope(), incomingIn)) {
            change(project, caller, CharterChangeType.IN_SCOPE_CHANGED, charter.getInScope(), incomingIn);
            charter.setInScope(incomingIn);
        }
        if (!Objects.equals(charter.getOutOfScope(), incomingOut)) {
            change(project, caller, CharterChangeType.OUT_OF_SCOPE_CHANGED, charter.getOutOfScope(), incomingOut);
            charter.setOutOfScope(incomingOut);
        }
        charters.save(charter);
        return get(caller, projectId);
    }

    private String normalizeScope(String value) {
        if (value == null || value.isBlank()) return null;
        RichTextDocument doc = richText.parse(value);
        if (doc.isEmpty()) return null;
        // Scope is a document, not a messaging surface. Mentions are not notification events here.
        return doc.json();
    }

    @Transactional
    public CharterStakeholderResponse addStakeholder(User caller, Long projectId,
                                                      CharterStakeholderRequest request) {
        Project project = writeAccess(caller, projectId);
        CharterStakeholder stakeholder = new CharterStakeholder();
        stakeholder.setProject(project);
        applyStakeholder(stakeholder, request, project);
        return CharterStakeholderResponse.from(stakeholders.save(stakeholder));
    }

    @Transactional
    public CharterStakeholderResponse updateStakeholder(User caller, Long projectId, Long stakeholderId,
                                                         CharterStakeholderRequest request) {
        Project project = writeAccess(caller, projectId);
        CharterStakeholder stakeholder = stakeholder(stakeholderId, projectId);
        applyStakeholder(stakeholder, request, project);
        return CharterStakeholderResponse.from(stakeholder);
    }

    @Transactional
    public void removeStakeholder(User caller, Long projectId, Long stakeholderId) {
        writeAccess(caller, projectId);
        stakeholders.delete(stakeholder(stakeholderId, projectId));
    }

    private void applyStakeholder(CharterStakeholder entity, CharterStakeholderRequest request, Project project) {
        entity.setName(request.name().trim());
        entity.setOrganization(blankToNull(request.organization()));
        entity.setProjectRole(blankToNull(request.projectRole()));
        entity.setInterest(request.interest());
        entity.setInfluence(request.influence());
        entity.setContactEmail(blankToNull(request.contactEmail()));
        if (request.linkedUserId() == null) {
            entity.setLinkedUser(null);
        } else {
            Long id = request.linkedUserId();
            if (!workspaceMembers.existsByWorkspaceIdAndUserId(project.getWorkspace().getId(), id)) {
                throw new BadRequestException("Linked user must belong to this workspace.");
            }
            entity.setLinkedUser(users.findById(id)
                    .orElseThrow(() -> new BadRequestException("Linked user not found.")));
        }
    }

    private CharterGoal goal(Long id, Long projectId) {
        return goals.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Charter goal not found."));
    }

    private CharterStakeholder stakeholder(Long id, Long projectId) {
        return stakeholders.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Stakeholder not found."));
    }

    private String snapshot(CharterGoal goal) {
        return json.writeValueAsString(CharterGoalResponse.from(goal));
    }

    private void change(Project project, User actor, CharterChangeType type, String before, String after) {
        histories.save(CharterHistoryEntry.builder()
                .project(project)
                .actorId(actor.getId())
                .actorName((actor.getFirstName() + " " + actor.getLastName()).trim())
                .occurredAt(Instant.now())
                .changeType(type)
                .oldValue(before)
                .newValue(after)
                .build());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
