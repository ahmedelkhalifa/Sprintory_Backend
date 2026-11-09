# Sprintory — Sprints API contract (R14, R15)

**Status:** PMP-119 (planning), PMP-41 (close, cancel, carry-over, overdue reminder) and PMP-40 (Scrum board) backend implemented; the Testcontainers suite must pass (`clean verify`) before merge (see §7).  
**Applies to:** `/api/v1/projects/{projectId}/sprints`, `/api/v1/sprints/{sprintId}` (including `/close-preview`, `/complete`, `/cancel`), task-list sprint filtering, the Scrum board fields of `GET /projects/{projectId}/board`, and Scrum project settings.  
**Related:** [Tasks](./tasks-docs.md), [Task history](./task-history-docs.md), [Projects](./projects-docs.md), [Board](./board-docs.md), [Notifications](./notifications-docs.md), [Roles](./roles-docs.md), [API conventions](./api-conventions.md).

## 1. Rules and access

- Sprints exist only in **Scrum** projects. They have a numbered, editable name; optional capacity; goal; start/end calendar dates; state `PLANNED`, `ACTIVE`, `COMPLETED` (closed) or `CANCELLED`.
- `MANAGE_SPRINTS` (Scrum Master permission) is required to create/update/delete/start/close sprints, move tasks, and change per-member availability. `CANCEL_SPRINTS` (Product Owner, plus the Project Manager as override; **not** the Scrum Master) is required to cancel. Members with `VIEW_WORK` (including Viewers) can read sprint details and the close preview.
- A nonmember cannot discover a project: `404`. An authenticated project member lacking permission gets `403`; archived workspaces get `409 WORKSPACE_ARCHIVED` for mutations.
- A sprint is read-only once it has **ended**, as `COMPLETED` (closed) or `CANCELLED`: no edits, no availability changes, and no task can be moved into or out of it (`409 CONFLICT`). Completed/archived projects are also read-only. `GET /projects/{id}/sprints` lists only `PLANNED` and `ACTIVE` sprints; read an ended one through `GET /sprints/{id}`. Restoring a deleted task whose sprint has ended returns it to the backlog.
- Several sprints may be `PLANNED`, but at most one may be `ACTIVE`; database unique index enforces this even under concurrent writes.
- All sprint writes first acquire the **project row lock**, *then* load the mutable sprint. This serializes competing starts, completions, deletes, PATCHes and task moves.
- Dates are inclusive, `endDate > startDate`, maximum 90 calendar days. On create/PATCH/start, dates supplied in a request must be **both present or both absent**.
- Sprints may not overlap. A planned or active sprint occupies its dates through `endDate`. An **ended** sprint occupies them only up to the day it ended (UTC), **not including** that day, and never beyond `endDate`. So the next sprint may start on the same day the previous one was closed or cancelled, also when that was its last planned day, and default dates for a new sprint start on that day. A sprint that ended on its own start day occupies no dates.

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
| `POST` | `/api/v1/sprints/{sprintId}/cancel` | `CANCEL_SPRINTS` | `200 CompleteSprintResponse` | Cancel (PMP-41), Product Owner or Project Manager; the Scrum Master gets `403`. Body **required** `{reason}`, not blank, ≤ 1000 characters (else `400 VALIDATION_ERROR`). Only ACTIVE; no early-close confirmation, no destination: unfinished work goes to the top of the backlog; `toSprint` is always `null`. |
| `POST` | `/api/v1/projects/{projectId}/sprints/move` | `MANAGE_SPRINTS` | `204` | `MoveSprintTaskRequest`: `{taskId, sprintId, afterTaskId}`; `sprintId:null` removes from sprint; `afterTaskId:null` means first. |
| `GET` | `/api/v1/sprints/{sprintId}/close-preview` | `VIEW_WORK` | `200 CloseSprintPreviewResponse` | ACTIVE only (else `409`). The close/cancel dialog: done and unfinished counts and points, the unfinished items in sprint order (`taskId`, `key`, `title`, `estimate`, `statusName`, `openSubtasks`), `endsEarly`, `daysLeft`, and the `PLANNED` sprints that can receive unfinished work. Done = a `DONE`-category column; no partial credit. |
| `POST` | `/api/v1/sprints/{sprintId}/complete` | `MANAGE_SPRINTS` | `200 CompleteSprintResponse` | Close (PMP-41). Body **required**: `{carryOverSprintId?, createSprint?, goalAchieved, goalNote?, confirmEarlyClose?}`. `goalAchieved` is `YES`, `PARTIALLY` or `NO` (missing → `400 VALIDATION_ERROR`); `goalNote` ≤ 1000 characters. Only ACTIVE; at most one destination. Before `endDate` (UTC) without `confirmEarlyClose: true` → `409 EARLY_CLOSE_CONFIRMATION_REQUIRED`. |

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

`CompleteSprintResponse` is `{sprint, carriedOver, toSprint}`. Closing is one transaction; a failure leaves the sprint `ACTIVE`.

- **Done vs unfinished:** done = a `DONE`-category column. No partial credit: an unfinished story counts 0 points and moves whole, with all its subtasks.
- **Done items stay linked** to the closed sprint.
- **Unfinished items** go, in sprint order, to the **top of the backlog** (the default; ranked above the current first backlog item), to the front of the nominated `PLANNED` sprint (`carryOverSprintId`), or to a new `PLANNED` sprint (`createSprint: true`, default name and length, starting today or the first free day).
- Each moved item's `carriedOverCount` (on `TaskResponse`) goes up by one, and its history gets a `CARRIED_OVER` entry: `oldValue` the closed sprint's name, `newValue` the destination sprint's name or `null` for the backlog, `refId` the closed sprint's id.
- **Frozen at the end:** `completedPoints` (feeds velocity), `unfinishedPoints`, `goalAchieved`, `goalNote`, and one `sprint_outcomes` row per item (`DONE` or `CARRIED_OVER`, estimate at the end, destination). Later edits to the tasks never change them.
- Closing writes the audit entry `SPRINT_COMPLETED` and publishes `SprintCompleted`; starting publishes `SprintStarted`. Committed item snapshots survive later estimate changes.

**Cancel** (`POST /cancel`) ends the sprint as `CANCELLED` with the same split, carry-over to the top of the backlog, `CARRIED_OVER` history, ×N marker and frozen `completedPoints` / `unfinishedPoints` / outcome rows, so reports stay accurate. It stores `cancelReason`, has no goal answer, writes the audit entry `SPRINT_CANCELLED` **including the reason**, and publishes `SprintCancelled` (the notification does not carry the reason). Velocity reads `COMPLETED` sprints only, so a cancelled sprint never counts toward the capacity suggestion.

`SprintResponse` also carries the end fields: `unfinishedPoints`, `goalAchieved`, `goalNote`, `cancelReason` (null until the sprint ends; `completedAt` is when it ended).

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

- Velocity: average frozen `completedPoints` of the last **up to three completed** sprints. **Cancelled sprints are excluded.** None completed → no suggestion (`null`).
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
| 409 | `EARLY_CLOSE_CONFIRMATION_REQUIRED` | Close before `endDate` without `confirmEarlyClose: true`; show the warning and retry with it. |

## 7. Implementation status

| Capability | Status |
| --- | --- |
| Create/list/read/update/delete planned sprints; permission checks, lock ordering | Implemented (PMP-119) |
| Sprint dates/default length, nonoverlap, same-day start after an ended sprint | Implemented (PMP-119, PMP-41) |
| Task-list `sprintId` / `noSprint` filtering before pagination | Implemented (PMP-119) |
| Start with immutable commitment + `SprintStarted` notification | Implemented (PMP-119) |
| Capacity and eligible non-Viewer member availability; cancelled sprints excluded from velocity | Implemented (PMP-119, PMP-41) |
| Sprint and task history for post-start scope moves, delete/restore | Implemented (PMP-119) |
| Close preview (done / unfinished, early-close flag, destinations) | Implemented (PMP-41) |
| Close: goal answer and note, early-close confirmation, frozen points and outcomes, audit `SPRINT_COMPLETED`, `SprintCompleted` | Implemented (PMP-41) |
| Cancel: `CANCEL_SPRINTS`, required reason, audit `SPRINT_CANCELLED` with the reason, `SprintCancelled` | Implemented (PMP-41) |
| Carry-over to the top of the backlog / planned sprint / new sprint, "Carried over ×N", `CARRIED_OVER` history | Implemented (PMP-41) |
| Daily overdue reminder to the Scrum Masters (`SPRINT_OVERDUE`) | Implemented (PMP-41) |
| Scrum board: `activeSprint` summary and per-column sprint counts | Implemented (PMP-40) |
| Frontend wiring and acceptance validation | Separate (PMP-120 / PMP-121, PMP-123 / PMP-124) |

**Migrations:**
- `V15__sprint_planning.sql` (requires V14 attachments) creates sprint and history tables, assigns nullable task sprint IDs, installs cascade/SET NULL FKs, sets project default 14 and range 2–90, and extends `ck_task_history_type` with `SPRINT_SCOPE_CHANGED`.
- `V16__sprint_close_cancel.sql` adds `CANCELLED` to `ck_sprint_status`; `unfinished_points`, `goal_achieved`, `outcome_note`, `cancel_reason` on `sprints`; `tasks.carry_over_count` (default 0); the `sprint_outcomes` table; and extends `ck_task_history_type` (`CARRIED_OVER`), `ck_audit_log_entries_action` (`SPRINT_COMPLETED`, `SPRINT_CANCELLED`), `ck_audit_log_entries_target_type` (`SPRINT`) and `ck_notifications_type` (`SPRINT_OVERDUE`).

The entity DDL annotations mirror these constraints for dev/test databases built by `ddl-auto`.

**Verification:** run `bash ./mvnw clean verify` with PostgreSQL/Testcontainers available. Do not report a green test suite until that command succeeds on the target branch.
