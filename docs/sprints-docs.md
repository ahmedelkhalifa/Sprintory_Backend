# Sprintory — Sprints API contract (R14)

**Status:** PMP-119 backend implemented; backend test suite must be run on the feature branch before merge. Sprint closure follow-up remains in PMP-122 (see §7).  
**Applies to:** `/api/v1/projects/{projectId}/sprints`, `/api/v1/sprints/{sprintId}`, task-list sprint filtering, and Scrum project settings.  
**Related:** [Tasks](./tasks-docs.md), [Task history](./task-history-docs.md), [Projects](./projects-docs.md), [Board](./board-docs.md), [Notifications](./notifications-docs.md), [Roles](./roles-docs.md), [API conventions](./api-conventions.md).

## 1. Rules and access

- Sprints exist only in **Scrum** projects. They have a numbered, editable name; optional capacity; goal; start/end calendar dates; state `PLANNED`, `ACTIVE`, or `COMPLETED`.
- `MANAGE_SPRINTS` (Scrum Master permission) is required to create/update/delete/start/complete sprints, move tasks, and change per-member availability. Members with `VIEW_WORK` (including Viewers) can read sprint details.
- A nonmember cannot discover a project: `404`. An authenticated project member lacking permission gets `403`; archived workspaces get `409 WORKSPACE_ARCHIVED` for mutations.
- A sprint is read-only after completion; completed/archived projects are also read-only.
- Several sprints may be `PLANNED`, but at most one may be `ACTIVE`; database unique index enforces this even under concurrent writes.
- All sprint writes first acquire the **project row lock**, *then* load the mutable sprint. This serializes competing starts, completions, deletes, PATCHes and task moves.
- Dates are inclusive, `endDate > startDate`, maximum 90 calendar days. On create/PATCH/start, dates supplied in a request must be **both present or both absent**.
- Sprints may not overlap. For a completed sprint, the occupied interval ends on its **actual completion date** if it closed before its planned end. Thus early completion does not block planning the next sprint.

## 2. Sprint endpoints

All endpoints require an access-token cookie. Bodies and responses are JSON; fields use `camelCase`.

| Method | Path | Required permission | Success | Request / behavior |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/projects/{projectId}/sprints` | `VIEW_WORK` | `200 SprintResponse[]` | Lists `PLANNED` + `ACTIVE` by number; use detail for completed. |
| `POST` | `/api/v1/projects/{projectId}/sprints` | `MANAGE_SPRINTS` | `201 SprintResponse` | `SprintRequest`: `name?`, `goal?`, `capacity?`, `startDate?`, `endDate?`. |
| `GET` | `/api/v1/sprints/{sprintId}` | `VIEW_WORK` | `200 SprintResponse` | Includes `COMPLETED`. |
| `PATCH` | `/api/v1/sprints/{sprintId}` | `MANAGE_SPRINTS` | `200 SprintResponse` | Partial `SprintRequest`; both dates if changing dates; `clearCapacity:true` clears capacity. |
| `DELETE` | `/api/v1/sprints/{sprintId}` | `MANAGE_SPRINTS` | `204` | `PLANNED` only; unlinks *all* tasks, including soft-deleted tasks, preserving `backlogRank`. |
| `POST` | `/api/v1/sprints/{sprintId}/start` | `MANAGE_SPRINTS` | `200 SprintResponse` | `StartSprintRequest`: optional `goal`, `startDate`, `endDate`; requires a nonblank goal, valid dates, ≥1 live top-level task, no other ACTIVE sprint. |
| `POST` | `/api/v1/projects/{projectId}/sprints/move` | `MANAGE_SPRINTS` | `204` | `MoveSprintTaskRequest`: `{taskId, sprintId, afterTaskId}`; `sprintId:null` removes from sprint; `afterTaskId:null` means first. |
| `POST` | `/api/v1/sprints/{sprintId}/complete` | `MANAGE_SPRINTS` | `200 CompleteSprintResponse` | `{carryOverSprintId?,createSprint?}`; only ACTIVE; choose at most one destination. |

`SprintRequest` names are ≤100 characters; goal ≤5000; capacity is null or a nonnegative integer. Absent name on create defaults to `KEY Sprint N`. Absent dates default to the next available period using the project's `sprintLengthDays` (initially 14). Sprint lengths cannot be one day because `endDate > startDate`.

`SprintResponse` fields:

| Field | Meaning |
| --- | --- |
| `id`, `projectId`, `name`, `goal` | Identity and editable sprint metadata. |
| `startDate`, `endDate` | **Planned** sprint dates; closing early does not rewrite the original end date. |
| `status`, `completedAt` | `PLANNED`, `ACTIVE`, `COMPLETED`; completion timestamp or null. |
| `capacity` | Team-selected point budget; optional, soft limit. |
| `taskIds` | Ordered live **top-level** tasks; subtasks follow their parents. |
| `selectedPoints` | Sum of estimates on currently selected top-level tasks. |
| `unestimatedTaskIds` | Selected top-level tasks with `estimate:null`. |
| `overCapacity` | `true` if `capacity` exists and selectedPoints exceeds it. **Never prevents saving.** |
| `committedPoints` | Point snapshot taken when sprint starts, unchanged afterward. |
| `completedPoints` | Sum of DONE-category top-level task estimates at completion. |

`CompleteSprintResponse` is `{sprint, carriedOver, toSprint}`. DONE items remain in the completed sprint. Unfinished items go to a nominated `PLANNED` sprint, a newly created `PLANNED` sprint, or back to their original backlog rank. Closing publishes `SprintCompleted`; starting publishes `SprintStarted` for notifications. Committed item snapshots survive later estimate changes.

## 3. Task list, backlog, board, capacity

**Task filtering is performed by the database before pagination**, not by filtering a fetched task page. Add to `GET /api/v1/projects/{projectId}/tasks`:

| Query parameter | Meaning |
| --- | --- |
| `sprintId=42` | Only top-level tasks assigned to sprint 42. |
| `noSprint=true` | Only top-level tasks with `sprint_id IS NULL`, i.e. the Scrum product backlog. |
| Neither | All live top-level project tasks, regardless of sprint. |
| Both `sprintId` and `noSprint=true` | `400 BAD_REQUEST` (mutually exclusive). |

These filters can be combined with existing `q`, `type`, `priority`, `statusId`, `assigneeId`, `label`, `order`, `page`, `size`; pagination totals match the filtered query. Board metadata `GET /projects/{projectId}/board` includes `activeSprintId` for Scrum, or `null` for Kanban. To populate a Scrum board, fetch tasks using `sprintId=activeSprintId`. A task is assigned to exactly one sprint via its parent; subtasks inherit the parent's `sprintId` in `TaskResponse` but cannot be moved separately.

**Capacity suggestion:**

| Method | Path | Required permission | Success |
| --- | --- | --- | --- |
| `GET` | `/api/v1/sprints/{sprintId}/capacity-suggestion` | `VIEW_WORK` | `200 CapacitySuggestionResponse` |
| `PUT` | `/api/v1/sprints/{sprintId}/availability/{userId}` | `MANAGE_SPRINTS` | `200 CapacitySuggestionResponse`; body `{availableDays:5}` |
| `GET` | `/api/v1/sprints/{sprintId}/commitment` | `VIEW_WORK` | `200 SprintCommitmentResponse[]` |
| `GET` | `/api/v1/sprints/{sprintId}/scope-history?page=&size=` | `VIEW_WORK` | `200 PageResponse<SprintScopeChangeResponse>` |

`CapacitySuggestionResponse` fields: `suggestedCapacity` (integer or null), `averageVelocity` (number or null), `normalPersonDays`, `availablePersonDays`, `workingDays`, and `members:[{userId,availableDays}]`.

- Velocity: average DONE story points for the last **up to three completed** sprints. None completed → no suggestion (`null`).
- Available days: Monday–Friday working days inclusive; no holidays calendar yet. A member defaults to all workdays until overridden by a value from 0 to workingDays.
- **Only project members who have at least one role other than `VIEWER` count** toward normal/available person-days and appear in the suggestion `members` list. Viewer-only members are excluded even if they belong to the project. `PUT /availability/{viewerId}` returns **400**.
- Formula: `round(averageVelocity × availablePersonDays / normalPersonDays)`. Example: `(28+30+32)/3 = 30`; 5 eligible members × 10 days = 50, one absent 5 → 45, suggestion **27**.
- The Scrum Master can select any nonnegative `capacity` regardless of suggestion. Over-capacity and unestimated work are soft warnings, not validation failures.

## 4. Scope changes and task history

A sprint's history is an append-only log recording each post-start task addition/removal with action `ADDED` or `REMOVED`, task ID/key, actor ID, timestamp. It feeds burndown calculations. A related `SPRINT_SCOPE_CHANGED` entry is also written **via `TaskStatusHistoryService`** into the task's personal history, with the old sprint name in `oldValue` or new sprint name in `newValue`; `refId` is the sprint ID. Both are written in the same transaction.

This covers drag/move between active sprint and backlog, and soft-delete or restore of a parent task in an ACTIVE sprint. Restoring a task associated with a **COMPLETED** sprint clears the association and returns it to the backlog; it never leaves the task stuck in closed work. Reordering within the same sprint does not create a scope event. A deleted PLANNED sprint removes assignments without creating ACTIVE scope changes.

## 5. Project sprint-length setting

**Canonical API:** `PATCH /api/v1/projects/{projectId}` with `{ "sprintLengthDays": 14 }`, using existing `EDIT_SETTINGS` authorization. Valid range **2–90** calendar days, Scrum only. The response `ProjectResponse.sprintLengthDays` is always present, including when reading/listing projects. Changing the value writes the standard `PROJECT_SETTINGS_CHANGED` audit entry. Existing sprint dates are unaffected.

**Legacy-compatible endpoints:** `GET /api/v1/projects/{projectId}/sprint-length` and `PUT /api/v1/projects/{projectId}/sprint-length` body `{ "days":14 }`, both return typed `SprintLengthResponse` `{ "sprintLengthDays":14 }`. The PUT delegates to the same audited project-settings update. Prefer `PATCH /projects/{projectId}` for new clients.

## 6. Errors

All errors have the common `{status,code,message,path,timestamp}` shape (and optionally `fieldErrors`).

| HTTP | Code | Cases |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | Missing goal (`"Add a Sprint Goal first."`), missing tasks, invalid/overlapping dates, conflicting sprint filters, cross-project task, subtask move, Viewer availability, illegal move order, invalid state-specific input. |
| 400 | `VALIDATION_ERROR` | Bean-validation violations: blank/oversized names, capacity <0, invalid availableDays, sprint length outside 2–90. |
| 401 | `AUTHENTICATION_ERROR` | Missing authentication. |
| 403 | `ACCESS_DENIED` | Project member lacks required permission, e.g. non-Scrum-Master writes. |
| 404 | `RESOURCE_NOT_FOUND` | Unknown or not-visible project/sprint/task. |
| 409 | `WORKSPACE_ARCHIVED` | A mutation against an archived workspace. |
| 409 | `CONFLICT` | Completed sprint changed/deleted, ACTIVE sprint deleted, closed project or incompatible state. |
| 409 | `SPRINT_ALREADY_ACTIVE` | Start attempted while another sprint is ACTIVE. |

## 7. Implementation status

| Capability | PMP-119 status |
| --- | --- |
| Create/list/read/update/delete planned sprints; permission checks, lock ordering | Implemented |
| Sprint dates/default length, nonoverlap and early-completion handling | Implemented |
| Task-list `sprintId` / `noSprint` filtering before pagination | Implemented |
| Start with immutable commitment + `SprintStarted` notification | Implemented |
| Capacity and eligible non-Viewer member availability | Implemented |
| Sprint and task history for post-start scope moves, delete/restore | Implemented |
| Complete with basic carry-over, `SprintCompleted` notification | Implemented (partial closure contract) |
| Full completion audit entry | **PMP-122, not yet implemented** |
| Explicit "carried over" history marker | **PMP-122, not yet implemented** |
| Goal-achieved note at completion | **PMP-122, not yet implemented** |
| Unfinished items moved to **top** of backlog on completion (instead of preserving original rank) | **PMP-122, not yet implemented** |
| Frontend wiring and acceptance validation | Separate PMP-120 / PMP-121 |

**Migration:** `V15__sprint_planning.sql` (requires V14 attachments) creates sprint and history tables, assigns nullable task sprint IDs, installs cascade/SET NULL FKs, sets project default 14 and range 2–90, and extends `ck_task_history_type` with `SPRINT_SCOPE_CHANGED`. The entity DDL annotations mirror these constraints for dev/test databases built by `ddl-auto`.

**Verification:** run `bash ./mvnw clean verify` with PostgreSQL/Testcontainers available. Do not report a green test suite until that command succeeds on the target branch.
