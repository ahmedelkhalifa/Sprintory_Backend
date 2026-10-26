# Sprintory — Tasks API contract (R09)

**Status:** tasks with keys, all story 9 fields, soft delete, filters and search, and the task order on the board and in the backlog. See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/projects/{projectId}/tasks/**`.
**Related:** [Projects](./projects-docs.md) (keys, board, columns), [Roles and permissions](./roles-docs.md), [Universal search](./search-docs.md), [Workspaces](./workspaces-docs.md) (archiving, pagination), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R09, R13, R16.

This document is the single reference for tasks end to end. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **A task lives inside one project.** Its fields:

  | Field | Rules |
  | --- | --- |
  | Key | Generated: the project key and a number, e.g. `MOB-42`. Unique, never changes, **never reused**, not even after the task is deleted. |
  | Title | Required, at most 255 characters. Leading and trailing spaces are removed. |
  | Type | `STORY`, `TASK` or `BUG`. Default `TASK`. |
  | Priority | `HIGHEST`, `HIGH`, `MEDIUM`, `LOW` or `LOWEST`. Default `MEDIUM`. |
  | Status | One of the project's board columns ([projects-docs §3.8](./projects-docs.md#38-read-the-projects-board)). Default: the first column. |
  | Assignee | Optional. Must be a member of the project. Someone who leaves the project (removed from it, or leaving or removed from the workspace) is unassigned from its tasks; a deleted user too. |
  | Estimate | Optional story points, 0 or more (whole numbers). |
  | Labels | Optional free-text tags, e.g. `R09`, `backend`. At most 20, each at most 50 characters. |
  | Description | Optional rich text, stored as sent (a string). No length limit. |

- **New tasks start in the backlog.** What "the backlog" means depends on the template:
  - **Scrum:** a task is in the backlog when it is **in no sprint**. Its status is still a column (the first one, To Do). The Scrum board shows only the active sprint's tasks (R15), so a backlog task is not on the board until the Scrum Master pulls it into a sprint (R14) and starts the sprint. Until sprints exist, every Scrum task is in the backlog and `sprintId` is always `null`.
  - **Kanban:** there are no sprints, so the backlog is the first **column**, "Backlog". A new task is in that column.
- **Deleting is a soft delete.** The task disappears from the board, the backlog, every list and search, and returns `404`, but its row stays in the database. Its key is not given out again.
- **Order.** Each task has a place in its board column and a place in the backlog. New tasks go last in both. Drag and drop uses two endpoints (3.6, 3.7); lists can be read in board or backlog order (`?order=`, §3.2).
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Anyone who may not see the project gets `404`.
- **Tasks are project work** (story 7). A workspace admin who is **not** a member of the project gets `403` on every task endpoint, reads included.
- **Archived workspaces are read-only.** Tasks can still be read; every change gets `409 WORKSPACE_ARCHIVED`.

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Member/Developer, Scrum Master | Product Owner | Project Manager |
| --- | --- | --- | --- | --- | --- | --- |
| Read, list, search tasks | 404 | 403 | allowed | allowed | allowed | allowed |
| Create, edit, delete, move on the board | 404 | 403 | 403 | allowed | allowed | allowed |
| Reorder the backlog (Scrum) | 404 | 403 | 403 | 403 | allowed | 403 |

Reading needs `VIEW_WORK`, changing needs `EDIT_TASKS`, reordering the backlog needs `MANAGE_BACKLOG` ([roles-docs.md §2](./roles-docs.md#2-permission-matrix)). Per the matrix's "strict Scrum roles", **only the Product Owner** holds `MANAGE_BACKLOG`; a Project Manager who should reorder the backlog gives themselves the Product Owner role too. Kanban projects do not offer that role, so nobody reorders a Kanban backlog (it is a column there anyway).

---

## 2. Endpoints at a glance

All paths are under `/api/v1/projects/{projectId}/tasks`. Bodies are JSON in `camelCase`.

| # | Method | Path | Request | Success | Permission |
| --- | --- | --- | --- | --- | --- |
| 3.1 | POST | `` | `CreateTaskRequest` | `201` + `TaskResponse` | `EDIT_TASKS` |
| 3.2 | GET | `?q=&type=&priority=&statusId=&assigneeId=&label=&order=&page=&size=` | none | `200` + `PageResponse<TaskResponse>` | `VIEW_WORK` |
| 3.3 | GET | `/{taskId}` | none | `200` + `TaskResponse` | `VIEW_WORK` |
| 3.4 | PATCH | `/{taskId}` | `UpdateTaskRequest` | `200` + `TaskResponse` | `EDIT_TASKS` |
| 3.5 | DELETE | `/{taskId}` | none | `204` | `EDIT_TASKS` |
| 3.6 | PUT | `/{taskId}/position` | `MoveTaskRequest` | `200` + `TaskResponse` | `EDIT_TASKS` |
| 3.7 | PUT | `/{taskId}/backlog-rank` | `ReorderBacklogRequest` | `200` + `TaskResponse` | `MANAGE_BACKLOG` |

A `{taskId}` is always looked up **inside** `{projectId}`: a task of another project gives the same `404` as a missing one.

### Bodies

```jsonc
// CreateTaskRequest: only "title" is required
{ "title": "Login fails on Safari", "type": "BUG", "priority": "HIGH", "assigneeId": 7,
  "estimate": 3, "labels": ["backend", "R09"], "description": "Steps to reproduce: ..." }

// UpdateTaskRequest: send only what changes (see 3.4)
{ "statusId": 13, "clearAssignee": true }

// MoveTaskRequest: the column, and the task to place it after in that column (null = first)
{ "statusId": 13, "afterTaskId": 41 }

// ReorderBacklogRequest: the task to place it after (null = first)
{ "afterTaskId": 41 }

// TaskResponse
{ "id": 55, "projectId": 8, "key": "MOB-42", "title": "Login fails on Safari",
  "type": "BUG", "priority": "HIGH", "statusId": 12, "statusName": "To Do",
  "assigneeId": 7, "estimate": 3, "labels": ["R09", "backend"],
  "description": "Steps to reproduce: ...", "sprintId": null,
  "createdAt": "2026-10-26T09:30:00Z", "updatedAt": "2026-10-26T09:30:00Z" }
```

- `labels` come back sorted. Labels are trimmed, and duplicates that differ only in case collapse into the first spelling (`["backend", "Backend"]` stores `backend`).
- `assigneeId` is a user id; show the name from `GET /projects/{projectId}/members`.
- `sprintId` stays `null` until sprints exist (R14).
- The board positions and backlog ranks are internal and never sent; lists come back already sorted (§3.2).

---

## 3. Endpoints in detail

### 3.1 Create a task

`POST /api/v1/projects/{projectId}/tasks`

- **Who:** members with `EDIT_TASKS` (everyone but Viewers). The workspace must be active.
- **Result:** the next key; `type` `TASK` and `priority` `MEDIUM` when left out; status = the board's first column (To Do in Scrum, Backlog in Kanban); last in that column and last in the backlog; no sprint.
- **Response `201`:** `TaskResponse`.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `title` missing, blank or over 255; `estimate` negative; `assigneeId` not positive; more than 20 labels, or a label blank or over 50 |
  | 400 | `BAD_REQUEST` "Malformed request body." | e.g. `"type": "EPIC"` or `"estimate": "three"` |
  | 400 | `BAD_REQUEST` "The assignee must be a member of this project." | `assigneeId` is not a project member |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | a Viewer, or a workspace admin who is not in the project |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | the project does not exist or the caller cannot see it |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

### 3.2 List and search a project's tasks

`GET /api/v1/projects/{projectId}/tasks?q=&type=&priority=&statusId=&assigneeId=&label=&order=&page=&size=`

- **Who:** members with `VIEW_WORK` (every project role). Archived workspace: allowed.
- **Filters** (all optional; every one that is given narrows the list):

  | Parameter | Matches |
  | --- | --- |
  | `q` | the title **or** the key contains it, ignoring case: `login` finds "Login fails", `mob-4` finds `MOB-42`. `%` and `_` are taken literally. |
  | `type` | `STORY`, `TASK`, `BUG`, ignoring case |
  | `priority` | `HIGHEST` … `LOWEST`, ignoring case |
  | `statusId` | tasks in that column |
  | `assigneeId` | tasks assigned to that user |
  | `label` | tasks with that label, ignoring case |

- **Order** (`?order=`, ignoring case):

  | Value | Sorted by | Use it for |
  | --- | --- | --- |
  | `newest` (default) | newest task first | the task list |
  | `board` | column order, then the place in the column | the board: all columns in one call, or one column with `statusId` |
  | `backlog` | the place in the backlog | the Scrum backlog page |

- **Paging** as everywhere ([workspaces-docs.md, Pagination and search](./workspaces-docs.md#pagination-and-search)): `page` 0-based, `size` default 20, at most 100. `?sort=` is ignored; use `order`.
- **Response `200`:** `PageResponse<TaskResponse>`. Deleted tasks are never included.
- **Errors:** `400 BAD_REQUEST` "Invalid 'type' filter." / "Invalid 'priority' filter." / "'statusId' must be a positive ID." / "'assigneeId' must be a positive ID." / "Invalid 'order': use newest, board or backlog."; `401`; `403` (an admin not in the project); `404` "Project not found.".

A `statusId` or `assigneeId` that does not belong to the project simply finds nothing.

### 3.3 Get a task

`GET /api/v1/projects/{projectId}/tasks/{taskId}`

- **Who:** members with `VIEW_WORK`. Archived workspace: allowed.
- **Response `200`:** `TaskResponse` with every field (story 9: key, title, type, priority, status, assignee, estimate, labels, description).
- **Errors:** `401`; `403` (an admin not in the project); `404` "Project not found."; `404` "Task not found." for a missing task, a deleted one, or a task of another project.

### 3.4 Edit a task

`PATCH /api/v1/projects/{projectId}/tasks/{taskId}`

- **Who:** members with `EDIT_TASKS`. The workspace must be active.
- **Rules:**
  - A field that is left out or `null` **stays as it is**. `{}` changes nothing.
  - To empty a field, send its flag: `clearAssignee`, `clearEstimate`, `clearDescription` (`true`). Sending a value **and** its clear flag is a `400`.
  - `labels` replaces all labels; `"labels": []` removes them all.
  - `statusId` must be a column of this project's board. Changing it puts the task **last** in the new column. To drop it at a particular place, use 3.6 instead.
  - `assigneeId` must be a project member.
  - The key, the project and the sprint cannot be changed here.
- **Response `200`:** the updated `TaskResponse`.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `title` blank or over 255; `estimate` negative; ids not positive; label limits; a value together with its clear flag |
  | 400 | `BAD_REQUEST` "The status must be a column of this project's board." | `statusId` of another project, or no such column |
  | 400 | `BAD_REQUEST` "The assignee must be a member of this project." | `assigneeId` is not a project member |
  | 401, 403, 404, 409 | | as in 3.1; plus `404` "Task not found." as in 3.3 |

### 3.5 Delete a task

`DELETE /api/v1/projects/{projectId}/tasks/{taskId}`

- **Who:** members with `EDIT_TASKS`. The workspace must be active.
- **Soft delete:** the task disappears everywhere (board, backlog, lists, search, 3.3) but stays in the database. Its key is never given out again: after deleting `MOB-2`, the next task is still `MOB-3`.
- **Response:** `204`, no body. Deleting it again gives `404` "Task not found.".
- **Errors:** `401`, `403`, `404`, `409` as in 3.4.

There is no undelete endpoint yet.

### 3.6 Move a task on the board

`PUT /api/v1/projects/{projectId}/tasks/{taskId}/position`

One drag and drop: puts the task in column `statusId`, right **after** `afterTaskId`, or first in the column when `afterTaskId` is `null`. Works inside one column and across columns; a move across columns also changes the task's status.

- **Who:** members with `EDIT_TASKS`. The workspace must be active.
- **Request:** `{ "statusId": 13, "afterTaskId": 41 }`.
- **Response `200`:** the moved `TaskResponse`. Reload the board with `?order=board` to show everyone's current order.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `statusId` missing or not positive; `afterTaskId` not positive |
  | 400 | `BAD_REQUEST` "The status must be a column of this project's board." | as in 3.4 |
  | 400 | `BAD_REQUEST` "A task cannot be placed after itself." | `afterTaskId` = `taskId` |
  | 400 | `BAD_REQUEST` "The task to place it after is not in this project." | missing, deleted, or another project's task |
  | 400 | `BAD_REQUEST` "The task to place it after must be in the target column." | `afterTaskId` is in another column |
  | 401, 403, 404, 409 | | as in 3.4 |

### 3.7 Reorder the backlog (Scrum)

`PUT /api/v1/projects/{projectId}/tasks/{taskId}/backlog-rank`

One drag and drop in the Scrum product backlog (R13): puts the task right after `afterTaskId`, or first.

- **Who:** members with `MANAGE_BACKLOG`, i.e. the **Product Owner**. The workspace must be active.
- **Request:** `{ "afterTaskId": 41 }` or `{ "afterTaskId": null }`.
- **Response `200`:** the moved `TaskResponse`. Reload with `?order=backlog`.
- **Errors:** `400 BAD_REQUEST` "This project has no backlog to reorder; move the task on the board." (Kanban); the `afterTaskId` errors of 3.6 (except the column one); `401`; `403` for everyone but the Product Owner; `404`; `409`.

### How the order is stored

Each task has a `board_position` (its place in its column) and a `backlog_rank` (its place in the backlog). They are **sparse**: 1,000,000 apart. A move takes the midpoint between its two new neighbours, so it updates one row. When two neighbours have no room left between them (about 20 moves into the same gap), the whole column (or backlog) is renumbered once, 1,000,000 apart again. Ties are broken by id, so the order is always stable. Every create and move takes the project row lock, so two people dragging at the same moment cannot get the same place.

---

## 4. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses). Branch on `code`, not on `message`.

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Request validation failed. | A body breaks the field rules. `fieldErrors` names the field, e.g. `{ "title": "must not be blank" }` or `{ "estimate": "must be greater than or equal to 0" }` |
| `BAD_REQUEST` | 400 | Malformed request body. | Not valid JSON, or a wrong value such as `"type": "EPIC"` |
| `BAD_REQUEST` | 400 | (see 3.2, 3.4, 3.6, 3.7) | A filter, status, assignee or neighbour that is not valid for this project |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | A Viewer changing a task; a workspace admin not in the project; anyone but the Product Owner reordering the backlog. Show "You don't have permission". |
| `RESOURCE_NOT_FOUND` | 404 | Project not found. | The project does not exist or the caller cannot see it |
| `RESOURCE_NOT_FOUND` | 404 | Task not found. | Missing, deleted, or another project's task |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Any change while the workspace is archived |

---

## 5. Frontend flows

### 5.1 Task form (create and edit)

1. Assignee picker: `GET /projects/{projectId}/members?q=` — only project members can be assigned.
2. Status picker (edit only): the columns of `GET /projects/{projectId}/board`.
3. Create with `POST …/tasks`; edit with `PATCH …/tasks/{taskId}`, sending only what changed and the `clear*` flags for emptied fields.
4. Show `fieldErrors` of a `400 VALIDATION_ERROR` next to the fields.
5. Hide create, edit, delete and drag controls unless the project's `permissions` contain `EDIT_TASKS` (Viewers do not have it). The backend refuses with `403` either way.

### 5.2 Task list

`GET …/tasks?order=newest` with the filter controls (type, priority, status, assignee, label) and a search box (`q`, title or key). Use `page`/`size` for paging.

### 5.3 Kanban board

1. `GET /projects/{projectId}/board` for the columns.
2. `GET …/tasks?order=board&size=100` (more pages if `totalPages` > 1), or one call per column with `&statusId=`; group by `statusId`.
3. On a drop: `PUT …/tasks/{taskId}/position { statusId, afterTaskId }`, where `afterTaskId` is the card now right above it (`null` at the top). Then reload, or keep your local order.
4. Kanban's first column is "Backlog"; there is no separate backlog page.

### 5.4 Scrum backlog and board

1. Backlog page: `GET …/tasks?order=backlog`. Today every Scrum task is in the backlog (no sprints yet); later only tasks with `sprintId: null`.
2. Drag in the backlog: `PUT …/tasks/{taskId}/backlog-rank { afterTaskId }`, shown only when `permissions` contain `MANAGE_BACKLOG` (the Product Owner).
3. The Scrum board shows the active sprint's tasks (R15). Until sprints exist it has no tasks.

---

## 6. Data model

| Table | Columns | Constraints |
| --- | --- | --- |
| `tasks` | `id`, `project_id`, `task_number`, `task_key` (≤ 21), `title` (≤ 255), `type`, `priority`, `status_column_id`, `assignee_id` (nullable), `estimate` (nullable), `description` (`text`), `board_position`, `backlog_rank`, `deleted_at` (nullable), audit columns | unique on (`project_id`, `task_number`); checks on `type`, `priority`, `estimate >= 0` |
| `task_labels` | `task_id`, `label` (≤ 50) | primary key on both |
| `projects.last_task_number` | the latest task number of the project | starts at 0, only ever increases |

- **Foreign keys:**
  - `project_id` → `projects`, `ON DELETE CASCADE`: deleting a project or workspace deletes its tasks.
  - `status_column_id` → `project_board_columns`, **no cascade**: removing a column (R16) must never delete tasks silently. Deleting a project still works, because Postgres checks this key at the end of the statement, after both the columns and the tasks are gone.
  - `assignee_id` → `users`, `ON DELETE SET NULL`.
  - `task_labels.task_id` → `tasks`, `ON DELETE CASCADE`.
- **Leaving unassigns.** `ProjectService.removeMember` calls `TaskRepository.unassignInProject`, and `WorkspaceService.removeMember` (remove and leave) calls `unassignInWorkspace`. Both are bulk updates, so the tasks' `updated_at` does not change. A new way of leaving a project must do the same.
- **Keys:** `ProjectRepository.incrementTaskNumber` (an UPDATE, which locks the project row) then `findLastTaskNumber`. `Project.lastTaskNumber` is mapped `updatable = false`, so saving a project never writes back a stale number.
- **Soft delete:** `Task` carries `@SQLRestriction("deleted_at is null")`, so no JPA query ever returns a deleted task. **Native SQL must add `deleted_at is null` itself** (universal search does).
- **Indexes:** (`project_id`, `deleted_at`), `status_column_id`, `assignee_id`, (`project_id`, `status_column_id`, `board_position`), (`project_id`, `backlog_rank`).
- **Migration:** `V6__tasks.sql`.
- **Code:** `task/{models,repositories,services,controllers,dto,api}`. `TaskApiService` holds the rules, `TaskSpecifications` the list query, `TaskRanks` the order arithmetic.

---

## 7. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| Every task endpoint | Nothing about projects the caller cannot see: `404`, as for a missing project. |
| `/{taskId}` paths | Not whether a task id exists in another project: same `404` as a missing task. |
| `?statusId=`, `?assigneeId=` | Nothing: an id outside the project finds no tasks. |
| `assigneeId` in a body | Only "is not a member of this project", which a member can already see from the member list. |

---

## 8. Known limitations and later stories

- **No undelete** and no list of deleted tasks.
- **Sprints (R14, R15):** `sprintId` is always `null`; the Scrum board shows no tasks until sprints exist. They will reuse `backlog_rank` and `board_position`.
- **Columns (R16):** adding, renaming, reordering columns and WIP limits are not built. Removing a column will have to move or refuse its tasks (no cascade).
- **Subtasks, comments, history (R10), attachments (R25)** are later stories; their tables must cascade from `tasks`.

---

## 9. Implementation status

As of 26 Oct 2026, on branch `features/project-template-defaults` (Damir: controllers, DTOs, filters; Ahmed: entity, persistence, rules):

| Part | Status |
| --- | --- |
| `Task` entity, `TaskRepository`, `V6__tasks.sql` | Implemented |
| `TaskApiService`: create, list, get, update, delete, move on board, reorder backlog | Implemented |
| `TaskController`: the seven endpoints in §2 | Implemented |
| Unit tests: `TaskApiServiceTest` (24), `TaskControllerTest` (4), `TaskRequestValidationTest` (6), `TaskSearchFiltersTest` (11), `TaskListOrderTest` (4), `TaskRanksTest` (3) | Implemented, all passing |
| Unassigning on leaving a project or the workspace (`ProjectServiceTest`, `WorkspaceServiceTest`) | Implemented, all passing |
| `TaskIntegrationTest` (12) against PostgreSQL: keys, soft delete, defaults, filters, board and backlog order, renumbering, unassigning on leave, cascades | Implemented, all passing |

---

## 10. Decisions

| Date | Decision |
| --- | --- |
| 26 Oct 2026 | Task keys are the project key and a per-project counter; the counter never decreases, so keys are never reused. |
| 26 Oct 2026 | Delete is a soft delete (`deleted_at`), hidden from every JPA read by `@SQLRestriction`. |
| 26 Oct 2026 | Scrum "backlog" means "in no sprint" (status stays a column); Kanban's backlog is its first column. |
| 26 Oct 2026 | Task order is stored now (board position and backlog rank, sparse ranks with renumbering), because the Sprint 2 board needs it. |
| 26 Oct 2026 | Reordering the backlog needs `MANAGE_BACKLOG` (Product Owner), per the roles matrix's strict Scrum roles; a Project Manager adds the Product Owner role to do it. |
| 26 Oct 2026 | Labels: at most 20, each at most 50 characters, collapsed case-insensitively. Description: rich text, no limit. |
| 26 Oct 2026 | Someone who leaves a project (or the workspace) is unassigned from its tasks, so the assignee is always a project member. |
