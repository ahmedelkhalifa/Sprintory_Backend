# Sprintory — Task comments API contract (R10, PMP-26)

**Status:** contract agreed, backend not built yet (PMP-133, branch `features/comments_history`). See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/projects/{projectId}/tasks/{taskId}/comments/**`.
**Related:** [Mentions and rich text](./mentions-docs.md), [Task history](./task-history-docs.md), [Tasks](./tasks-docs.md), [Notifications](./notifications-docs.md) (who is told about a comment), [Roles and permissions](./roles-docs.md), [Workspaces](./workspaces-docs.md) (archiving, pagination), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R10, R26.

This document is the single reference for task comments. The backend implements it, the frontend (PMP-134, Amir) builds the comment section of the task view against it, and acceptance testing (PMP-135, Aliaksandr) checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a project member, I want to … discuss [a task] in comments, and see its history, so that I know what has been done and why."*

- **A comment belongs to one task.** It has an author, a time and a rich-text body.
- **Rich text is TipTap JSON**, checked by the backend. The format, the allowed nodes and the size limits are in [mentions-docs.md §2](./mentions-docs.md#2-rich-text-format). A comment can @mention people and teams ([mentions-docs.md](./mentions-docs.md)).
- **Oldest first, paginated.**
- **Who can comment:** every project member except Viewers. Viewers read.
- **Authors edit and delete only their own comments.** An edited comment carries `editedAt`, and the frontend shows "(edited)".
- **Moderation:** the Project Manager can delete **any** comment, but cannot edit someone else's.
- **Soft delete.** A deleted comment stays in the list as a placeholder ("Comment deleted"), so the thread stays readable. Its text is never returned again.
- **History:** deleting a comment writes a "comment deleted" entry to the task's history ([task-history-docs.md](./task-history-docs.md)). Posting and editing do not, and comment text is never copied into history.
- **Notifications:** posting notifies the task's watchers and the people mentioned. Editing notifies only newly mentioned people. Commenting makes you a watcher. See [notifications-docs.md §5](./notifications-docs.md#5-event-catalogue).
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Someone who cannot see the project gets `404`.
- **Archived workspaces are read-only.** Comments can still be read; posting, editing and deleting get `409 WORKSPACE_ARCHIVED`.
- **A deleted task hides its comments.** Every comment endpoint answers `404` "Task not found." for it. Restoring the task (PMP-131, `POST …/tasks/{taskId}/restore`) brings them back unchanged. Deleting a parent also deletes its subtasks, so their comments are hidden too until the parent is restored.
- **Subtasks have comments too.** A subtask (PMP-132) is a task with its own key, so the same endpoints work with the subtask's id as `{taskId}`, with the same rules. Its comments are its own; they do not appear on the parent.

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Member/Developer, Scrum Master, Product Owner | Project Manager |
| --- | --- | --- | --- | --- | --- |
| Read comments | 404 | 403 | allowed | allowed | allowed |
| Post a comment | 404 | 403 | 403 | allowed | allowed |
| Edit **own** comment | 404 | 403 | 403 | allowed | allowed |
| Edit someone else's comment | 404 | 403 | 403 | 403 | 403 |
| Delete **own** comment | 404 | 403 | 403 | allowed | allowed |
| Delete someone else's comment | 404 | 403 | 403 | 403 | allowed |

- Reading needs `VIEW_WORK`. Posting, editing and deleting own comments need `COMMENT`. Deleting anyone's comment needs `MODERATE_COMMENTS`, which only the Project Manager holds ([roles-docs.md §2](./roles-docs.md#2-permission-matrix)).
- A person who became a Viewer after commenting can no longer edit or delete their old comments: they lost `COMMENT`.
- A person who left the project keeps their comments in the thread, under their name.

---

## 2. Endpoints at a glance

All paths are under `/api/v1/projects/{projectId}/tasks/{taskId}/comments`. Bodies are JSON in `camelCase`.

| # | Method | Path | Request | Success | Permission |
| --- | --- | --- | --- | --- | --- |
| 3.1 | GET | `?page=&size=` | none | `200` + `PageResponse<CommentResponse>` | `VIEW_WORK` |
| 3.2 | POST | `` | `CommentRequest` | `201` + `CommentResponse` | `COMMENT` |
| 3.3 | PATCH | `/{commentId}` | `CommentRequest` | `200` + `CommentResponse` | `COMMENT`, author only |
| 3.4 | DELETE | `/{commentId}` | none | `204` | `COMMENT` and author, or `MODERATE_COMMENTS` |

`{taskId}` is looked up inside `{projectId}`, and `{commentId}` inside `{taskId}`: an id from another task or project gives the same `404` as a missing one.

### Bodies

```jsonc
// CommentRequest: "body" is a string holding a TipTap document (mentions-docs.md §2)
{
  "body": "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Can you check this, \"},{\"type\":\"mention\",\"attrs\":{\"kind\":\"user\",\"id\":\"31\",\"label\":\"Damir Gabaidullin\"}},{\"type\":\"text\",\"text\":\"?\"}]}]}"
}

// CommentResponse
{
  "id": 77,
  "taskId": 230,
  "author": { "id": 7, "name": "Ada Lovelace" },   // null if the user account no longer exists
  "body": "{\"type\":\"doc\",...}",                // the stored, normalized TipTap JSON; null when deleted
  "mentions": [                                     // current names of everyone and every team in the body
    { "kind": "user", "id": 31, "name": "Damir Gabaidullin" }
  ],
  "createdAt": "2026-11-05T09:12:44.120Z",
  "editedAt": null,                                 // set when the text was changed; show "(edited)"
  "deleted": false,
  "deletedAt": null,
  "canEdit": true,                                  // for the caller: own comment, not deleted, has COMMENT
  "canDelete": true                                 // for the caller: as above, or MODERATE_COMMENTS
}

// A deleted comment in the list
{ "id": 78, "taskId": 230, "author": { "id": 9, "name": "Linus Torvalds" }, "body": null,
  "mentions": [], "createdAt": "2026-11-05T09:20:00Z", "editedAt": null,
  "deleted": true, "deletedAt": "2026-11-05T10:02:13Z", "canEdit": false, "canDelete": false }
```

- `body` is a **string** holding the TipTap JSON, the same as the task `description`. Send `JSON.stringify(editor.getJSON())`, and pass `JSON.parse(body)` to the editor.
- The returned `body` is the **normalized** copy the backend stored. Unknown attributes are dropped, so it can differ from what was sent (see [mentions-docs.md §2](./mentions-docs.md#2-rich-text-format)).
- **Render mentions with `mentions[].name`**, not the `label` stored in the node. Names change, and the list holds the current one.
- `canEdit` and `canDelete` show or hide the buttons. The backend checks again on every request.

---

## 3. Endpoints in detail

### 3.1 List a task's comments

`GET /api/v1/projects/{projectId}/tasks/{taskId}/comments?page=&size=`

- **Who:** members with `VIEW_WORK` (every project role). Archived workspace: allowed.
- **Order:** oldest first (`createdAt`, then `id`). The order cannot be changed; `?sort=` is ignored.
- **Paging** as everywhere ([workspaces-docs.md, Pagination and search](./workspaces-docs.md#pagination-and-search)): `page` 0-based, `size` default 20, at most 100.
- **Deleted comments are included** as placeholders (`deleted: true`, `body: null`), so page numbers and the thread stay stable. `totalElements` counts them too.
- **Response `200`:** `PageResponse<CommentResponse>`.
- **Errors:** `401`; `403` (a workspace admin not in the project); `404` "Project not found."; `404` "Task not found." (missing, deleted, or another project's task).

### 3.2 Post a comment

`POST /api/v1/projects/{projectId}/tasks/{taskId}/comments`

- **Who:** members with `COMMENT` (everyone but Viewers). The workspace must be active.
- **Rules:**
  - `body` must be a valid TipTap document ([mentions-docs.md §2](./mentions-docs.md#2-rich-text-format)) with some text or at least one mention. An empty paragraph is refused.
  - At most **10,000 characters** of text (mentions count as their name).
  - Every mentioned person must be a member of this project, and every mentioned team must have a member in it ([mentions-docs.md §4](./mentions-docs.md#4-rules)).
- **Side effects, in the same transaction:**
  - the author starts watching the task;
  - the task's watchers get `TASK_COMMENTED`, and the mentioned people get `MENTIONED` instead ([notifications-docs.md §5](./notifications-docs.md#5-event-catalogue)).
- **Response `201`:** `CommentResponse`.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `body` missing or blank |
  | 400 | `INVALID_RICH_TEXT` | `body` is not a TipTap document, uses a node or mark that is not allowed, has an unsafe link, is empty, or is over the limit. The message says which. |
  | 400 | `INVALID_MENTION` | a mentioned person is not a member of this project, or a mentioned team has no member in it |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | a Viewer, or a workspace admin who is not in the project |
  | 404 | `RESOURCE_NOT_FOUND` | "Project not found." or "Task not found." |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

### 3.3 Edit a comment

`PATCH /api/v1/projects/{projectId}/tasks/{taskId}/comments/{commentId}`

- **Who:** the comment's **author**, who must still have `COMMENT`. The workspace must be active. Nobody else, not even the Project Manager, can edit it.
- **Request:** the full new `body`, with the same rules as 3.2.
- **Rules:**
  - Sending the same text again changes nothing: `editedAt` stays as it was and nobody is notified.
  - A mention that was **already** in the comment is kept, even if that person has since left the project. Only **newly added** mentions are checked ([mentions-docs.md §4](./mentions-docs.md#4-rules)).
  - Only **newly added** people get `MENTIONED`. Watchers are not told about edits.
- **Response `200`:** the updated `CommentResponse`, with `editedAt` set.
- **Errors:** as in 3.2, plus:

  | Status | Code | When |
  | --- | --- | --- |
  | 403 | `ACCESS_DENIED` | the caller is not the author |
  | 404 | `RESOURCE_NOT_FOUND` "Comment not found." | missing, deleted, or another task's comment |

### 3.4 Delete a comment

`DELETE /api/v1/projects/{projectId}/tasks/{taskId}/comments/{commentId}`

- **Who:** the author with `COMMENT`, or anyone with `MODERATE_COMMENTS` (the Project Manager). The workspace must be active.
- **Soft delete:** the comment stays in the list as "Comment deleted" (3.1). Its text is no longer returned, and nobody can edit or restore it.
- **History:** one `COMMENT_DELETED` entry on the task, naming who deleted it, without the text ([task-history-docs.md](./task-history-docs.md)).
- **Notifications:** none. Notifications already sent keep their 200-character excerpt until they expire (90 days).
- **Response:** `204`, no body. Deleting it again gives `404` "Comment not found.".
- **Errors:** `401`; `403` (not the author and not the Project Manager, or a Viewer); `404` as in 3.3; `409`.

---

## 4. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses). Branch on `code`, not on `message`.

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Request validation failed. | `body` missing or blank; `fieldErrors` names it |
| `BAD_REQUEST` | 400 | Malformed request body. | The request itself is not valid JSON |
| `INVALID_RICH_TEXT` | 400 | e.g. "The text is not a valid document.", "Node type 'image' is not allowed.", "Links must use http, https or mailto.", "A comment cannot be empty.", "A comment can have at most 10000 characters." | `body` breaks [mentions-docs.md §2](./mentions-docs.md#2-rich-text-format) |
| `INVALID_MENTION` | 400 | "Only members of this project can be mentioned." / "This team has no members in this project." | A newly added mention outside the project |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | A Viewer posting; editing or deleting someone else's comment without the right; a workspace admin not in the project |
| `RESOURCE_NOT_FOUND` | 404 | Project not found. / Task not found. / Comment not found. | Not visible, missing, deleted, or in another project or task |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Posting, editing or deleting in an archived workspace |

---

## 5. Frontend flows

### 5.1 Comment section of the task view (PMP-134)

1. Load `GET …/comments?page=0&size=20`. Older comments come first, so show "Load more" at the **bottom** for the next page.
2. Render each comment with a read-only TipTap editor from `JSON.parse(body)`. Show the author, `createdAt`, and "(edited)" when `editedAt` is not `null`.
3. Deleted comments (`deleted: true`): show a grey "Comment deleted" line in their place, with no text and no buttons.
4. Mentions: draw them as highlighted links using `mentions[].name`, matched by `kind` and `id`.
5. Show the editor only when the project's `permissions` contain `COMMENT`. Viewers see the thread without an input.
6. Show "Edit" when `canEdit` and "Delete" when `canDelete`. Ask for confirmation before deleting.
7. After post, edit or delete, update the item in place or reload the current page.
8. On `400 INVALID_MENTION`, keep the draft and show the message. On `INVALID_RICH_TEXT`, show the message.

### 5.2 @ suggestions

Typing `@` opens a list fed by `GET /projects/{projectId}/mentionables?q=` ([mentions-docs.md §3](./mentions-docs.md#3-endpoint-mention-suggestions)).

---

## 6. Data model

| Table | Columns | Constraints |
| --- | --- | --- |
| `task_comments` | `id`, `task_id`, `author_id` (nullable), `body` (`text`, normalized TipTap JSON), `edited_at` (nullable), `deleted_at` (nullable), `deleted_by_id` (nullable), audit columns | index on (`task_id`, `created_at`, `id`) |

- **Foreign keys:**
  - `task_id` → `tasks`, `ON DELETE CASCADE`. A soft-deleted task keeps its comments; deleting the project or workspace removes them.
  - `author_id` → `users`, `ON DELETE SET NULL`. The thread survives a deleted account, and the author shows as `null`.
  - `deleted_by_id` has **no** foreign key: it is a record of who did it, like the audit log's `actor_id`.
- **Leaving a project does not touch comments.** No cleanup is needed in `ProjectService.removeMember` or `WorkspaceService.removeMember`.
- **Soft delete** keeps the row so the thread order and page numbers do not shift. The text stays in the database but is never returned.
- **Migration:** `V12__task_comments.sql` (after `V10__subtasks.sql`, Damir's, and `V11__task_history.sql`).
- **Code:** `comment/{models,repositories,services,controllers,dto}`. `CommentService` holds the rules. Rich text goes through `common/richtext/RichTextParser`, and mentions through `mention/services/MentionService`.

---

## 7. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| Every comment endpoint | Nothing about projects the caller cannot see: `404`, as for a missing project |
| `/{commentId}` paths | Not whether a comment id exists on another task: the same `404` as a missing comment |
| A deleted comment | Only that a comment existed, its author and its times; never its text |
| `INVALID_MENTION` | Only "not a member of this project", which a member can already see from the member list |

---

## 8. Known limitations

- **No edit history of a comment.** Only the latest text and `editedAt` are kept.
- **No restoring a deleted comment.**
- **No reactions, threads or replies.**
- **No attachments in comments.** Files are attached to the task (R25).
- **No real-time update of the thread.** Other people's new comments appear after a reload; the bell tells watchers about them.

---

## 9. Implementation status

As of 4 Nov 2026, on branch `features/comments_history`:

| Part | Status |
| --- | --- |
| This contract | Written, agreed with the backend owner |
| `V12__task_comments.sql`, `TaskComment`, repository | Not started. Built after PMP-131/132 (Damir) is merged into `main` |
| `CommentService`, `CommentController`, `MODERATE_COMMENTS` | Not started |
| Notifications producer (`TaskCommented`, `Mentioned`) | Not started; the listener side is done (PMP-113) |
| Unit and PostgreSQL integration tests | Not started |
| Frontend (PMP-134) and acceptance test (PMP-135) | Not started |

---

## 10. Decisions

| Date | Decision |
| --- | --- |
| 4 Nov 2026 | Comment text is TipTap JSON, checked and normalized by the backend. No HTML is stored. The body is a string, like the task description. |
| 4 Nov 2026 | Only the Project Manager moderates, through a new permission `MODERATE_COMMENTS`. It deletes others' comments but never edits them. |
| 4 Nov 2026 | Delete is soft: the placeholder stays in the list, the text is never returned again, and a history entry is written without the text. |
| 4 Nov 2026 | Editing or deleting your own comment needs `COMMENT`, so a member turned Viewer can no longer change their old comments. |
| 4 Nov 2026 | Oldest first, paginated, deleted placeholders included. |
| 4 Nov 2026 | Editing notifies only newly added mentions; watchers are not told about edits. |
| 4 Nov 2026 | Subtasks can be commented on through the same endpoints, because a subtask is a task with its own key. |