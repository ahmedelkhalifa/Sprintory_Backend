# Sprintory — Task history API contract (R10, PMP-26)

**Status:** contract agreed, backend not built yet (PMP-133, branch `features/comments_history`). It replaces the status-only history of PMP-125, and covers the subtasks and restore of PMP-131/PMP-132 (Damir, branch `features/task-restore-subtasks`, which merges first). See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `GET /api/v1/projects/{projectId}/tasks/{taskId}/history`, and every action that writes an entry (§3).
**Related:** [Tasks](./tasks-docs.md), [Comments](./comments-docs.md), [Board configuration](./board-docs.md) (column delete moves tasks), [Audit log](./audit-log-docs.md) (the workspace-level log, a different thing), [Roles and permissions](./roles-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R10.

This document is the single reference for a task's activity history. The backend implements it, the frontend (PMP-134, Amir) builds the "History" tab of the task view against it, and acceptance testing (PMP-135, Aliaksandr) checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **A read-only, chronological feed per task.** It shows what changed, who changed it and when: the task's creation, every field change (old → new), status moves, assignee changes, deleted comments, subtasks added or removed, and restoring the task after a delete.
- **Subtasks have their own feed.** A subtask (PMP-132) is a task with its own key, so it has its own history (created, title, status, assignee, time estimate, restored). Its parent's feed shows only that a subtask was added or removed.
- **Entries can never be edited or deleted.** There is no endpoint for it, for anyone, not even a Project Manager or workspace admin. The entity is immutable in code.
- **Comment text is not copied** into history. Comments have their own section ([comments-docs.md](./comments-docs.md)). History only says that a comment was deleted, and by whom.
- **Description changes are recorded without text**: "changed the description". Descriptions are long rich text, and the current one is on the task.
- **Values are snapshots.** Names of people, columns and subtasks are stored as they were at the time, so a later rename does not change the past.
- **Only real changes are recorded.** Saving a field with the value it already has writes nothing.
- **Not the audit log.** The audit log ([audit-log-docs.md](./audit-log-docs.md)) is the workspace admins' log of key actions, and for tasks it logs only delete and restore. Everything else about a task is here.
- **Projects are private.** A non-member asking for a task's history by id gets `404` (R04).

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Every other project role |
| --- | --- | --- | --- | --- |
| Read a task's history | 404 | 403 | allowed | allowed |
| Edit or delete an entry | no endpoint | no endpoint | no endpoint | no endpoint |

Reading needs `VIEW_WORK`.

---

## 2. Endpoint

`GET /api/v1/projects/{projectId}/tasks/{taskId}/history?page=&size=`

- **Who:** members with `VIEW_WORK`, Viewers included. Archived workspace: allowed.
- **Order:** oldest first (`occurredAt`, then `id`). The order cannot be changed; `?sort=` is ignored.
- **Paging** as everywhere ([workspaces-docs.md, Pagination and search](./workspaces-docs.md#pagination-and-search)): `page` 0-based, `size` default 20, at most 100.
- **Response `200`:** `PageResponse<TaskHistoryEntryResponse>`:

  ```jsonc
  {
    "content": [
      { "id": 500, "type": "CREATED", "field": null, "oldValue": null, "newValue": null, "refId": null,
        "actor": { "id": 7, "name": "Ada Lovelace" }, "occurredAt": "2026-11-05T09:00:00Z" },
      { "id": 501, "type": "FIELD_CHANGED", "field": "PRIORITY", "oldValue": "LOW", "newValue": "HIGH", "refId": null,
        "actor": { "id": 31, "name": "Damir Gabaidullin" }, "occurredAt": "2026-11-05T09:12:44Z" },
      { "id": 502, "type": "FIELD_CHANGED", "field": "STATUS", "oldValue": "To Do", "newValue": "In Progress", "refId": null,
        "actor": { "id": 31, "name": "Damir Gabaidullin" }, "occurredAt": "2026-11-05T09:13:02Z" },
      { "id": 503, "type": "COMMENT_DELETED", "field": null, "oldValue": null, "newValue": null, "refId": 77,
        "actor": { "id": 7, "name": "Ada Lovelace" }, "occurredAt": "2026-11-05T10:02:13Z" }
    ],
    "page": 0, "size": 20, "totalElements": 4, "totalPages": 1
  }
  ```

- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | a workspace admin who is not in the project |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | the project does not exist or the caller cannot see it |
  | 404 | `RESOURCE_NOT_FOUND` "Task not found." | missing, deleted, or another project's task |

**Changed on 4 Nov 2026.** Until then this endpoint returned a plain array of status moves (`fromColumnId`, `fromColumnName`, `toColumnId`, `toColumnName`, `actorId`, `changedAt`). Those moves are kept and now appear as `FIELD_CHANGED` / `STATUS` entries, with the column names but no column ids.

---

## 3. Entry catalogue

### 3.1 Types

| `type` | Written when | `field` | `oldValue` → `newValue` | `refId` |
| --- | --- | --- | --- | --- |
| `CREATED` | the task or subtask is created | `null` | `null` → `null` | `null` |
| `FIELD_CHANGED` | a field of the task or subtask changes (3.2) | the field | the old and new value (3.2) | `null` |
| `COMMENT_DELETED` | a comment of the task is deleted, by its author or by the Project Manager | `null` | `null` → `null` (the text is never copied) | the comment id |
| `SUBTASK_ADDED` | on the **parent**: a subtask is created under it, or a subtask deleted on its own is restored | `null` | `null` → the subtask's key and title, e.g. `MOB-57 Write tests` | the subtask id |
| `SUBTASK_REMOVED` | on the **parent**: one of its subtasks is deleted on its own | `null` | the subtask's key and title → `null` | the subtask id |
| `RESTORED` | the task or subtask is restored after a delete (PMP-131, `POST …/restore`) | `null` | `null` → `null` | `null` |

`actor` is always the person who did it. `actor.id` can belong to someone who has since left; `actor.name` is the name they had at the time.

**Deleting and restoring a parent.** Deleting a parent also deletes its live subtasks, and every one of them disappears with it, so no entry is written: a deleted task's history cannot be read. Restoring the parent writes **one** `RESTORED` entry on the parent. The subtasks that come back with it get no entry of their own; the parent's entry covers them.

### 3.2 Fields

| `field` | Changed by | Value format | Example |
| --- | --- | --- | --- |
| `TITLE` | task edit, subtask edit | the title | `Login fails` → `Login fails on Safari` |
| `TYPE` | task edit | the enum value | `TASK` → `BUG` |
| `PRIORITY` | task edit | the enum value | `LOW` → `HIGH` |
| `STATUS` | task or subtask edit with `statusId`, a drag across columns, or deleting a board column that held the task or subtask | the column names | `To Do` → `In Progress` |
| `ASSIGNEE` | task or subtask edit with `assigneeId` or `clearAssignee` | the person's first and last name; `null` = unassigned | `null` → `Ada Lovelace` |
| `ESTIMATE` | task edit with `estimate` or `clearEstimate` (story points, top-level tasks only) | the number; `null` = not estimated | `3` → `5` |
| `ESTIMATE_MINUTES` | subtask edit with `estimateMinutes` or `clearEstimateMinutes` (time estimate, subtasks only) | the number of minutes; `null` = not estimated | `60` → `90` |
| `LABELS` | task edit with `labels` | the labels sorted and joined with `, `; `null` = none | `backend` → `backend, R09` |
| `DESCRIPTION` | task edit with `description` or `clearDescription` | always `null` → `null`: the entry means "changed the description" | |

- "Task edit" is `PATCH …/tasks/{taskId}`. "Subtask edit" is `PATCH …/tasks/{parentId}/subtasks/{subtaskId}`; that entry goes to the **subtask's** own history.
- One edit that changes several fields writes **one entry per field**, all with the same `occurredAt`.
- **Not recorded:**
  - reordering inside a column or in the backlog
  - a save that changes nothing
  - posting or editing a comment
  - watching
  - unassigning someone because they left the project (a bulk cleanup, not a person's action)
  - the task's own deletion: its history is hidden with it
- Enum values are sent as stored (`HIGH`), so the frontend shows its own labels.

### 3.3 Later types

Planned by later stories; each adds its `type` with a migration (§6):
- sprint scope changes and "carried over" markers (PMP-119, PMP-122);
- attachment removed (PMP-141).

---

## 4. Frontend flows (PMP-134)

1. The task view gets a "History" tab next to "Comments". Load `GET …/history?page=0&size=20`; older entries come first, so "Load more" goes at the bottom.
2. Render one line per entry with the actor's name and the time:

   | Entry | Line |
   | --- | --- |
   | `CREATED` | **Ada Lovelace** created the task (or "the subtask") |
   | `FIELD_CHANGED`, `DESCRIPTION` | **Ada Lovelace** changed the description |
   | `FIELD_CHANGED`, `ASSIGNEE` | **Ada Lovelace** assigned it to Damir Gabaidullin / unassigned Damir Gabaidullin / changed the assignee from X to Y |
   | `FIELD_CHANGED`, `ESTIMATE_MINUTES` | **Ada Lovelace** changed the time estimate: 1 h → 1 h 30 min (`null` shows as "None") |
   | `FIELD_CHANGED`, other fields | **Ada Lovelace** changed the priority: Low → High (`null` shows as "None") |
   | `COMMENT_DELETED` | **Ada Lovelace** deleted a comment |
   | `SUBTASK_ADDED` / `SUBTASK_REMOVED` | **Ada Lovelace** added / removed subtask MOB-57 Write tests |
   | `RESTORED` | **Ada Lovelace** restored the task |

3. A subtask's own history uses the same endpoint with the subtask's id: `GET …/tasks/{subtaskId}/history`. Show it where the subtask is opened.
4. There are no edit or delete controls, for anyone.

---

## 5. For backend developers

Every entry is written through **`task/services/TaskStatusHistoryService`**, inside the action's own transaction, after the change succeeded. The class keeps its PMP-125 name.

| Method | Use it for |
| --- | --- |
| `record(task, fromColumn, toColumn, actor)` | a move to another column. The signature is unchanged from PMP-125; it also publishes `TaskStatusChanged` (R27). |
| `recordCreated(task, actor)` | task and subtask creation |
| `recordChange(task, field, oldValue, newValue, actor)` | any other field; it writes nothing when the two values are equal |
| `recordCommentDeleted(task, commentId, actor)` | comment deletion |
| `recordSubtaskAdded(parent, subtask, actor)` / `recordSubtaskRemoved(parent, subtask, actor)` | entries on the parent (PMP-132) |
| `recordRestored(task, actor)` | restore (PMP-131) |

Where each one is called:

| Action | Class and method | Entries |
| --- | --- | --- |
| Create a task | `TaskApiService.create` | `CREATED` |
| Edit a task | `TaskApiService.update` | `FIELD_CHANGED` per changed field; `STATUS` through `record` |
| Drag on the board | `TaskApiService.moveOnBoard` | `STATUS` through `record` |
| Delete a board column | `ProjectBoardService` | `STATUS` through `record`, for every moved task and subtask |
| Delete a subtask on its own | `TaskApiService.delete` (child branch) | `SUBTASK_REMOVED` on the parent |
| Delete a parent | `TaskApiService.delete` | none (3.1) |
| Restore | `TaskApiService.restore` | `RESTORED` on the restored task; for a subtask restored on its own, also `SUBTASK_ADDED` on its parent |
| Create a subtask | `SubtaskService.create` | `CREATED` on the subtask, `SUBTASK_ADDED` on the parent |
| Edit a subtask | `SubtaskService.update` | `FIELD_CHANGED` on the subtask; `STATUS` through `record` |
| Delete a comment | `CommentService.delete` | `COMMENT_DELETED` |

Rules:
1. **Every path that moves a task between columns calls `record`.** That includes task PATCH, subtask PATCH, the board drag, column delete, and later sprint close.
2. **Record only real changes.** Return before recording on a no-op.
3. **Never pass comment text, a description, a token or a link** as a value.
4. **A new `type` or `field`** needs a migration changing `ck_task_history_type` or `ck_task_history_field`, plus its row in §3.

---

## 6. Data model

| Table | Columns | Constraints |
| --- | --- | --- |
| `task_history` | `id`, `task_id`, `type`, `field` (nullable), `old_value` (`text`, nullable), `new_value` (`text`, nullable), `ref_id` (nullable), `actor_id`, `actor_name`, `occurred_at` | check constraints on `type` and `field`; index on (`task_id`, `occurred_at`, `id`) |

- **Foreign keys:** `task_id` → `tasks`, `ON DELETE CASCADE`. A soft-deleted task keeps its history; deleting the project or workspace removes it. `actor_id` has **no** foreign key on purpose, so entries outlive their author, as in the audit log.
- **Append-only:** `TaskHistoryEntry` is `@Immutable`, and the repository offers no update or delete.
- **Migration:** `V11__task_history.sql`. (`V10__subtasks.sql` is Damir's PMP-131/132 migration, which merges first.)
  - It creates `task_history` and copies every `task_status_history` row into it as `FIELD_CHANGED` / `STATUS`. The actor name is taken from `users`; an actor who no longer exists becomes "Deleted user".
  - Then it **drops `task_status_history`** (from V7). V7 itself is not edited.
- **Dev databases** run with `ddl-auto=update`. Hibernate creates `task_history` but does not copy the old rows or drop the old table. Old status moves then do not show on a dev database, which is harmless; recreate the database to see them.
- **Code:** `task/models/TaskHistoryEntry`, `TaskHistoryType`, `TaskHistoryField`; `task/repositories/TaskHistoryRepository`; `task/dto/TaskHistoryEntryResponse`; `task/services/TaskStatusHistoryService`.

---

## 7. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| `/history` | Nothing about projects the caller cannot see: `404`, as for a missing project. Not whether a task id exists in another project: the same `404` as a missing task. |
| An entry by someone who left | Their name at the time, as for an old comment |
| `COMMENT_DELETED` | That a comment was deleted and by whom, never its text |

---

## 8. Known limitations

- **No filtering** by type, field or person. Only paging.
- **No old or new text for descriptions**, by design.
- **Status moves recorded before 4 Nov 2026 lost their column ids** in the migration; their names are kept.
- **Bulk changes are not recorded per task**: unassigning someone who left the project, or a column rename (the tasks keep their column, only its name changed).

---

## 9. Implementation status

As of 4 Nov 2026, on branch `features/comments_history`:

| Part | Status |
| --- | --- |
| This contract | Written, agreed with the backend owner |
| `V11__task_history.sql`, `TaskHistoryEntry`, repository | Not started |
| `TaskStatusHistoryService` writers and paginated list | Not started |
| Entries from task create and edit, comment delete | Not started |
| Entries from subtasks and restore (`SubtaskService`, `TaskApiService.delete/restore`) | Not started. Needs PMP-131/132 merged into `main` first (Damir, `features/task-restore-subtasks`); this story then adds the calls |
| Unit and PostgreSQL integration tests | Not started |
| Frontend (PMP-134) and acceptance test (PMP-135) | Not started |

---

## 10. Decisions

| Date | Decision |
| --- | --- |
| 1 Nov 2026 | (PMP-125) A status change records from/to column snapshots and the actor. |
| 4 Nov 2026 | One generic `task_history` table for every kind of entry. V11 copies the PMP-125 status moves into it and drops `task_status_history`. `TaskStatusHistoryService` stays the single writer, and `record(...)` keeps its signature. |
| 4 Nov 2026 | Subtasks have their own feed; the parent only shows "subtask added / removed". Their time estimate is its own field, `ESTIMATE_MINUTES`, because it is not story points. |
| 4 Nov 2026 | Restore (PMP-131) writes `RESTORED`. Deleting a parent writes nothing for its subtasks, and restoring it writes one entry on the parent only. |
| 4 Nov 2026 | PMP-131/132 merge first; this story adds their history calls afterwards, because it owns the history (agreed in PMP-132's docs, §10.4). |
| 4 Nov 2026 | A status move is a `FIELD_CHANGED` entry with field `STATUS`, like every other field. |
| 4 Nov 2026 | Description changes are recorded without text; comment text is never copied. |
| 4 Nov 2026 | Values and the actor's name are snapshots, stored as display text. `actor_id` has no foreign key. |
| 4 Nov 2026 | `GET /history` is paginated, oldest first, and carries the actor's name. |