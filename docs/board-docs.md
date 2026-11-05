# Sprintory — Board configuration API contract (R16, PMP-125)

**Status:** backend endpoints for column configuration, categories, soft deletion, WIP limits and task status history are implemented. The full Maven verification passed (381 tests, 1 Nov 2026) and Flyway V1–V7 applied successfully in PostgreSQL Testcontainers. Final follow-up regression and frontend end-to-end acceptance remain pending. See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/projects/{projectId}/board/**`; status moves and history under `/api/v1/projects/{projectId}/tasks/**`.
**Related:** [Projects](./projects-docs.md) (board creation and template), [Tasks](./tasks-docs.md) (task movement), [Roles and permissions](./roles-docs.md), [API and Git Conventions](./api-conventions.md) (standard errors and project visibility), [Requirements](./requirements.md) R16.

This document is the single API reference for board configuration. If the code and this file disagree, fix both in the same PR.

---

## 1. Overview

- **Board configuration belongs to the project.** Each board has 2–10 active columns, in a persisted 0-based order. Column ids are stable when the name, category or position changes.
- **Categories** are `TODO`, `IN_PROGRESS` and `DONE`, independent of column names. At least one `TODO` and one `DONE` must remain. Reports and completion views must use the category, not a literal name such as "Done" or the rightmost position.
- **Names** are required, trimmed, 1–100 characters, unique case-insensitively among *active* columns in one project.
- **Kanban Backlog** is the first column. It may not be removed, reordered away from position 0, or given a WIP limit; its category stays `TODO`. Kanban has no separate backlog page.
- **Soft delete** records `deleted_at`, retains the row, and hides it from the active board. If it contains active tasks, the caller must choose another active column in the same project; each task moves there and receives a task-status-history event. There is no restoration endpoint in PMP-125.
- **WIP is soft.** A limit is an optional positive integer on a *non-Backlog, non-DONE Kanban* column. Tasks can move past the limit. The backend returns counts and an optional warning; the frontend renders `taskCount / wipLimit` and highlights only while `taskCount > wipLimit`.
- **Counts are project-wide and unfiltered.** `taskCount` counts active top-level tasks in the column, not only those currently shown by a client-side filter. Only **top-level tasks** count for WIP; PMP-132 subtasks are excluded even if their status points to the column. `subtaskCount` separately reports live children so a column with only subtasks still prompts for a relocation target on deletion.
- **Scrum never has WIP controls.** The API rejects setting a WIP limit on Scrum columns, regardless of category.
- **Archived workspaces are read-only.** Reading remains possible, but board mutations return `409 WORKSPACE_ARCHIVED`.

### Who can do what

| Action | Not logged in | Project not visible | Member or Viewer | Project Manager | Workspace Admin |
| --- | --- | --- | --- | --- | --- |
| Read board configuration | 401 | 404 | allowed (`VIEW_WORK`) | allowed | allowed (`CONFIGURE_BOARD`) |
| Add, rename, reorder, recategorize, delete, set WIP | 401 | 404 | 403 | allowed | allowed |
| Move tasks | 401 | 404 | Member: allowed; Viewer: 403 | allowed | 403 unless also a project member with `EDIT_TASKS` |

Column mutations require `CONFIGURE_BOARD`, which is granted to Project Managers and workspace Admins. Task moves require `EDIT_TASKS`, not `CONFIGURE_BOARD`. The UI should use `ProjectResponse.permissions`, rather than hard-coded role names ([roles-docs §2](./roles-docs.md#2-permission-matrix)).

---

## 2. Endpoints at a glance

All paths are under `/api/v1`. Bodies are JSON in `camelCase`.

| # | Method | Path | Request | Success | Permission |
| --- | --- | --- | --- | --- | --- |
| 3.1 | GET | `/projects/{projectId}/board` | none | `200 ProjectBoardResponse` | `VIEW_WORK` or `CONFIGURE_BOARD` |
| 3.2 | POST | `/projects/{projectId}/board/columns` | `CreateBoardColumnRequest` | `201 ProjectBoardResponse` | `CONFIGURE_BOARD` |
| 3.3 | PATCH | `/projects/{projectId}/board/columns/{columnId}` | `UpdateBoardColumnRequest` | `200 ProjectBoardResponse` | `CONFIGURE_BOARD` |
| 3.4 | PUT | `/projects/{projectId}/board/columns/order` | `ReorderBoardColumnsRequest` | `200 ProjectBoardResponse` | `CONFIGURE_BOARD` |
| 3.5 | DELETE | `/projects/{projectId}/board/columns/{columnId}` | `DeleteBoardColumnRequest` when needed | `204`, no body | `CONFIGURE_BOARD` |
| 3.6 | PUT | `/projects/{projectId}/tasks/{taskId}/position` | `MoveTaskRequest` | `200 TaskResponse` | `EDIT_TASKS` |
| 3.7 | GET | `/projects/{projectId}/tasks/{taskId}/history` | none | `200 TaskStatusHistoryResponse[]` | `VIEW_WORK` |

### Request and response bodies

```jsonc
// CreateBoardColumnRequest: insert after an existing column; omit afterColumnId to append
{ "name": "In Review", "category": "IN_PROGRESS", "afterColumnId": 3 }

// UpdateBoardColumnRequest: only supplied, non-null values change
{ "name": "Under Review", "category": "IN_PROGRESS", "wipLimit": 4 }
// Or clear a limit explicitly
{ "clearWipLimit": true }

// ReorderBoardColumnsRequest: every active column exactly once
{ "columnIds": [1, 2, 5, 3, 4] }

// DeleteBoardColumnRequest: required if the column has tasks
{ "targetColumnId": 2 }
```

`afterColumnId`, `wipLimit`, and `targetColumnId` are optional in their respective requests. Omitted or `null` `wipLimit` on PATCH means **no change**; use `clearWipLimit: true` to remove it. Do not send both a limit and `clearWipLimit: true`.

---

## 3. Endpoints in detail

### 3.1 Read the board

`GET /api/v1/projects/{projectId}/board`

- **Who:** a project member with `VIEW_WORK`, or a workspace Admin with `CONFIGURE_BOARD`. An admin not in the project can read board *configuration*, but not task details.
- **Response `200`:** all active columns in position order. This example shows a Kanban column above its limit:

  ```json
  {
    "projectId": 15,
    "template": "KANBAN",
    "backlogEnabled": false,
    "sprintsEnabled": false,
    "wipLimitsEnabled": true,
    "activeSprintId": null,
    "columns": [
      { "id": 1, "name": "Backlog", "position": 0, "wipLimit": null, "category": "TODO", "taskCount": 3, "wipExceeded": false, "subtaskCount": 0 },
      { "id": 2, "name": "To Do", "position": 1, "wipLimit": null, "category": "TODO", "taskCount": 1, "wipExceeded": false, "subtaskCount": 0 },
      { "id": 3, "name": "In Progress", "position": 2, "wipLimit": 3, "category": "IN_PROGRESS", "taskCount": 4, "wipExceeded": true, "subtaskCount": 0 },
      { "id": 4, "name": "Done", "position": 3, "wipLimit": null, "category": "DONE", "taskCount": 5, "wipExceeded": false, "subtaskCount": 0 }
    ]
  }
  ```

- `wipExceeded` is true precisely when the column has a limit and `taskCount > wipLimit`. It is false when the count returns to the limit or below. The frontend may show `4 / 3` and a red warning for the third column.
- This endpoint is also documented under [Projects §3.8](./projects-docs.md#38-read-the-projects-board).

### 3.2 Add a column

`POST /api/v1/projects/{projectId}/board/columns`

- **Who:** `CONFIGURE_BOARD`; active workspace.
- **Request:** `name` and `category` are required. `afterColumnId` is optional; omitted means append. To put a new Scrum column at position 0, add it first and then call the reorder endpoint.
- **Response `201`:** the entire updated `ProjectBoardResponse`, with persisted order. Refresh shared board state from this response.
- **Errors:** `400 VALIDATION_ERROR` for invalid body; `400 BAD_REQUEST` for duplicate name, invalid reference, or more than 10 columns; usual access and workspace errors (§4).

### 3.3 Rename, change category or set WIP

`PATCH /api/v1/projects/{projectId}/board/columns/{columnId}`

- **Request:** send only changed fields. Rename preserves `id`, task membership and task status. A category change changes semantic classification without moving tasks.
- **Category rule:** no change may remove the final `TODO` or `DONE`. Kanban's first Backlog remains `TODO`.
- **WIP rule:** limits must be positive integers. Limits are unavailable on Scrum, Kanban Backlog and `DONE`. To recategorize a limited column to `DONE`, send `{ "category": "DONE", "clearWipLimit": true }` together.
- **Response `200`:** updated board with current task counts.
- **Errors:** `400 VALIDATION_ERROR` for zero/negative or nonnumeric WIP; `400 BAD_REQUEST` for duplicate name, prohibited category change or prohibited WIP; standard access errors (§4).

### 3.4 Reorder columns

`PUT /api/v1/projects/{projectId}/board/columns/order`

- **Request:** `{ "columnIds": [1, 2, 5, 3, 4] }`. Include *all active column ids*, exactly once. Kanban's first Backlog ID must remain at index 0.
- **Response `200`:** updated board. No tasks change column, and no status-history events are written.
- **Errors:** `400` for missing, duplicate, inactive, cross-project, or incorrect column ids, or moving Kanban Backlog.

### 3.5 Soft-delete a column

`DELETE /api/v1/projects/{projectId}/board/columns/{columnId}`

- **Empty column:** no request body is needed (`{}` is also accepted).
- **Column with tasks:** send `{ "targetColumnId": 2 }` to name a *different active column of the same project*.
- **Result `204`:** moved tasks are appended to the target column in board order, one status-history event per active task; the old column row is soft-deleted. The operation is transactional.
- **Refused:** Kanban Backlog; deleting below two columns; deleting the final `TODO` or `DONE`; missing/invalid target for a nonempty column.
- **No restore endpoint** is available. The soft-deleted column row stays in the database, but is excluded from board reads.

### 3.6 Soft WIP warning on task move

`PUT /api/v1/projects/{projectId}/tasks/{taskId}/position` (also `PATCH /tasks/{taskId}` with a changed `statusId`).

- **Request:** `{ "statusId": 3, "afterTaskId": null }` places the task first in the target column.
- **Response `200`:** the usual `TaskResponse`. If the destination exceeds its WIP limit, `wipWarning` is populated:

  ```json
  {
    "wipWarning": {
      "columnId": 3,
      "count": 4,
      "limit": 3,
      "message": "WIP limit exceeded; the task was moved successfully."
    }
  }
  ```

  The snippet above shows **only the new response field**, not the entire `TaskResponse`.
- **Never block a valid task move just because of WIP.** The client should refresh `GET /board` after a move, and highlight a column only while `wipExceeded` is true. Display the *unfiltered* count even if the task list uses a search/filter.
- Status changes also create the history event in §3.7. Reordering within the same column does not.

### 3.7 Read a task's status history

`GET /api/v1/projects/{projectId}/tasks/{taskId}/history`

- **Who:** `VIEW_WORK`, so only project members (including Viewers) may read the history. A workspace admin without project membership receives `403`.
- **Response `200`:** events in chronological order:

  ```json
  [
    { "id": 83, "fromColumnId": 1, "fromColumnName": "Backlog",
      "toColumnId": 3, "toColumnName": "In Progress",
      "actorId": 17, "changedAt": "2026-11-01T19:30:00Z" }
  ]
  ```

- An event is created by changing `statusId` via task PATCH, moving across columns via `position`, or rehoming a task when a column is deleted. Same-column reordering creates no status event.
- Names are stored as snapshots on the event, so a later rename does not change the historical text. Task creation does not generate a "from → to" event.

---

## 4. Errors

Errors use [API conventions §4](./api-conventions.md#4-standard-error-responses): `{ "timestamp", "status", "code", "message", "path", "fieldErrors?" }`. Branch on `code`, not the exact wording of `message`.

| Status | Code | When |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Missing/blank/too-long names, invalid IDs, WIP `0` or negative; malformed type/JSON is handled as `BAD_REQUEST` |
| 400 | `BAD_REQUEST` | Duplicate active name; 10-column cap; missing/mismatched reorder ids; forbidden category/WIP change; Kanban Backlog operation; deleting final TODO/DONE; missing target when deleting an occupied column |
| 401 | `AUTHENTICATION_ERROR` | No valid session |
| 403 | `ACCESS_DENIED` | Visible project but not authorized to configure or move |
| 404 | `RESOURCE_NOT_FOUND` | Project not visible or does not exist; task missing/deleted, when reading task history |
| 409 | `WORKSPACE_ARCHIVED` | Attempted mutation in an archived workspace |

Database indexes also guard concurrent duplicate active names and positions. A migration-backed PostgreSQL integration test is required to confirm the actual database behavior.

---

## 5. Frontend flows

The frontend implementation is **PMP-126**, not part of this backend delivery.

1. **Show configuration only for `CONFIGURE_BOARD`** in `ProjectResponse.permissions`. Never use `EDIT_TASKS` as a substitute. A workspace Admin may configure columns even without task visibility.
2. **Render server columns.** `GET /board` returns ordered, persistent columns; do not keep UI-only column overrides. After every create/edit/reorder/delete, use the returned board (for DELETE, refetch it).
3. **Add/edit/reorder.** Collect name, category and optional Kanban WIP. Use `afterColumnId` for insertion; use the full-ID reorder endpoint for drag-and-drop. Use category for completion and reports.
4. **Delete with tasks.** When the chosen column contains tasks (`taskCount > 0`), require a destination selector. Request soft delete with `targetColumnId`, then refetch the board/tasks.
5. **Soft warnings.** Display `taskCount / wipLimit` for limited columns. Apply warning style when `wipExceeded` is true; a move still succeeds and may return `TaskResponse.wipWarning` for a toast.
6. **Scrum.** Never show WIP editing or WIP counters because `wipLimitsEnabled` is false. Do not offer WIP setup even if a client can craft a request.
7. **History.** Optionally expose `GET /tasks/{taskId}/history` in the task view; the endpoint is independent of the audit-log story for other task changes.

---

## 6. Data model

| Table | Column(s) | Behavior |
| --- | --- | --- |
| `project_board_columns` | `category` (`TODO` / `IN_PROGRESS` / `DONE`), `deleted_at` (nullable), existing `wip_limit`, `sort_order` | `deleted_at IS NULL` rows are active; soft-deleted columns retain their ids |
| `task_status_history` | `id`, `task_id`, `from_column_id`, `to_column_id`, `from_column_name`, `to_column_name`, `actor_id`, `changed_at` | Append-only history; snapshots preserve past column names; table cascades with task deletion at the database level |
| `tasks` | `status_column_id`, `board_position`, `deleted_at` | Task retains column id when renamed; moves rewrite status and rank; deleted tasks are not counted in WIP |

- **Migration:** `V7__board_configuration_and_status_history.sql`, applied *after* V6. It backfills categories for existing boards, adds the soft-delete field, creates active-only unique indexes on `(project_id, lower(name))` and `(project_id, sort_order)`, and creates the history table. Earlier migrations must not be edited.
- **Code:** `project/{controllers,services,repositories,dto,models}` for board configuration; `task/{services,repositories,dto,models}` for moves, warnings and history.
- **Local DB caveat:** default development configuration disables Flyway and enables Hibernate `ddl-auto=update`. Hibernate alone does not perform V7's backfill or filtered indexes. Validate V7 against a *fresh* PostgreSQL database with Flyway enabled and schema validation; do not enable Flyway against a previously hand-patched database without reconciling its history and backing it up.

---

## 7. What the API reveals

| Caller/action | Reveals |
| --- | --- |
| Member or Viewer reading board | Column configuration and task counts of their own project only |
| Workspace Admin not in project reading board | Column configuration and aggregate task counts, but **not** task details or task history |
| Non-member, non-admin accessing project path | Same `404` as a nonexistent project |
| Member/Viewer attempting a mutation | `403` without changing any columns |

---

## 8. Known limitations and later stories

- **No column restore API.** Soft deletion preserves rows for integrity, but restoring them requires a separate decision on names, order, categories and tasks.
- **Subtasks:** PMP-132 excludes child tasks from WIP counts and board cards. Deleting a column still moves live child tasks along with their parent cards, preserving individual status histories.
- **PMP-132 API:** `ProjectBoardColumnResponse.subtaskCount` counts live children separately. It does not affect `taskCount` or WIP. When deleting a column, the frontend requests a relocation target if `taskCount + subtaskCount > 0`.
- **Status history only.** Changes to task titles, estimates, labels, comments, etc. are not recorded here; broader audit activity remains a separate story.
- **No sprint lifecycle yet.** `activeSprintId` remains `null` until R14/R15 is built.
- **No client UI changes in PMP-125.** PMP-126 integrates these endpoints, then PMP-127 verifies end-to-end acceptance.

---

## 9. Implementation status

Baseline PMP-125 (merged into backend `main` by 3 Nov 2026). Test counts below refer to the original 1 Nov verification, **not** to the new PMP-131/132 patch:

| Part | Status |
| --- | --- |
| Column create/update/order/delete endpoints and permissions | Implemented; selected unit tests passed |
| Board categories and Kanban WIP metadata | Implemented; selected unit tests passed |
| Task status history and soft move warnings | Implemented; selected unit tests passed |
| Migration `V7__board_configuration_and_status_history.sql` | Applied successfully on PostgreSQL 16 via Testcontainers (V1–V7) |
| Targeted Maven tests (BoardColumnValidationTest, ProjectBoardServiceTest, ProjectBoardColumnControllerTest, TaskApiServiceTest, TaskControllerTest, ProjectRoleTest) | **52 passed, 0 failures, 0 errors** (1 Nov 2026, developer machine) |
| Full test suite and PostgreSQL acceptance tests | Final baseline clean verify: **382 passed, 0 failed, 0 errors** (1 Nov 2026, developer machine). PMP-131/132 must run its own full suite after applying V9. |
| Frontend integration (PMP-126) and manual acceptance (PMP-127) | Not started as part of this backend patch |

---

## 10. Decisions

| Date | Decision |
| --- | --- |
| 1 Nov 2026 | WIP is a soft warning: never reject an otherwise valid task move for exceeding a limit. |
| 1 Nov 2026 | Persist category separate from column name; keep at least one TODO and one DONE column. |
| 1 Nov 2026 | Kanban Backlog stays at position 0 and has no WIP limit; DONE columns also have no WIP. |
| 1 Nov 2026 | Use soft deletion for columns; rehome tasks with a target and record a status event for each moved task. No restore endpoint in this scope. |
| 1 Nov 2026 | `CONFIGURE_BOARD` is granted to Project Managers and workspace Admins; task movement remains under `EDIT_TASKS`. |
