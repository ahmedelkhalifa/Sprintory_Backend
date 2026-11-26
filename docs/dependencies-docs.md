# Sprintory — Task dependencies API contract (R11, PMP-45)

**Status:** backend implementation passed full Maven/PostgreSQL verification (972 tests, 0 failures/errors/skipped; PMP-162, 24 Nov 2026). Review refactor verified: 974 tests passed, zero failures/errors/skipped. Frontend: PMP-163 (Alexander). This is the implementation contract, not a claim that the feature is deployed. V18 (retrospectives) and V19 (dependencies) are coordinated (see §8).
**Applies to:** `/api/v1/tasks/{id}/dependencies`, `/api/v1/dependencies/{id}`, task list/detail and board/backlog task responses, task status edits, history and notifications.
**Related:** [Tasks](./tasks-docs.md), [Board](./board-docs.md), [Sprints](./sprints-docs.md), [Roles](./roles-docs.md), [History](./task-history-docs.md), [Notifications](./notifications-docs.md), [API conventions](./api-conventions.md), R03/R04/R10c/R16, PMP-63 and PMP-48.

This file is the shared backend/frontend reference. Update code, documentation and tests together when the contract changes.

## 1. Overview and permissions

*As a project member, I want to define which tasks must finish before others can proceed, so that the team works in the right order and I can see what is blocked.*

- One directed link: A blocks B; equivalently B is blocked by A. It appears on both tasks.
- Scrum and Kanban. Only live top-level `STORY`, `TASK`, `BUG` in one project may be linked. Backlog/sprint membership is irrelevant. Subtasks have no independent dependencies.
- No self-links, duplicate directed pairs, or direct/indirect cycles. The graph includes retained links of deleted tasks; blocked calculations ignore deleted tasks. Restoration therefore cannot introduce a cycle. A cycle error can name a deleted task by key even though its links are hidden from the UI. Restore that task first to inspect and remove its dependency, or ask a project member with the necessary permissions to restore it. A previously known dependency ID remains removable through the authorized API.
- Blocked is computed from direct live blockers whose column category is not `DONE`. It is not a new workflow column or stored flag. No transitive blocked propagation: DONE satisfies a dependency even if that task has blockers itself.
- Soft blocking: a forward task movement may proceed after warning confirmation. No automatic dates, graph visualization, cross-project links, other dependency types or critical path.

| Action | Non-member (including workspace admin without project membership) | Viewer | Developer / Member | PM, PO, SM |
| --- | --- | --- | --- | --- |
| GET dependencies | 404 | allowed | allowed | allowed |
| Add/remove link | 404 | 403 | allowed | allowed |

`DEPENDENCY_MANAGE` is granted to every project role except Viewer, and appears in the project's `permissions` array. Use this permission, not role-name checks. The backend independently checks it. This story deliberately returns 404 even for a workspace admin who is not a project member (PMP-45 acceptance criterion 19), unlike other task endpoints that may return 403 for such an admin. Archived workspace and non-ACTIVE project: reads still allowed, writes return 409. Project lifecycle editing itself belongs to PMP-63.

## 2. Endpoints and bodies

All paths below are under `/api/v1`; use the normal authenticated access-token cookie. JSON is camelCase. IDs are numeric, not task keys.

| Method | Path | Request | Success |
| --- | --- | --- | --- |
| GET | `/tasks/{id}/dependencies` | none | 200 `TaskDependenciesResponse` |
| POST | `/tasks/{id}/dependencies` | `{ "blockerId": 10, "blockedId": 11 }` | 201 `DependencyResponse` |
| DELETE | `/dependencies/{id}` | none | 204 |

```jsonc
// DependencyTaskSummary
{ "id": 10, "key": "PMP-10", "title": "Build API", "statusName": "In Progress", "statusCategory": "IN_PROGRESS" }

// POST response (DependencyResponse): id = dependency id, not task id
{
  "id": 901,
  "blocker": { "id": 10, "key": "PMP-10", "title": "Build API", "statusName": "In Progress", "statusCategory": "IN_PROGRESS" },
  "blocked": { "id": 11, "key": "PMP-11", "title": "Connect UI", "statusName": "To Do", "statusCategory": "TODO" }
}

// GET /tasks/11/dependencies (TaskDependenciesResponse)
{
  "blocked": true,
  "blockedBy": [
    { "id": 901, "task": { "id": 10, "key": "PMP-10", "title": "Build API", "statusName": "In Progress", "statusCategory": "IN_PROGRESS" } }
  ],
  "blocks": [],
  "openBlockers": [
    { "id": 10, "key": "PMP-10", "title": "Build API", "statusName": "In Progress", "statusCategory": "IN_PROGRESS" }
  ],
  "canManage": true
}
```

`statusCategory` is `TODO | IN_PROGRESS | DONE`, independent of editable status names. Arrays are never null. `blockedBy`/`blocks` are sorted by key then id and include live DONE endpoints. Only `openBlockers` filters by non-DONE. `canManage` is true only for a top-level task, current member with the permission, active workspace and ACTIVE project. Use it to gate the controls even before PMP-63 exposes lifecycle state in the project response. On a visible subtask GET returns empty arrays and both booleans false. Missing/deleted path task or private project is 404.

## 3. Create/remove rules

- Add blocker on task 11: POST `/tasks/11/dependencies` with `{ "blockerId": 10, "blockedId": 11 }`. Add blocked task on task 10: POST `/tasks/10/dependencies` with the **same body**. Path id must be one of the endpoints.
- Both IDs must be positive and different. Anchor task is resolved first, then permissions checked, project row locked, state re-read, and endpoints verified in that project. Missing/deleted secondary endpoint, other project or subtask → 400 without disclosing foreign details.
- Duplicate → 409 `DEPENDENCY_ALREADY_EXISTS`. Proposed edge A→B where A is reachable from B → 409 `DEPENDENCY_CYCLE`; no writes on refusal.
- DELETE takes the dependency id from a list item. The same project lock serializes creation/removal. A known link ID can be removed even if an endpoint is soft-deleted (history preserved on both); hidden links are not displayed. Repeated DELETE returns 404.
- POST and DELETE require a writable project/workspace and record both histories in the same transaction. On success re-fetch the GET response and invalidate task list/detail queries.

## 4. Task list, board and backlog

All existing `TaskResponse` outputs gain additive fields:

```jsonc
{ "blocked": true, "openBlockers": [ { "id": 10, "key": "PMP-10", "title": "Build API", "statusName": "In Progress", "statusCategory": "IN_PROGRESS" } ] }
```

Empty = `blocked: false, openBlockers: []`. The rest of the existing task fields remain unchanged. New tasks/subtasks default to empty. Board and backlog load these same task-list DTOs:

- `GET /projects/{projectId}/tasks?blockedOnly=true` — applies before pagination, combines with existing filters and order; omitted/false = no dependency filter.
- Scrum board: existing `?order=board&sprintId={activeSprintId}`.
- Scrum backlog: existing `?order=backlog&noSprint=true` or sprint-scoped requests. Kanban uses board ordering.
- `GET /projects/{projectId}/tasks/{taskId}` — current derived state for task detail.
- The collection uses one batched blocker query per page, not one per card. The list filter is a database EXISTS predicate.
- A DONE task can itself still be blocked; status is based on its blockers, not its own category. A soft-deleted blocker does not count, restoration makes its retained links work again. Carry-over changes sprint membership, not dependency IDs.

## 5. Soft blocking and movement

Existing endpoints (do not create a replacement task-move endpoint):

- `PUT /projects/{projectId}/tasks/{taskId}/position`
- `PATCH /projects/{projectId}/tasks/{taskId}` when `statusId` changes.

An actual forward transition `TODO -> IN_PROGRESS`, `TODO -> DONE`, or `IN_PROGRESS -> DONE` with any open blockers returns `409 BLOCKED_TASK_CONFIRMATION_REQUIRED` unless `confirmBlocked: true` is sent. Reorders, same-category changes and backward transitions do not prompt. Administrative bulk column reconfiguration is not an individual task move; it still recomputes state and triggers unblocked notifications. Existing permissions and open-subtask completion confirmations are unchanged.

```jsonc
// First attempt (board drag)
{ "statusId": 14, "afterTaskId": null }
// Response: 409, standard error shape + openBlockers (§6)
// After the user clicks Move anyway, retry exactly the same request with:
{ "statusId": 14, "afterTaskId": null, "confirmBlocked": true }
```

If a separate `OPEN_SUBTASKS_CONFIRMATION_REQUIRED` comes back, preserve `confirmBlocked` and also add `confirmOpenSubtasks: true` after confirming that warning. Neither confirmation bypasses any other validation. Cancellation leaves the task unchanged; refresh optimistic UI. After success reload affected task/board/backlog data, because a completed blocker can change several other cards.

## 6. Errors

Standard error envelope: `timestamp`, `status`, `code`, `message`, `path`, optional `fieldErrors`. Two additive optional fields are `cyclePath` and `openBlockers` (omitted otherwise). Branch on `code`, not message.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | missing/invalid required fields |
| 400 | BAD_REQUEST | self-link, wrong path endpoint, non-live/foreign task, subtask |
| 401 | AUTHENTICATION_ERROR | not authenticated |
| 403 | ACCESS_DENIED | visible project but missing write permission |
| 404 | RESOURCE_NOT_FOUND | missing/private task or link; deleted path task |
| 409 | DEPENDENCY_ALREADY_EXISTS | This dependency already exists. |
| 409 | DEPENDENCY_CYCLE | full closed cycle path |
| 409 | BLOCKED_TASK_CONFIRMATION_REQUIRED | This task is blocked. Move it anyway? |
| 409 | WORKSPACE_ARCHIVED | archived workspace |
| 409 | CONFLICT | completed/archived project |

```jsonc
// Example error fields (standard timestamp/path also present)
{ "status": 409, "code": "DEPENDENCY_CYCLE", "message": "This would create a cycle: PMP-12 -> PMP-10 -> PMP-11 -> PMP-12.", "cyclePath": ["PMP-12", "PMP-10", "PMP-11", "PMP-12"] }

{ "status": 409, "code": "BLOCKED_TASK_CONFIRMATION_REQUIRED", "message": "This task is blocked. Move it anyway?", "openBlockers": [ { "id": 10, "key": "PMP-10", "title": "Build API", "statusName": "In Progress", "statusCategory": "IN_PROGRESS" } ] }
```

## 7. History and notifications

`DependencyService` calls `TaskStatusHistoryService.recordDependencyAdded` / `recordDependencyRemoved` directly inside the action transaction. Both append entries through the normal JPA history repository, with `field: null`, `refId: otherTaskId`, existing actor and timestamp fields, and direction as a display snapshot. Render the other task link using `refId` where it remains accessible.

| Type | On blocker (A) | On blocked task (B) |
| --- | --- | --- |
| DEPENDENCY_ADDED | `newValue: "Blocks PMP-11"` | `newValue: "Blocked by PMP-10"` |
| DEPENDENCY_REMOVED | `oldValue: "Blocks PMP-11"` | `oldValue: "Blocked by PMP-10"` |

Unused old/new value is null. No history or notification for rejected writes.

`TASK_UNBLOCKED` notification: message `"PMP-15 is no longer blocked."`. Target is the dependent task; normal task navigation (`kind: TASK` when opened). Category `STATUS_CHANGE` (in-app on, email off by default). Current project-member assignee only, respecting preferences; specifically includes the assignee even if they caused the transition (PMP-45 exception to ordinary actor exclusion). No assignee means no notification.

Notify on a real blocked→unblocked transition: last live non-DONE blocker reaches DONE, is soft-deleted, or last open link is removed. Column category edits and column deletion/reassignment also recompute transitions. A rename, partial completion, repeat move, or restoration that re-blocks a task does not notify. Project lock plus before/after state comparison within one transaction prevents duplicate notifications from concurrent completions. Existing task status event fires before the task status is saved, so use a post-write `TaskUnblocked` event rather than querying from that pre-update event. This also covers category edits/deletion. Notification persistence and outbox are transactional.

## 8. Data model and cycle prevention

Migration `V19__task_dependencies.sql` follows V18 (retrospectives) and V17 (Jira importer). Use a Flyway-managed database for integration/deployment; dev ddl-auto=update alone does not install these SQL constraints. V18 is now merged into main and must precede V19. Never edit an already applied migration. Renumber unmerged migrations only with agreement; ensure later migrations extending history/notification constraints preserve these new types.

`task_dependency(id, project_id, blocker_task_id, blocked_task_id, created_by, created_at)`:

- Unique directed pair and check different endpoint IDs.
- Composite foreign keys enforce same-project endpoints at the database level. Hard delete cascades; soft delete leaves the row. Creator FK `ON DELETE SET NULL`.
- Project and blocked-task indexes; unique pair also indexes the leading blocker ID.
- `DependencyGraph` is pure Java iterative BFS with visited/parent map. For candidate A→B search from B to A; if reached, reconstruct B→…→A and prepend A. O(V+E) for the project's graph, including retained links involving deleted tasks.
- Use existing `ProjectRepository.lockById` (`PESSIMISTIC_WRITE`) inside each add/remove transaction; acquire before duplicate/cycle check and retain until commit. Task status writes, deletes, restores and board edits use the same project lock. Task updates that do not request a status change do not acquire it. Never rely on unique pair alone to prevent a reverse-link race.
- Blocked is not persisted. Repository has native projections for current live links and open blockers, and JPA EXISTS for filtering.
- PMP-48 can reuse the same stored edge data and live summaries. A project-wide Gantt read model/endpoint, timeline and automatic scheduling are outside PMP-162.

## 9. Frontend implementation (PMP-163)

1. On top-level task detail load GET dependencies, show `Blocked by` and `Blocks` with key/title/status, clickable through existing task navigation. Display `blocked` badge only when true. Show add/remove only when `canManage` is true (also check the project permission if already loaded). Do not show dependency controls on subtasks.
2. Search/select candidates from `GET /projects/{projectId}/tasks?q=…` (same project, paginated, top-level only). Exclude current task and existing identical directional links; server still checks everything. Use correct POST orientation (§3); display 409 message and keep dialog open. DELETE uses link id.
3. Board and backlog: badge/icon with tooltip listing `openBlockers` key/title. Task list filter sends `blockedOnly=true` to server and resets page to 0.
4. On board drag/status change handle warning in §5; restore optimistic state on failure; retry only after confirmation. Keep all existing warnings. Refresh task/board/backlog after success.
5. Render new history types with their direction snapshots; render `TASK_UNBLOCKED` as ordinary task notification; use existing notification click routing. No new realtime transport; refresh/reload existing data as needed.

## 10. Acceptance and test coverage

| Criteria | Verification |
| --- | --- |
| 1–2, 10 | directed link, GET both sides, delete + two histories |
| 3–6 | self, duplicate, direct and indirect cycles, path; pure graph chain/diamond/long path |
| 7–9 | foreign project, subtask, deleted endpoint validation |
| 11–12, 16–17 | computed fields, multi-blocker progress, server filter, category semantics |
| 13 | last blocker and removal/delete notifications, no duplicate on repeat, actor=assignee |
| 14 | confirmation on board drag and status PATCH; retry works |
| 15 | deletion/restoration and retained DAG, hidden-link removal |
| 18–20 | viewer/member, private non-member, workspace admin, read-only lifecycle |
| 21 | committed fixtures, two concurrent reverse-link transactions, only one succeeds |

Backend tests in `dependency/DependencyGraphTest` and `dependency/DependencyIntegrationTest` plus existing task/board/project/notification regression suite. Frontend UI assertions belong to PMP-163 and are not claimed by backend tests. Sprint carry-over is also exercised by the integration suite; run the existing sprint regression suite. Full Maven and PostgreSQL tests must pass on the target development machine before marking implementation ready. No deployment performed.

## 11. Implementation status

The original backend implementation (six commits through `4b00672`) passed `mvn verify` locally: 972 tests, zero failures, errors or skipped tests, including 14 dependency tests. The reviewer independently confirmed the same result. The follow-up review changes reorganize packages/DTOs, use shared access checks, write task history directly through JPA, and restore conditional project locking for status edits. The HTTP contract is unchanged. The review refactor also passed `mvn verify`: 974 tests, zero failures, errors or skipped tests. V18/V19 ordering is settled. No deployment claimed here.

After merging main with retrospectives (V18), `mvnw -B clean verify` passed: 1053 tests, zero failures, errors or skipped tests; both V18 and V19 ran on PostgreSQL.
