# Sprintory — Tasks API contract (R09)

**Status:** tasks with keys, story 9 fields, soft delete, filters/search and board/backlog ordering. PMP-125 adds status-change history and soft Kanban WIP warnings to task moves. PMP-131 adds task restoration and PMP-132 one-level subtasks with completion confirmation (§10). PMP-133/PMP-136 (4 Nov 2026) turn the history into a full activity feed ([task-history-docs.md](./task-history-docs.md)), add comments ([comments-docs.md](./comments-docs.md)), and make the description TipTap JSON with @mentions ([mentions-docs.md](./mentions-docs.md)); the history and comments cover subtasks too. Sprint assignment remains a separate story. See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/projects/{projectId}/tasks/**`. Comments and history have their own contracts (linked above).
**Related:** [Projects](./projects-docs.md) (keys, board, columns), [Board configuration](./board-docs.md), [Task history](./task-history-docs.md), [Comments](./comments-docs.md), [Mentions and rich text](./mentions-docs.md), [Roles and permissions](./roles-docs.md), [Universal search](./search-docs.md), [Workspaces](./workspaces-docs.md) (archiving, pagination), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Audit log](./audit-log-docs.md), [Requirements](./requirements.md) R09, R13, R16.
**Audit log:** deleting or restoring a task writes an entry to the workspace's audit log ([audit-log-docs.md §4.4](./audit-log-docs.md#4-action-catalogue)). Other task edits are not audit-logged; they belong to the task's own history.

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
  | Description | Optional rich text: a string holding a **TipTap JSON document**, checked and normalized by the backend, and able to @mention people and teams ([mentions-docs.md §2](./mentions-docs.md#2-rich-text-format)). No length limit. A document with no text is stored as no description. |

- **New tasks start in the backlog.** What "the backlog" means depends on the template:
  - **Scrum:** a task is in the backlog when it is **in no sprint**. Its status is still a column (the first one, To Do). The Scrum board shows only the active sprint's tasks (R15), so a backlog task is not on the board until the Scrum Master pulls it into a sprint (R14) and starts the sprint. Until sprints exist, every Scrum task is in the backlog and `sprintId` is always `null`.
  - **Kanban:** there are no sprints, so the backlog is the first **column**, "Backlog". A new task is in that column.
- **Deleting is a soft delete.** The task disappears from the board, the backlog, every list and search, and returns `404`, but its row stays in the database. Its key is not given out again. `POST /{taskId}/restore` restores it, with permission checks and audit logging. Deleting a parent also soft-deletes its live subtasks; restoring it only restores children deleted by that cascade.
- **Order.** Each task has a place in its board column and a place in the backlog. New tasks go last in both. Drag and drop uses two endpoints (3.6, 3.7); lists can be read in board or backlog order (`?order=`, §3.2).
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Anyone who may not see the project gets `404`.
- **Tasks are project work** (story 7). A workspace admin who is **not** a member of the project gets `403` on every task endpoint, reads included.
- **Archived workspaces are read-only.** Tasks can still be read; every change gets `409 WORKSPACE_ARCHIVED`.

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Member/Developer, Scrum Master | Product Owner | Project Manager |
| --- | --- | --- | --- | --- | --- | --- |
| Read, list, search tasks and their history | 404 | 403 | allowed | allowed | allowed | allowed |
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
| 3.8 | GET | `/{taskId}/history?page=&size=` | none | `200` + `PageResponse<TaskHistoryEntryResponse>` | `VIEW_WORK` |
| 3.9 | POST | `/{taskId}/restore` | none | `200 TaskResponse` | `EDIT_TASKS` |
| 3.10 | POST | `/{parentId}/subtasks` | `CreateSubtaskRequest` | `201 TaskResponse` | `EDIT_TASKS` |
| 3.11 | GET | `/{parentId}/subtasks` | none | `200 TaskResponse[]` | `VIEW_WORK` |
| 3.12 | PATCH | `/{parentId}/subtasks/{subtaskId}` | `UpdateSubtaskRequest` | `200 TaskResponse` | `EDIT_TASKS` |
| — | GET, POST, PATCH, DELETE | `/{taskId}/comments/**` | see [comments-docs.md](./comments-docs.md) | | `VIEW_WORK`, `COMMENT`, `MODERATE_COMMENTS` |

A `{taskId}` is always looked up **inside** `{projectId}`: a task of another project gives the same `404` as a missing one.

### Bodies

```jsonc
// CreateTaskRequest: only "title" is required
{ "title": "Login fails on Safari", "type": "BUG", "priority": "HIGH", "assigneeId": 7,
  "estimate": 3, "labels": ["backend", "R09"],
  "description": "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Steps to reproduce: ...\"}]}]}" }

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
  "description": "{\"type\":\"doc\",\"content\":[...]}", "sprintId": null,
  "createdAt": "2026-10-26T09:30:00Z", "updatedAt": "2026-10-26T09:30:00Z",
  "wipWarning": null,
  "parentTaskId": null, "estimateMinutes": null,
  "subtaskTotal": 0, "subtaskDone": 0 }
```

- `labels` come back sorted. Labels are trimmed, and duplicates that differ only in case collapse into the first spelling (`["backend", "Backend"]` stores `backend`).
- `assigneeId` is a user id; show the name from `GET /projects/{projectId}/members`.
- `description` is a string holding TipTap JSON (`JSON.stringify(editor.getJSON())`). The response returns the backend's normalized copy. Descriptions saved before 4 Nov 2026 may still be plain text; see [mentions-docs.md §2.6](./mentions-docs.md#26-task-descriptions).
- `sprintId` stays `null` until sprints exist (R14). Parent-to-child sprint inheritance will be integrated with the future sprint backend; the API does not yet accept `sprintId`.
- Top-level responses include `subtaskTotal` and `subtaskDone` (zero until children exist); child responses have `parentTaskId`, `estimateMinutes`, and `estimate: null` (no story points). Children do not appear as separate board cards or in the main project task list.
- `confirmOpenSubtasks` is an optional boolean on `PATCH /{taskId}` and `PUT /{taskId}/position`. A transition of a parent with open subtasks to a `DONE`-category column returns `409 OPEN_SUBTASKS_CONFIRMATION_REQUIRED` unless explicitly confirmed.
- The board positions and backlog ranks are internal and never sent; lists come back already sorted (§3.2).
- `wipWarning` is `null` normally. On a move into a Kanban column above its WIP limit, the *move succeeds* and `wipWarning` contains `{ "columnId", "count", "limit", "message" }`. See [board-docs §3.6](./board-docs.md#36-soft-wip-warning-on-task-move).

---

## 3. Endpoints in detail

### 3.1 Create a task

`POST /api/v1/projects/{projectId}/tasks`

- **Who:** members with `EDIT_TASKS` (everyone but Viewers). The workspace must be active.
- **Result:** the next key; `type` `TASK` and `priority` `MEDIUM` when left out; status = the board's first column (To Do in Scrum, Backlog in Kanban); last in that column and last in the backlog; no sprint.
- **Side effects:** a `CREATED` history entry ([task-history-docs.md](./task-history-docs.md)); everyone @mentioned in the description gets `MENTIONED` ([mentions-docs.md §4](./mentions-docs.md#4-rules)).
- **Response `201`:** `TaskResponse`.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `title` missing, blank or over 255; `estimate` negative; `assigneeId` not positive; more than 20 labels, or a label blank or over 50 |
  | 400 | `BAD_REQUEST` "Malformed request body." | e.g. `"type": "EPIC"` or `"estimate": "three"` |
  | 400 | `BAD_REQUEST` "The assignee must be a member of this project." | `assigneeId` is not a project member |
  | 400 | `INVALID_RICH_TEXT` | `description` is not a valid TipTap document ([mentions-docs.md §2](./mentions-docs.md#2-rich-text-format)), e.g. plain text |
  | 400 | `INVALID_MENTION` | the description mentions someone outside the project, or a team with no member in it |
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
  - To empty a field, send its flag: `clearAssignee`, `clearEstimate`, `clearDescription` (`true`). The flags are optional; left out or `null` means `false`. Sending a value **and** its clear flag is a `400`.
  - `labels` replaces all labels; `"labels": []` removes them all.
  - `statusId` must be an active column of this project's board. Changing it puts the task **last** in the new column. To drop it at a particular place, use 3.6 instead. A changed status can return a nonblocking Kanban `wipWarning`.
  - `assigneeId` must be a project member.
  - `description` must be TipTap JSON. Only **newly added** mentions are checked and notified ([mentions-docs.md §4](./mentions-docs.md#4-rules)).
  - The key, the project and the sprint cannot be changed here.
  - **History:** every field that really changes writes one `FIELD_CHANGED` entry (§3.8); a description change is recorded without its text.
- **Response `200`:** the updated `TaskResponse`.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `title` blank or over 255; `estimate` negative; ids not positive; label limits; a value together with its clear flag |
  | 400 | `BAD_REQUEST` "The status must be a column of this project's board." | `statusId` of another project, or no such column |
  | 400 | `BAD_REQUEST` "The assignee must be a member of this project." | `assigneeId` is not a project member |
  | 400 | `INVALID_RICH_TEXT`, `INVALID_MENTION` | as in 3.1, for the new description and its newly added mentions |
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
- **Response `200`:** the moved `TaskResponse`. Reload the board with `?order=board` to show everyone's current order. Moving across columns records one `STATUS` history entry (§3.8); moving within a column does not. Kanban WIP limits never block a valid move: over-limit destinations return `wipWarning`, and `GET /board` exposes the persistent `wipExceeded` flag. See [board-docs §3.6](./board-docs.md#36-soft-wip-warning-on-task-move).
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

### 3.8 Read a task's history

`GET /api/v1/projects/{projectId}/tasks/{taskId}/history?page=&size=`

- **Who:** project members with `VIEW_WORK`, including Viewers. Workspace admins not in the project cannot read tasks or task history.
- **Response `200`:** `PageResponse<TaskHistoryEntryResponse>`, oldest first: creation, every field change (old → new), status moves, deleted comments and subtasks.
- **Errors:** `401`, `403`, `404` as in §3.3.
- **The full contract is [task-history-docs.md](./task-history-docs.md).** Until 4 Nov 2026 this endpoint returned a plain array of status moves; see its §2.

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
| `INVALID_RICH_TEXT` | 400 | (see [mentions-docs.md §5](./mentions-docs.md#5-errors)) | `description` is not a valid TipTap document |
| `INVALID_MENTION` | 400 | Only members of this project can be mentioned. | The description newly mentions someone outside the project |
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
3. Description: the shared TipTap editor with @mentions ([mentions-docs.md §6](./mentions-docs.md#6-frontend-flows-pmp-138)); send `JSON.stringify(editor.getJSON())`.
4. Create with `POST …/tasks`; edit with `PATCH …/tasks/{taskId}`, sending only what changed and the `clear*` flags for emptied fields.
5. Show `fieldErrors` of a `400 VALIDATION_ERROR` next to the fields, and the message of `INVALID_RICH_TEXT` / `INVALID_MENTION` under the description.
6. Hide create, edit, delete and drag controls unless the project's `permissions` contain `EDIT_TASKS` (Viewers do not have it). The backend refuses with `403` either way.

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
| `task_history` | `task_id`, `type`, `field`, `old_value`, `new_value`, `ref_id`, `actor_id`, `actor_name`, `occurred_at` | append-only activity feed, created by V11, which replaced V7's `task_status_history`; `task_id` has `ON DELETE CASCADE`. See [task-history-docs.md §6](./task-history-docs.md#6-data-model) |
| `task_comments` | see [comments-docs.md §6](./comments-docs.md#6-data-model) | `task_id` has `ON DELETE CASCADE` |
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
- **Migrations:** `V6__tasks.sql` (task storage), `V7__board_configuration_and_status_history.sql` (status history and board configuration), `V10__subtasks.sql` (PMP-131/132, Damir), `V11__task_history.sql` (full history, replaces the V7 table), `V12__task_comments.sql` (comments).
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
- **Columns (R16 / PMP-125):** board configuration and soft WIP warnings are implemented in the backend. See [board-docs.md](./board-docs.md).
- **Subtasks (R10, PMP-132) and attachments (R25)** are later stories. Comments and the full activity history (R10) are PMP-133 ([comments-docs.md](./comments-docs.md), [task-history-docs.md](./task-history-docs.md)). Future dependent tables must cascade from `tasks`.

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
| PMP-125 status history and soft WIP responses | Implemented; clean verify passed 381 tests and V7 applied on PostgreSQL Testcontainers (1 Nov 2026); final regression re-run and frontend acceptance pending |
| PMP-133/PMP-136: full history, comments, TipTap descriptions with mentions | Contract written (4 Nov 2026), not started. The history and comment tables are built after PMP-131/132 is merged into `main` |

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
| 1 Nov 2026 | A task status change records from/to column snapshots and actor; over-limit Kanban task moves succeed with a warning, never a validation failure. |
| 4 Nov 2026 | The description must be TipTap JSON, checked and normalized by the backend; mentions in it notify. Supersedes "stored as sent" of 26 Oct. |
| 4 Nov 2026 | History covers every field change, not only status, in one `task_history` table ([task-history-docs.md §10](./task-history-docs.md#10-decisions)). |


---

## 10. Task restoration (PMP-131) and one-level subtasks (PMP-132)

**Release:** migration `V9__subtasks.sql` on top of V8 audit infrastructure. No earlier migration is changed. For development with Flyway disabled, use a clean Flyway-managed test database; Hibernate auto-update is not a migration strategy.

### 10.1 Restore a task

`POST /api/v1/projects/{projectId}/tasks/{taskId}/restore` (no body)

- **Permission:** `EDIT_TASKS`, with an active workspace. Non-members see `404`; Viewers see `403`; archived workspace changes see `409`.
- **Success:** `200 TaskResponse`, same immutable `key`. The key counter is never decremented or reused. Workspace audit logs record one `TASK_RESTORED` entry; a failed or repeated restore produces none.
- **Missing, live (not deleted), other-project, or child whose parent is still deleted:** `404 RESOURCE_NOT_FOUND` with `Task not found.`. No separate deleted-record listing endpoint is exposed.
- **Original column was subsequently removed:** the task is restored into the first *active* project column rather than linking to a soft-deleted column.
- **Parent restoration:** also restores only children that were live when the parent was deleted. A child deleted separately *before* its parent's deletion remains deleted; it can be restored independently after the parent is live again.
- **Subtasks:** the same restore endpoint works on individually deleted subtasks; restoring a subtask while its parent is deleted returns `404`.

### 10.2 Subtask endpoints

A subtask belongs to exactly one top-level task of the *same project*. It has its own generated key, title, assignee, board-column status and optional time estimate **in minutes**. It has **no story points**, and cannot have children. Only existing project members may be assigned.

**Create:** `POST /api/v1/projects/{projectId}/tasks/{parentId}/subtasks` (`201`)

```json
{ "title": "Write integration tests", "assigneeId": 17, "statusId": 11, "estimateMinutes": 90 }
```

Only `title` is required. `statusId` omitted means the project's first active board column. The subtask's key comes from the *same* per-project sequence as top-level tasks. Reject blank or overlong titles, non-positive ids/minutes, non-member assignees and a parent that is itself a subtask. Unknown or cross-project parents return `404`; attempting a second subtask level returns `400 BAD_REQUEST`.

**List:** `GET /api/v1/projects/{projectId}/tasks/{parentId}/subtasks` (`200 TaskResponse[]`), sorted by creation id oldest first. This is the **only dedicated subtask list**; the main `GET /projects/{projectId}/tasks` excludes children so they never become separate board cards, backlog items, or WIP counts. A parent response reports `subtaskDone` / `subtaskTotal` (for example `3 / 5`). The count is based on board-column `DONE` **category**, not its editable name.

**Edit:** `PATCH /api/v1/projects/{projectId}/tasks/{parentId}/subtasks/{subtaskId}` (`200`)

```json
{ "title": "Write backend tests", "statusId": 14, "estimateMinutes": 60 }
```

Allowed fields: `title`, `statusId`, `assigneeId`, `estimateMinutes`, `clearAssignee`, `clearEstimateMinutes` (booleans, optional). Status changes create the existing `task_status_history` event. `estimateMinutes` must be a positive integer; clearing sets it to `null`. The parent id and subtask id must match and belong to the project, otherwise `404`. The regular task PATCH endpoint is deliberately **not** an alternative for subtasks because its `estimate` means story points.

**Delete:** use the existing `DELETE /api/v1/projects/{projectId}/tasks/{subtaskId}` (`204`). The child is independently soft-deleted; `subtaskTotal` decreases accordingly. Restoration uses §10.1.

**Permissions:** `VIEW_WORK` for reading; `EDIT_TASKS` for create/edit/delete/restore. Viewer may read but never mutate. Non-project-members get `404` even if they belong to the workspace. A workspace admin outside the project still cannot read or edit project tasks.

### 10.3 Parent completion confirmation

When a top-level task has one or more live subtasks in columns other than category `DONE`, a transition **into DONE** is conditional. The server rejects an unconfirmed transition with HTTP `409`:

```json
{ "code": "OPEN_SUBTASKS_CONFIRMATION_REQUIRED",
  "message": "2 subtasks are not Done. Retry with confirmOpenSubtasks=true to continue." }
```

The frontend shows a warning, allows explicit confirmation, and retries the *same* request with `"confirmOpenSubtasks": true`. This applies to both `PATCH /{taskId}` (when changing `statusId`) and `PUT /{taskId}/position`. No need to reconfirm for reordering within a DONE column. There is **no hard block** once confirmation is provided. A status change for a child never needs this confirmation because nesting beyond one level is forbidden.

### 10.4 Other subsystems, current boundaries

- **Kanban WIP:** only top-level tasks count; subtasks never increase column task counters or WIP warnings. Parent tasks remain in their own status columns. Column deletion still rehomes *both* active parents and active children; individual status-history entries are recorded.
- **Sprint assignment:** `sprintId` remains `null` because the sprint domain/API is not yet implemented. When the R14 sprint feature ships, it must move all children with their parent and expose the inherited `sprintId`. This requirement cannot be fully acceptance-tested yet; do not simulate a second sprint model here.
- **Task history:** existing `GET /{taskId}/history` currently returns **status-change history only**, including child column changes. Ahmed owns the expanded comment/field/subtask activity feed (PMP-133), so field-change and subtask added/deleted events must be coordinated there, not invented as an incompatible duplicate log. The workspace audit log still records explicit task delete/restore actions.
- **No frontend modifications** in PMP-131/PMP-132. Mobeen's PMP-134 must integrate these endpoints after backend review and merge.

### 10.5 Test expectations

The full suite is `bash ./mvnw clean verify` (Docker required for PostgreSQL Testcontainers). New integration tests cover restoration including a removed column, uniqueness of keys, cascade versus manual deletion, parent progress, distinct estimate units, board/WIP exclusion, DONE confirmation, column rehoming history and role-based access checks. Validate against a Flyway-created schema at V9. **Do not mark these tests as passing until they run in the project's Java/Maven environment.**
