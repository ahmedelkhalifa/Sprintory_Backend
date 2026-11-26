# Sprintory — Milestones API contract (R12, PMP-46)

**Status:** contract written before the code (26 Nov 2026), so the frontend (PMP-151, Amir) can start now. Backend: PMP-150, branch `features/milestones`, not started yet. Acceptance testing: PMP-152 (Aliaksandr). See [§11 Implementation status](#11-implementation-status).
**Applies to:** backend `/api/v1/projects/{projectId}/milestones`, `/api/v1/milestones/**`, `/api/v1/tasks/{taskId}/milestone`, the `milestone` field of `TaskResponse`, and the `milestoneId` / `noMilestone` task list filters.
**Related:** [Tasks](./tasks-docs.md), [Task history](./task-history-docs.md), [Audit log](./audit-log-docs.md), [Roles and permissions](./roles-docs.md), [Project charter](./project-charter-docs.md) (the Overview page), [Board](./board-docs.md) (column categories), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R12.

This document is the single reference for milestones. The backend implements it, the frontend builds the milestone screens against it, and acceptance testing checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a project manager or Product Owner, I want to define milestones and link tasks to them, so that I can track progress toward key dates."*

- **A milestone is a checkpoint with a target date**, such as "Beta release" or "Client demo". It has no duration and no work of its own. It groups tasks and answers one question: how close are we to this date? A sprint is a time-box for the team. A milestone is a business date that can span several sprints.
- **Scrum and Kanban.** Milestones depend on dates, not on sprints.
- **0 to 50 milestones per project.** Each has a name, a target date and an optional description.
- **A task has at most one milestone.** Only **top-level** tasks (`STORY`, `TASK`, `BUG`) can be linked. Subtasks follow their parent and are never counted separately. Tasks in the backlog, in any sprint and in a `DONE` column can all be linked.
- **Progress and status are calculated on every request and never stored**, so they follow task moves, deletes, restores and column changes immediately ([§4](#4-progress-and-status)).
- **Deleting a milestone** is a soft delete that **unlinks its tasks**. It can be restored, but restoring does **not** relink the tasks ([§3.5](#35-delete-a-milestone), [§3.6](#36-restore-a-milestone)).
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Someone who cannot see the project gets `404`.
- **Read-only states:** in an archived workspace (`409 WORKSPACE_ARCHIVED`) or a `COMPLETED` / `ARCHIVED` project (`409 CONFLICT`), milestones and links can be read but not changed.
- **Out of scope:** milestone dependencies, automatic date shifting, recurring milestones, overdue reminders or notifications, calendar (iCal) entries, cross-project milestones. Milestone diamonds on the Gantt chart (PMP-48, Damir) and milestone progress in reports (PMP-55) are built in those stories and read the same data.

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Member/Developer, Scrum Master | Product Owner, Project Manager |
| --- | --- | --- | --- | --- | --- |
| List and read milestones | 404 | 403 | allowed | allowed | allowed |
| Create, edit, delete, restore a milestone | 404 | 403 | 403 | 403 | allowed |
| Link and unlink tasks (task field or "Add tasks") | 404 | 403 | 403 | allowed | allowed |

- Two new permissions ([roles-docs.md §2](./roles-docs.md#2-permission-matrix)):
  - **`MILESTONE_MANAGE`**: Product Owner, Project Manager. Create, edit, delete and restore milestones.
  - **`MILESTONE_LINK`**: every role except Viewer. Link and unlink tasks.
- Reading needs `VIEW_WORK` (every project role).
- The project response's `permissions` list contains these names. The `canManage` / `canLink` flags in the milestone responses ([§2](#bodies)) also take the read-only states into account, so prefer them for showing or hiding buttons. The backend checks again on every request.
- Workspace admins do **not** get these permissions through their workspace role. They need a project role.

---

## 2. Endpoints at a glance

All paths are under `/api/v1`. Bodies are JSON in `camelCase`. Every endpoint needs the access-token cookie. Dates are `YYYY-MM-DD` strings with no time.

| # | Method | Path | Request | Success | Permission |
| --- | --- | --- | --- | --- | --- |
| 3.1 | GET | `/projects/{projectId}/milestones` | none | `200` + `MilestoneListResponse` | `VIEW_WORK` |
| 3.2 | POST | `/projects/{projectId}/milestones` | `MilestoneRequest` | `201` + `MilestoneResponse` | `MILESTONE_MANAGE` |
| 3.3 | GET | `/milestones/{milestoneId}` | none | `200` + `MilestoneResponse` | `VIEW_WORK` |
| 3.4 | PUT | `/milestones/{milestoneId}` | `MilestoneRequest` | `200` + `MilestoneResponse` | `MILESTONE_MANAGE` |
| 3.5 | DELETE | `/milestones/{milestoneId}` | none | `204` | `MILESTONE_MANAGE` |
| 3.6 | POST | `/milestones/{milestoneId}/restore` | none | `200` + `MilestoneResponse` | `MILESTONE_MANAGE` |
| 3.7 | POST | `/milestones/{milestoneId}/tasks` | `MilestoneTasksRequest` | `200` + `MilestoneResponse` | `MILESTONE_LINK` |
| 3.8 | PUT | `/tasks/{taskId}/milestone` | `TaskMilestoneRequest` | `200` + `TaskResponse` | `MILESTONE_LINK` |

Also changed:

| Endpoint | Change |
| --- | --- |
| `TaskResponse` (every task endpoint: list, get, create, edit, move, board, backlog) | new `milestone`: `{ id, name, targetDate }` or `null` ([§5](#5-changes-to-tasks)) |
| `GET /projects/{projectId}/tasks` | new filters `milestoneId` and `noMilestone=true` ([§5](#5-changes-to-tasks)) |
| `GET /projects/{projectId}/tasks/{taskId}/history` | new field `MILESTONE` in `FIELD_CHANGED` entries ([§6](#6-task-history-and-audit-log)) |
| `GET /workspaces/{workspaceId}/audit-log` | new actions `MILESTONE_CREATED`, `MILESTONE_UPDATED`, `MILESTONE_DELETED`, `MILESTONE_RESTORED`, and the target type `MILESTONE` ([§6](#6-task-history-and-audit-log)) |

There is no endpoint that lists a milestone's tasks: the milestone page uses the task list with `?milestoneId=` ([§7.2](#72-milestone-page)). There is no list of deleted milestones.

### Bodies

```jsonc
// MilestoneRequest (create and edit; edit replaces all three fields)
{
  "name": "Beta release",            // required, 1–100 characters after trimming, unique in the project ignoring case
  "targetDate": "2026-12-15",        // required; may be in the past
  "description": "Feature-complete build for the pilot customers."   // optional plain text, max 500; null or blank = none
}

// MilestoneTasksRequest ("Add tasks" on the milestone page)
{ "taskIds": [55, 56, 61] }          // required, 1–100 task ids; duplicates are ignored

// TaskMilestoneRequest (Milestone field on the task page)
{ "milestoneId": 4 }                 // a milestone of the task's project; null (or omitted) = unlink

// MilestoneListResponse
{
  "milestones": [ /* MilestoneResponse, by targetDate, then name (ignoring case), then id */ ],
  "canManage": true,                 // the caller may create, edit, delete and restore now
  "canLink": true,                   // the caller may link and unlink tasks now
  "limit": 50                        // the maximum per project; disable "Add milestone" when reached
}

// MilestoneResponse
{
  "id": 4,
  "projectId": 15,
  "name": "Beta release",
  "description": "Feature-complete build for the pilot customers.",   // null when none
  "targetDate": "2026-12-15",
  "status": "UPCOMING",              // EMPTY | UPCOMING | OVERDUE | REACHED (§4)
  "progress": {
    "totalTasks": 9,
    "doneTasks": 6,
    "totalPoints": 20,               // story points of all linked tasks; unestimated tasks count 0
    "donePoints": 12,
    "basis": "POINTS",               // POINTS when any linked task has story points, else TASKS
    "percent": 60                    // 0–100, rounded down, on the basis; null when status is EMPTY
  },
  "createdAt": "2026-11-26T10:02:11Z",
  "createdBy": { "id": 9, "name": "Linus Torvalds" },   // null if the account no longer exists
  "canManage": true,
  "canLink": true
}
```

- `canManage` is `true` when the caller has `MILESTONE_MANAGE`, the workspace is not archived and the project is `ACTIVE`. `canLink` is the same with `MILESTONE_LINK`.
- Show progress as **"12 / 20 pts (6 / 9 tasks)"**. When `basis` is `TASKS`, show only "6 / 9 tasks".
- **Done** means a task in a column whose category is `DONE`, never a column name ([board-docs.md](./board-docs.md)).
- Milestone ids are looked up inside the milestone's project. An id from a project the caller cannot see gives the same `404` as a missing one.

---

## 3. Endpoints in detail

### 3.1 List a project's milestones

`GET /api/v1/projects/{projectId}/milestones`

- **Who:** `VIEW_WORK`. Allowed in archived workspaces and completed or archived projects.
- **Contents:** every live (not deleted) milestone of the project, each with its current status and progress. Sorted by `targetDate`, then `name` ignoring case, then `id`. No paging: a project has at most 50.
- The progress of all milestones is read in **one query**, not one per milestone.
- **Errors:** `401`; `403` (a workspace admin not in the project); `404` "Project not found.".

### 3.2 Create a milestone

`POST /api/v1/projects/{projectId}/milestones`

- **Who:** `MILESTONE_MANAGE` (Product Owner, Project Manager). Workspace active, project `ACTIVE`.
- **Rules:**
  - `name` is required and trimmed, 1–100 characters. No other **live** milestone of the project may have the same name, ignoring case ("beta release" = "Beta Release"). The name of a deleted milestone can be used again.
  - `targetDate` is required. A past date is allowed; the milestone is then `OVERDUE` as soon as it has open tasks.
  - `description` is optional plain text, at most 500 characters. A blank one is stored as `null`. It is not rich text: render it as text, never as HTML.
  - At most **50** live milestones per project. The 51st gives `409 MILESTONE_LIMIT_REACHED`.
  - The name and limit checks run under the project row lock, so two requests at once cannot both pass.
- **Side effects:** an audit entry `MILESTONE_CREATED` ([§6](#6-task-history-and-audit-log)).
- **Response `201`:** `MilestoneResponse`, status `EMPTY`.
- **Errors:** `400 VALIDATION_ERROR` (`name` missing, blank or over 100; `targetDate` missing; `description` over 500); `400 BAD_REQUEST` "Malformed request body." (e.g. `"targetDate": "15.12.2026"`); `401`; `403`; `404` "Project not found."; `409 MILESTONE_NAME_TAKEN`; `409 MILESTONE_LIMIT_REACHED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT` (project completed or archived).

### 3.3 Read a milestone

`GET /api/v1/milestones/{milestoneId}`

- **Who:** `VIEW_WORK`. Allowed in read-only states.
- **Response `200`:** `MilestoneResponse`. Its tasks come from the task list ([§7.2](#72-milestone-page)).
- **Errors:** `401`; `403` (a workspace admin not in the project); `404` "Milestone not found." (missing, deleted, or a project the caller cannot see).

### 3.4 Edit a milestone

`PUT /api/v1/milestones/{milestoneId}`

- **Who:** `MILESTONE_MANAGE`. Workspace active, project `ACTIVE`.
- **Full replace:** send all three fields. A `description` left out or `null` removes it.
- **Rules:** as in 3.2. Keeping the same name, or changing only its case ("beta" → "Beta"), is allowed.
- **Side effects:** an audit entry `MILESTONE_UPDATED` listing only the fields that actually changed. A save that changes nothing writes no entry. Linked tasks keep their link, and their `milestone` label shows the new name and date on the next read. Task history is not touched: it keeps the name a milestone had when a task was linked.
- **Response `200`:** the updated `MilestoneResponse`.
- **Errors:** as in 3.2, plus `404` "Milestone not found.", and without the limit.

### 3.5 Delete a milestone

`DELETE /api/v1/milestones/{milestoneId}`

- **Who:** `MILESTONE_MANAGE`. Workspace active, project `ACTIVE`.
- **Effect, in one transaction:**
  - the milestone is soft-deleted and disappears from every list, the task field dropdown and the task labels;
  - **every task linked to it is unlinked** (`milestone` becomes `null`). This includes deleted tasks, so a task restored later never points at a deleted milestone;
  - every unlinked task that is not deleted gets a history entry `MILESTONE`: name → `null`;
  - an audit entry `MILESTONE_DELETED` with the number of unlinked tasks.
- **Confirmation:** the dialog says "N tasks will be unlinked." Take N from `progress.totalTasks` of the milestone already on screen. Deleted tasks are not counted there; they are unlinked silently.
- **Response:** `204`. Deleting it again gives `404` "Milestone not found.".
- **Errors:** `401`; `403`; `404`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

### 3.6 Restore a milestone

`POST /api/v1/milestones/{milestoneId}/restore`

- **Who:** `MILESTONE_MANAGE`. Workspace active, project `ACTIVE`.
- **Use:** the "Undo" action of the toast shown after a delete ([§7.1](#71-milestones-section-on-the-project-overview)). There is no list of deleted milestones, so the id comes from that delete.
- **Effect:** the milestone is live again with its name, date and description. **Its former tasks are not relinked** (story rule); link them again with "Add tasks". An audit entry `MILESTONE_RESTORED`.
- **Rules:** if a live milestone has taken its name meanwhile, `409 MILESTONE_NAME_TAKEN`. If the project already has 50 live milestones, `409 MILESTONE_LIMIT_REACHED`.
- **Response `200`:** `MilestoneResponse`, status `EMPTY`.
- **Errors:** `401`; `403`; `404` "Milestone not found." (missing, **not deleted**, or a project the caller cannot see); `409 MILESTONE_NAME_TAKEN`; `409 MILESTONE_LIMIT_REACHED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

### 3.7 Link several tasks ("Add tasks")

`POST /api/v1/milestones/{milestoneId}/tasks`

- **Who:** `MILESTONE_LINK` (everyone but Viewers). Workspace active, project `ACTIVE`.
- **Rules:**
  - `taskIds`: 1–100 ids. Duplicates are ignored.
  - **All or nothing.** Every id must be a live **top-level** task of the milestone's project. If one is not (a subtask, a deleted or missing task, or a task of another project), nothing is linked and the answer is `400 BAD_REQUEST` "Only live top-level tasks of this project can be linked.".
  - A task already linked to **another** milestone is moved here. Its history records the old name → the new name.
  - A task already linked to **this** milestone is skipped: no change, no history entry.
- **Side effects:** one history entry `MILESTONE` per task that actually changed ([§6](#6-task-history-and-audit-log)). Linking writes no audit entry.
- **Response `200`:** the milestone's updated `MilestoneResponse`. Then reload the milestone page's task list.
- **Errors:** `400 VALIDATION_ERROR` (`taskIds` missing, empty, over 100, or containing `null`); `400 BAD_REQUEST`; `401`; `403`; `404` "Milestone not found."; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

### 3.8 Set or clear a task's milestone (task page)

`PUT /api/v1/tasks/{taskId}/milestone`

- **Who:** `MILESTONE_LINK` in the task's project. Workspace active, project `ACTIVE`.
- **Rules:**
  - The task must be a live **top-level** task. A subtask gives `400 BAD_REQUEST` "Only top-level tasks can be linked to a milestone.".
  - `milestoneId` must be a live milestone of the **task's** project. A missing, deleted or other-project milestone gives `400 BAD_REQUEST` "The milestone does not belong to this project.".
  - `milestoneId: null` (or left out) unlinks the task.
  - Choosing the milestone the task already has, or unlinking a task that has none, changes nothing: no history entry, response `200`.
- **Side effects:** a history entry `MILESTONE`, old name → new name (`null` = none). So A → B, `null` → A and A → `null` are each one entry.
- **Response `200`:** the updated `TaskResponse`, with `milestone` set.
- **Errors:** `400 BAD_REQUEST`; `401`; `403` (a Viewer, or a workspace admin not in the project); `404` "Task not found." (missing, deleted, or a project the caller cannot see); `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

---

## 4. Progress and status

Calculated on every request from the linked tasks that are **top-level and not deleted**. Nothing is stored, so:

- a task moved into or out of a `DONE` column changes progress on the next read, without any manual step;
- a deleted task stops counting, and counts again after it is restored (it keeps its link while deleted, unless the milestone was deleted meanwhile);
- renaming a column changes nothing, because "done" is the column's **category**.

**Progress**

| Field | Meaning |
| --- | --- |
| `totalTasks` / `doneTasks` | linked tasks / those in a `DONE`-category column |
| `totalPoints` / `donePoints` | sum of their story points (`estimate`); a task without an estimate counts 0 |
| `basis` | `POINTS` when `totalPoints > 0`, otherwise `TASKS` |
| `percent` | `donePoints * 100 / totalPoints` (POINTS) or `doneTasks * 100 / totalTasks` (TASKS), rounded down; `null` when `totalTasks` is 0 |

Example (criterion 15): tasks of 5, 3 and 2 points, the 5-point one done → `totalTasks 3, doneTasks 1, totalPoints 10, donePoints 5, basis POINTS, percent 50`, shown as "5 / 10 pts (1 / 3 tasks)".

**Status**, checked in this order. "Today" is the current date in the **server's time zone** (workspaces have no time zone of their own).

| Status | When | Show |
| --- | --- | --- |
| `EMPTY` | no linked tasks | "No tasks yet", no percentage (never 100%) |
| `REACHED` | every linked task is done | "Reached" |
| `OVERDUE` | some linked task is not done and `targetDate` is before today | "Overdue" |
| `UPCOMING` | some linked task is not done and `targetDate` is today or later | "Upcoming" |

- `REACHED` is not permanent: linking an open task, or moving a task out of `DONE`, turns it back into `UPCOMING` or `OVERDUE`.
- The status counts **tasks**, not points. With `basis: POINTS`, a milestone can show `percent: 100` while an unestimated task is still open; its status is then `UPCOMING` or `OVERDUE`, not `REACHED`.
- The rule is one pure function, `MilestoneStatus.of(today, targetDate, doneTasks, totalTasks)`, unit-tested with the story's examples.

---

## 5. Changes to tasks

**`TaskResponse.milestone`** ([tasks-docs.md §2](./tasks-docs.md#bodies)), on every task endpoint:

```jsonc
"milestone": { "id": 4, "name": "Beta release", "targetDate": "2026-12-15" }   // or null
```

- A **subtask** shows its **parent's** milestone (read-only), the same way `sprintId` works. A subtask cannot be linked on its own.
- Use it for the label on the task page, board cards and backlog rows, with the date in a tooltip. No extra call is needed.

**Task list filters** (`GET /projects/{projectId}/tasks`, [tasks-docs.md §3.2](./tasks-docs.md#32-list-and-search-a-projects-tasks)):

| Parameter | Matches |
| --- | --- |
| `milestoneId` | tasks linked to that milestone. An id of another project or a deleted milestone simply finds nothing. |
| `noMilestone=true` | tasks with no milestone |

- Both together give `400 BAD_REQUEST` "Choose milestoneId or noMilestone, not both.". A `milestoneId` that is not positive gives `400` "'milestoneId' must be a positive ID.".
- They combine with every other filter and `order`, and run before paging, like `sprintId` / `noSprint`.

The task PATCH (`PATCH /projects/{projectId}/tasks/{taskId}`) does **not** take a milestone. Linking has its own endpoint (3.8) because it needs `MILESTONE_LINK`, not `EDIT_TASKS`.

Carrying a task over at sprint close or cancel keeps its milestone. Leaving a project does not touch milestones or links.

---

## 6. Task history and audit log

**Task history** ([task-history-docs.md §3.2](./task-history-docs.md#32-fields)): a new field `MILESTONE` in `FIELD_CHANGED` entries. The values are milestone **names** as they were at the time; `null` means none.

| Change | Entry |
| --- | --- |
| link (3.7, 3.8) | `MILESTONE`: `null` → `Beta release` |
| move to another milestone (3.7, 3.8) | `MILESTONE`: `Beta release` → `Client demo` |
| unlink (3.8) | `MILESTONE`: `Beta release` → `null` |
| milestone deleted (3.5) | `MILESTONE`: `Beta release` → `null`, on every unlinked task that is not deleted, by the person who deleted it |

Suggested sentence: ***Ada Lovelace** changed the milestone: None → Beta release*. Renaming a milestone (3.4) writes no task history.

**Audit log** ([audit-log-docs.md §4.3](./audit-log-docs.md#43-projects-area-project)), area `PROJECT`, target type `MILESTONE` (new; `id` and the name at the time), `project` = the milestone's project:

| `action` | Written when | `changes` |
| --- | --- | --- |
| `MILESTONE_CREATED` | 3.2 succeeds | `name`: null → name; `targetDate`: null → date; `description`: null → text (only when given) |
| `MILESTONE_UPDATED` | 3.4 changes at least one field | only the fields that changed, in this order: `name`, `targetDate`, `description` (old → new) |
| `MILESTONE_DELETED` | 3.5 succeeds | `unlinkedTasks`: null → the number (also `0`) |
| `MILESTONE_RESTORED` | 3.6 succeeds | none |

Linking and unlinking tasks writes no audit entry: the task history already has it. Refused requests write nothing.

No notifications are sent for milestones (overdue reminders are out of scope).

---

## 7. Frontend flows (PMP-151)

Milestones work in **Scrum and Kanban**. Show buttons from `canManage` / `canLink`.

### 7.1 Milestones section on the project Overview

There is no separate project tab: milestones are a section of the Overview page, next to the charter's goals, scope and stakeholders.

1. Load `GET /projects/{projectId}/milestones`. One row or card per milestone, in the order returned: name, target date, status badge, a progress bar from `percent`, and the counts ("12 / 20 pts (6 / 9 tasks)" or "6 / 9 tasks"). `EMPTY` shows "No tasks yet" and no bar.
2. Clicking a milestone opens its page ([§7.2](#72-milestone-page)), which has its own URL, e.g. `/projects/{projectId}/milestones/{milestoneId}`.
3. **"Add milestone"** only when `canManage`. Disable it, with a hint, once `milestones.length` reaches `limit`. The form has name (max 100), target date (date picker, past dates allowed) and description (plain textarea, max 500, with a counter).
4. **Edit and Delete** per milestone only when `canManage`. Edit sends all three fields.
5. **Delete:** confirm "Delete "{name}"? {progress.totalTasks} tasks will be unlinked." (or "It has no tasks." when 0). After `204`, remove it from the list and show a toast "Milestone deleted." with **Undo** for a few seconds. Undo calls `POST /milestones/{id}/restore` and reloads the list. Tasks stay unlinked; the toast after an undo can say so.
6. On `409 MILESTONE_NAME_TAKEN`, keep the form open and show the message under the name field. On `409 MILESTONE_LIMIT_REACHED`, show the message. On `409 CONFLICT` or `WORKSPACE_ARCHIVED`, reload: the project became read-only.

### 7.2 Milestone page

1. Load `GET /milestones/{milestoneId}` for the header: name, target date, status, progress bar and counts, description (plain text), Edit/Delete when `canManage`.
2. **Linked tasks:** `GET /projects/{projectId}/tasks?milestoneId={id}&order=board&size=100` (page further if `totalElements` > 100). Group them by status column (`statusId` / `statusName`, in board order). The page's filters (assignee, type, priority, `q`, …) are the normal task list parameters added to the same request.
3. **"Add tasks"** when `canLink`: a dialog that searches `GET /projects/{projectId}/tasks?q=…` (top-level tasks only, paginated). Tasks already linked here can be shown as checked and disabled. Tasks linked to another milestone show that milestone's name from `milestone`, with a hint that they will be moved. Send the chosen ids in one `POST /milestones/{id}/tasks`. Then refresh the header from the response and reload the task list.
4. **Unlink** a task from this page when `canLink`: `PUT /tasks/{taskId}/milestone` with `{ "milestoneId": null }`.
5. A `404` on load means the milestone was deleted or is not visible: show "Milestone not found" with a link back to the Overview.

### 7.3 Task page

1. A **"Milestone"** field showing `task.milestone` (name, with the date in a tooltip), or "None".
2. For a top-level task with `canLink`, it is a dropdown. Its options are the milestones from `GET /projects/{projectId}/milestones` (at most 50, already sorted), plus "None". Choosing one calls `PUT /tasks/{taskId}/milestone`; replace the task with the returned `TaskResponse`.
3. For a subtask the field is read-only and shows the parent's milestone.
4. History shows the new `MILESTONE` entries ([§6](#6-task-history-and-audit-log)).

### 7.4 Board cards, backlog rows and the task list

1. **Label:** when `milestone` is not `null`, show a small milestone label (name) with the target date in a tooltip. It comes with the normal task responses, so no extra call is needed.
2. **Task list filter "Milestone":** the options are the project's milestones plus **"No milestone"**. A milestone sends `milestoneId={id}`, "No milestone" sends `noMilestone=true`. Reset to page 0 when it changes.

---

## 8. Data model

Migration `V21__milestones.sql`. V20 is the Gantt's task dates (PMP-48, Damir) and must be merged first.

| Table | Columns | Constraints |
| --- | --- | --- |
| `milestones` | `id`, `project_id`, `name` (100), `description` (500, nullable), `target_date` (`date`), `created_by_id` (nullable), `deleted_at` (nullable), plus the four audit columns (`created_at`, `updated_at`, `created_by`, `updated_by`) | unique index on (`project_id`, `lower(name)`) **where `deleted_at is null`**; index on `project_id` |
| `tasks` | new `milestone_id` (nullable) | index on `milestone_id` |

- **Foreign keys:** `milestones.project_id` cascades on delete (deleting a project removes its milestones). `created_by_id` is `SET NULL`, so milestones survive a deleted account. `tasks.milestone_id` is `SET NULL`.
- **One milestone per task** is the `tasks.milestone_id` column itself; there is no link table.
- **Milestones are soft-deleted** (`deleted_at`) and never listed again. Restoring clears `deleted_at`.
- Also in V21: `MILESTONE` in `ck_task_history_field`; the four new actions in `ck_audit_log_entries_action`; `MILESTONE` in `ck_audit_log_entries_target_type`.
- **Readers in other stories:** the Gantt (PMP-48) reads `milestones` (diamonds on `target_date`) and `tasks.milestone_id` (the "ends after milestone" conflict). The time views (PMP-50) filter by `tasks.milestone_id`.
- **Code:** `milestone/{models,repositories,services,controllers,dto,exceptions}`. `MilestoneService` holds the rules. Every write takes the project row lock (`ProjectRepository.lockById`), which serializes the name check, the 50 limit and linking against a delete.

---

## 9. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| Every milestone endpoint | Nothing about projects the caller cannot see: `404`, as for a missing one |
| `/milestones/{milestoneId}` paths | Not whether a milestone exists in another project: the same `404` as a missing one |
| 3.7 and 3.8 | Not whether a task or milestone id exists in another project: the same `400` as a missing one |
| `MilestoneResponse` | The creator's name, like other project content |

---

## 10. Known limitations

- **Restoring does not relink tasks** (story rule). Undo after a delete brings the milestone back empty.
- **No list of deleted milestones**: restore is only reachable from the Undo toast. The audit log still shows deletions to workspace admins.
- **No notifications or reminders** for overdue milestones.
- **No milestone dependencies, date shifting, recurring or cross-project milestones, and no calendar export.**
- **"Today" uses the server's time zone.** Around midnight, a user in another time zone may see `OVERDUE` a few hours early or late.

---

## 11. Implementation status

As of 26 Nov 2026:

| Part | Status |
| --- | --- |
| This contract | Written before the code, agreed with the backend owner; the frontend can start |
| Permissions `MILESTONE_MANAGE`, `MILESTONE_LINK` | Not started |
| `V21__milestones.sql`, entities, repositories | Not started |
| Status and progress calculation (§4) | Not started |
| Create, read, edit, delete, restore (3.1–3.6), audit entries | Not started |
| Linking (3.7, 3.8), task history | Not started |
| `TaskResponse.milestone`, `milestoneId` / `noMilestone` filters (§5) | Not started |
| Integration test on PostgreSQL | Not started |
| Frontend (PMP-151) and acceptance test (PMP-152) | Not started |

---

## 12. Decisions

| Date | Decision |
| --- | --- |
| 26 Nov 2026 | Two permissions: `MILESTONE_MANAGE` (PO, PM) and `MILESTONE_LINK` (all but Viewer). Workspace admins get neither through their workspace role, so one outside the project gets `403`, as for tasks and retros. |
| 26 Nov 2026 | Create, edit, delete and restore are all audited, not only edits. Linking is recorded in the task history only. |
| 26 Nov 2026 | A restore endpoint exists (`POST /milestones/{id}/restore`), used from the Undo toast. Restoring never relinks tasks. |
| 26 Nov 2026 | Delete unlinks every task, deleted ones included, so a restored task never points at a deleted milestone. Only the live tasks get a history entry. |
| 26 Nov 2026 | "Add tasks" is all or nothing (max 100). Tasks in another milestone are moved; tasks already here are skipped. |
| 26 Nov 2026 | Linking from the task page uses its own endpoint (`PUT /tasks/{id}/milestone`), not the task PATCH, because it has its own permission. |
| 26 Nov 2026 | The milestone page reads its tasks through the task list (`?milestoneId=`) instead of a separate endpoint, so the filters and order are the usual ones. |
| 26 Nov 2026 | Subtasks show their parent's milestone in `TaskResponse`, as with `sprintId`. |
| 26 Nov 2026 | "Today" is the server's date: workspaces have no time zone, and the other Sprint 3 stories use the server's zone too. |
| 26 Nov 2026 | `basis` is `POINTS` as soon as one linked task has story points. Unestimated tasks then count 0 points. The status always counts tasks. |
| 26 Nov 2026 | Read-only project (`COMPLETED` / `ARCHIVED`) answers `409 CONFLICT` on every write, as for retros and dependencies, until PMP-63 centralizes it. |
| 26 Nov 2026 | Migration `V21`: V20 is taken by the Gantt (PMP-48) and must be merged first. |
