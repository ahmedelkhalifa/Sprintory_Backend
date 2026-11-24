package team.sprintory_backend.dependency;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.exceptionHandler.*;
import team.sprintory_backend.project.dto.*;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.*;
import team.sprintory_backend.project.services.*;
import team.sprintory_backend.task.api.*;
import team.sprintory_backend.task.dto.*;
import team.sprintory_backend.task.models.TaskHistoryType;
import team.sprintory_backend.task.services.*;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.*;
import team.sprintory_backend.workspace.repositories.*;
import team.sprintory_backend.dependency.DependencyDtos.CreateRequest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL and independent transactions (including simultaneous reverse requests). */
class DependencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired private DependencyService deps;
    @Autowired private DependencyRepository links;
    @Autowired private TaskApiService tasks;
    @Autowired private SubtaskService subtasks;
    @Autowired private team.sprintory_backend.sprint.services.SprintService sprints;
    @Autowired private ProjectService projects;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMembers;
    @Autowired private ProjectBoardService boards;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private User pm, dev, viewer, outsider, admin;
    private Long workspaceId, projectId, foreignProjectId;
    private Long todo, progress, done;
    private String suffix;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(txManager);
        suffix = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            pm = user("pm"); dev = user("dev"); viewer = user("viewer"); outsider = user("outsider"); admin = user("admin");
            Workspace ws = workspaces.save(Workspace.builder().name("Dependency test").slug("dep-" + suffix).build());
            workspaceId = ws.getId();
            for (User user : List.of(pm,dev,viewer,outsider,admin)) {
                workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(user)
                    .role(user.equals(admin) ? Role.WORKSPACE_ADMIN : Role.WORKSPACE_MEMBER).build());
            }
            projectId = projects.create(pm,workspaceId,new CreateProjectRequest("Dependencies","DEP",ProjectTemplate.SCRUM,null)).id();
            foreignProjectId = projects.create(pm,workspaceId,new CreateProjectRequest("Other","OTHER",ProjectTemplate.KANBAN,null)).id();
            Project project = projectRepository.getReferenceById(projectId);
            projectMembers.save(ProjectMember.builder().project(project).user(dev)
                .roles(new HashSet<>(Set.of(ProjectRole.MEMBER))).build());
            projectMembers.save(ProjectMember.builder().project(project).user(viewer)
                .roles(new HashSet<>(Set.of(ProjectRole.VIEWER))).build());
            var columns = boards.get(pm,projectId).columns();
            todo = columns.stream().filter(c -> c.category()==BoardColumnCategory.TODO).findFirst().orElseThrow().id();
            progress = columns.stream().filter(c -> c.category()==BoardColumnCategory.IN_PROGRESS).findFirst().orElseThrow().id();
            done = columns.stream().filter(c -> c.category()==BoardColumnCategory.DONE).findFirst().orElseThrow().id();
        });
    }
    @AfterEach void cleanup() {
        if (workspaceId==null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from workspaces where id=?", workspaceId);
            for (User user : List.of(pm,dev,viewer,outsider,admin)) {
                jdbc.update("delete from email_outbox where recipient_email=?",user.getEmail());
                jdbc.update("delete from users where id=?",user.getId());
            }
        });
    }
    private User user(String name) {
        return users.save(User.builder().firstName(name).lastName("Dependency test")
            .email(name+"-"+suffix+"@example.invalid").enabled(true).build());
    }
    private TaskResponse create(String title) { return create(pm,projectId,title,null); }
    private TaskResponse create(User user, Long pid, String title, Long assignee) {
        return tasks.create(user,pid,new CreateTaskRequest(title,null,null,assignee,null,null,null));
    }
    private DependencyDtos.Edge add(TaskResponse a, TaskResponse b) {
        return deps.add(pm,b.id(),new CreateRequest(a.id(),b.id()));
    }
    private long notices(TaskResponse task) {
        return jdbc.queryForObject("select count(*) from notifications where task_id=? and type='TASK_UNBLOCKED'",Long.class,task.id());
    }
    private List<TaskHistoryEntryResponse> history(TaskResponse task) {
        return tasks.history(pm,projectId,task.id(),TaskHistoryOrder.OLDEST,PageRequest.of(0,100)).content();
    }
    private void assertConflict(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code,assertThrows(DependencyConflict.class,action).code());
    }

    @Test void bothDirectionsDuplicatesCyclesAndHistory() {
        var a=create("A"); var b=create("B"); var c=create("C");
        var ab=add(a,b);
        deps.add(dev,b.id(),new CreateRequest(b.id(),c.id())); // Add blocked task from its blocker.
        assertEquals(ab.id(),deps.get(viewer,a.id()).blocks().get(0).id());
        assertFalse(deps.get(viewer,a.id()).canManage());
        assertTrue(deps.get(dev,a.id()).canManage());
        assertEquals(a.id(),deps.get(viewer,b.id()).blockedBy().get(0).task().id());
        assertThrows(BadRequestException.class,()->deps.add(pm,a.id(),new CreateRequest(a.id(),a.id())));
        assertThrows(BadRequestException.class,()->deps.add(pm,c.id(),new CreateRequest(a.id(),b.id())));
        assertConflict(ErrorCode.DEPENDENCY_ALREADY_EXISTS,()->add(a,b));
        assertConflict(ErrorCode.DEPENDENCY_CYCLE,()->add(b,a));
        var cycle=assertThrows(DependencyConflict.class,()->add(c,a));
        assertEquals(List.of(c.key(),a.key(),b.key(),c.key()),cycle.cyclePath());
        assertTrue(cycle.getMessage().contains(String.join(" -> ",cycle.cyclePath())));
        assertEquals(2,links.findByProjectIdOrderByIdAsc(projectId).size());
        var outgoing=history(a).stream().filter(h->h.type()==TaskHistoryType.DEPENDENCY_ADDED).toList();
        assertEquals(1,outgoing.size()); assertEquals("Blocks "+b.key(),outgoing.get(0).newValue());
        deps.remove(dev,ab.id());
        assertTrue(deps.get(pm,a.id()).blocks().isEmpty());
        assertTrue(deps.get(pm,b.id()).blockedBy().isEmpty());
        assertTrue(history(a).stream().anyMatch(h->h.type()==TaskHistoryType.DEPENDENCY_REMOVED && h.oldValue().equals("Blocks "+b.key())));
        assertTrue(history(b).stream().anyMatch(h->h.type()==TaskHistoryType.DEPENDENCY_REMOVED && h.oldValue().equals("Blocked by "+a.key())));
        assertThrows(ResourceNotFoundException.class,()->deps.remove(pm,ab.id()));
    }

    @Test void accessInvalidEndpointsAndReadOnlyState() {
        var a=create("A"); var b=create("B"); var c=create("C");
        var ab=add(a,b);
        assertThrows(AccessDeniedException.class,()->deps.add(viewer,b.id(),new CreateRequest(c.id(),b.id())));
        assertThrows(AccessDeniedException.class,()->deps.remove(viewer,ab.id()));
        for(User stranger:List.of(outsider,admin)) {
            assertThrows(ResourceNotFoundException.class,()->deps.get(stranger,a.id()));
            assertThrows(ResourceNotFoundException.class,()->deps.add(stranger,a.id(),new CreateRequest(a.id(),c.id())));
            assertThrows(ResourceNotFoundException.class,()->deps.remove(stranger,ab.id()));
        }
        var other=create(pm,foreignProjectId,"Foreign",null);
        assertThrows(BadRequestException.class,()->deps.add(pm,a.id(),new CreateRequest(a.id(),other.id())));
        var child=subtasks.create(pm,projectId,a.id(),new CreateSubtaskRequest("Child",null,null,null));
        assertFalse(deps.get(pm,child.id()).blocked());
        assertFalse(deps.get(pm,child.id()).canManage());
        assertThrows(BadRequestException.class,()->deps.add(pm,a.id(),new CreateRequest(a.id(),child.id())));
        tasks.delete(pm,projectId,c.id());
        assertThrows(BadRequestException.class,()->deps.add(pm,a.id(),new CreateRequest(a.id(),c.id())));
        for (String state:List.of("COMPLETED","ARCHIVED")) {
            jdbc.update("update projects set lifecycle_status=? where id=?",state,projectId);
            assertTrue(deps.get(viewer,b.id()).blocked());
            assertFalse(deps.get(pm,b.id()).canManage());
            assertConflict(ErrorCode.CONFLICT,()->deps.remove(pm,ab.id()));
        }
        jdbc.update("update projects set lifecycle_status='ACTIVE' where id=?",projectId);
        jdbc.update("update workspaces set archived_at=now() where id=?",workspaceId);
        assertTrue(deps.get(viewer,b.id()).blocked());
        assertFalse(deps.get(pm,b.id()).canManage());
        assertThrows(team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException.class,()->deps.remove(pm,ab.id()));
    }

    @Test void blockedFieldsFilterConfirmationAndLastBlockerNotification() {
        var a=create("First blocker"); var b=create("Second blocker");
        var target=create(dev,projectId,"Dependent",dev.getId());
        add(a,target); add(b,target);
        var blocked=tasks.get(pm,projectId,target.id());
        assertTrue(blocked.blocked()); assertEquals(2,blocked.openBlockers().size());
        var filtered=tasks.list(viewer,projectId,new TaskSearchFilters(null,null,null,null,null,null,null,null,true),
                TaskListOrder.BACKLOG,PageRequest.of(0,1));
        assertEquals(1,filtered.totalElements()); assertEquals(target.id(),filtered.content().get(0).id());
        var refused=assertThrows(DependencyConflict.class,()->tasks.moveOnBoard(dev,projectId,target.id(),new MoveTaskRequest(progress,null)));
        assertEquals(ErrorCode.BLOCKED_TASK_CONFIRMATION_REQUIRED,refused.code()); assertEquals(2,refused.openBlockers().size());
        assertEquals(todo,tasks.get(pm,projectId,target.id()).statusId());
        assertTrue(tasks.moveOnBoard(dev,projectId,target.id(),new MoveTaskRequest(progress,null,false,true)).blocked());
        assertConflict(ErrorCode.BLOCKED_TASK_CONFIRMATION_REQUIRED,()->tasks.update(dev,projectId,target.id(),
                new UpdateTaskRequest(null,null,null,done,null,null,null,null,false,false,false,false,false)));
        tasks.moveOnBoard(pm,projectId,a.id(),new MoveTaskRequest(done,null));
        assertEquals(List.of(b.id()),tasks.get(pm,projectId,target.id()).openBlockers().stream().map(DependencyDtos.TaskSummary::id).toList());
        assertEquals(0,notices(target));
        boards.update(pm,projectId,done,new UpdateBoardColumnRequest("Shipped",null,null,null));
        assertTrue(tasks.get(pm,projectId,target.id()).blocked());
        tasks.moveOnBoard(dev,projectId,b.id(),new MoveTaskRequest(done,null)); // assignee is also actor
        assertFalse(tasks.get(pm,projectId,target.id()).blocked());
        assertEquals(1,notices(target));
        assertEquals(target.key()+" is no longer blocked.",jdbc.queryForObject("select message from notifications where task_id=? and type='TASK_UNBLOCKED'",String.class,target.id()));
        tasks.moveOnBoard(dev,projectId,b.id(),new MoveTaskRequest(done,null));
        assertEquals(1,notices(target));
    }

    @Test void deletionRestorationAndRetainedGraphCannotCreateLatentCycle() {
        var a=create("A"); var b=create("B"); var c=create(pm,projectId,"C",pm.getId());
        var ab=add(a,b); add(b,c);
        tasks.delete(pm,projectId,b.id());
        assertFalse(deps.get(pm,c.id()).blocked()); assertEquals(1,notices(c));
        assertConflict(ErrorCode.DEPENDENCY_CYCLE,()->add(c,a));
        tasks.restore(pm,projectId,b.id());
        assertTrue(deps.get(pm,c.id()).blocked()); assertTrue(deps.get(pm,b.id()).blocked());
        tasks.delete(pm,projectId,a.id());
        deps.remove(pm,ab.id()); // Known link ID is removable even while its blocker is hidden.
        tasks.restore(pm,projectId,a.id());
        assertFalse(deps.get(pm,b.id()).blocked());
        assertTrue(history(a).stream().anyMatch(h->h.type()==TaskHistoryType.DEPENDENCY_REMOVED));
        var bc=deps.get(pm,c.id()).blockedBy().get(0);
        deps.remove(pm,bc.id());
        assertFalse(deps.get(pm,c.id()).blocked()); assertEquals(2,notices(c));
    }

    @Test void changingColumnCategoryAndDeletingColumnRecomputeBlockedState() {
        var a=create("Blocker"); var b=create(pm,projectId,"Dependent",pm.getId()); add(a,b);
        tasks.moveOnBoard(pm,projectId,a.id(),new MoveTaskRequest(progress,null));
        boards.update(pm,projectId,progress,new UpdateBoardColumnRequest(null,BoardColumnCategory.DONE,null,null));
        assertFalse(tasks.get(pm,projectId,b.id()).blocked()); assertEquals(1,notices(b));
        boards.update(pm,projectId,progress,new UpdateBoardColumnRequest(null,BoardColumnCategory.IN_PROGRESS,null,null));
        assertTrue(tasks.get(pm,projectId,b.id()).blocked());
        boards.delete(pm,projectId,progress,new DeleteBoardColumnRequest(done));
        assertFalse(tasks.get(pm,projectId,b.id()).blocked()); assertEquals(2,notices(b));
    }

    @Test void linksSurviveSprintCarryOver() {
        tx.executeWithoutResult(status -> projectMembers.findWithRolesByProjectIdAndUserId(projectId,dev.getId())
                .orElseThrow().getRoles().add(ProjectRole.SCRUM_MASTER));
        var a=create("Blocker in backlog"); var b=create("Dependent in sprint"); var edge=add(a,b);
        var sprint=sprints.create(dev,projectId,new team.sprintory_backend.sprint.dto.SprintRequest(
                "Dependency sprint","Test carry over",null,null,null));
        sprints.move(dev,projectId,new team.sprintory_backend.sprint.dto.MoveSprintTaskRequest(b.id(),sprint.id(),null));
        sprints.start(dev,sprint.id(),null);
        assertEquals(sprint.id(),tasks.get(pm,projectId,b.id()).sprintId());
        assertTrue(tasks.get(pm,projectId,b.id()).blocked());
        sprints.complete(dev,sprint.id(),new team.sprintory_backend.sprint.dto.CompleteSprintRequest(null,false,
                team.sprintory_backend.sprint.models.SprintGoalAchieved.NO,null,true));
        assertNull(tasks.get(pm,projectId,b.id()).sprintId());
        assertEquals(edge.id(),deps.get(pm,b.id()).blockedBy().get(0).id());
        assertTrue(tasks.get(pm,projectId,b.id()).blocked());
    }

    @Test void concurrentLastBlockerCompletionsNotifyOnce() throws Exception {
        var a=create("A"); var b=create("B"); var c=create(dev,projectId,"C",dev.getId()); add(a,c); add(b,c);
        var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var x=pool.submit(()->finishBlocker(pm,a,ready,go));
            var y=pool.submit(()->finishBlocker(dev,b,ready,go));
            assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
            x.get(20,TimeUnit.SECONDS); y.get(20,TimeUnit.SECONDS);
            assertFalse(deps.get(pm,c.id()).blocked()); assertEquals(1,notices(c));
        } finally { go.countDown(); pool.shutdownNow(); pool.awaitTermination(20,TimeUnit.SECONDS); }
    }
    private TaskResponse finishBlocker(User actor,TaskResponse blocker,CountDownLatch ready,CountDownLatch go) throws Exception {
        ready.countDown(); if(!go.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
        return tasks.moveOnBoard(actor,projectId,blocker.id(),new MoveTaskRequest(done,null));
    }

    @Test void concurrentReverseLinksOnlyOneCommits() throws Exception {
        var a=create("A"); var b=create("B");
        var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var x=pool.submit(()->attemptReverse(pm,a,b,ready,go));
            var y=pool.submit(()->attemptReverse(dev,b,a,ready,go));
            assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
            assertNotEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));
            assertEquals(1,links.findByProjectIdOrderByIdAsc(projectId).size());
        } finally { go.countDown(); pool.shutdownNow(); pool.awaitTermination(20,TimeUnit.SECONDS); }
    }
    private boolean attemptReverse(User actor,TaskResponse a,TaskResponse b,CountDownLatch ready,CountDownLatch go) throws Exception {
        ready.countDown(); if(!go.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
        try { deps.add(actor,a.id(),new CreateRequest(a.id(),b.id())); return true; }
        catch(DependencyConflict e) { assertEquals(ErrorCode.DEPENDENCY_CYCLE,e.code()); return false; }
    }
}
