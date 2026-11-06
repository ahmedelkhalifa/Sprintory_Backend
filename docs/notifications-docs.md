# Sprintory — Notifications API contract (R27, PMP-32)

**Status:** backend implemented and tested, including against PostgreSQL (PMP-113, branch `features/notifications`). Still open: the manual end-to-end run with Mailpit. See [§13 Implementation status](#13-implementation-status).
**Applies to:**
- backend `/api/v1/notifications/**`, `/api/v1/notification-preferences` and `/api/v1/projects/{projectId}/tasks/{taskId}/watch`;
- the actions in [§5](#5-event-catalogue) that produce notifications.

**Related:** [Tasks](./tasks-docs.md), [Projects](./projects-docs.md), [Board](./board-docs.md), [Workspaces](./workspaces-docs.md), [Roles and permissions](./roles-docs.md), [Authentication](./auth-endpoints.md), [Email service](./email-service.md), [API and Git Conventions](./api-conventions.md) (error shape, visibility), [Requirements](./requirements.md) R27.

This document is the single reference for notifications. The backend implements it, the frontend (PMP-114, Amir) builds the bell, the notification list, the preferences page and the watch toggle against it, and acceptance testing (PMP-115, Damir) checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a team member, I want to receive notifications about what concerns me and choose how I receive them, so that I stay informed without being overwhelmed."*

- **One bell per user, across all workspaces.**
  - The bell is not per workspace. Each notification carries the workspace, project and task it is about, so the list can show where it came from.
  - The list can be narrowed to one workspace with `?workspaceId=`.
- **Two channels:**
  - **In-app:** the bell and the list. It is **real time**: a new notification reaches every open tab within a few seconds over a Server-Sent Events stream ([§4](#4-real-time-stream-sse)), with no refresh needed.
  - **Email:** a short summary with a link. It is sent **asynchronously and retried**, so a slow or broken mail server never slows down or fails the action that caused it ([§10](#10-email-delivery)).
- **Preferences.** Each user has a matrix of *category × channel* on/off switches ([§6](#6-preferences)).
  - **Defaults apply without any setup.**
  - Invitation and security notifications are **always on** and cannot be turned off.
- **Watching.** You automatically watch tasks you created, are assigned to, or comment on. You can unwatch any task, and watchers are told about comments and status changes ([§7](#7-watching)).
- **You are never notified about your own actions.** For example, assigning a task to yourself sends nothing. Security notifications are the only exception (they have no actor).
- **Newest first, paginated.** The response carries an unread count. Opening a notification marks it read, and "Mark all as read" sets the count to 0.
- **Retention: 90 days.** This is a team choice, recorded in [§14](#14-decisions). Older notifications are deleted by a nightly job, whether they were read or not.
- **Lost access.** If you lose access to a project, its old notifications stay in your list. Opening one answers `404`, the same as any page you can no longer see (R04).
- **No sensitive content.** Notification text holds only names, task keys and titles. Comments and mentions add a short excerpt. A notification never contains a full comment, description, password, token or link with a token ([§9](#9-what-is-never-sent)).

### Who can do what

Every endpoint needs a logged-in user. Everything is scoped to the caller: nobody can read, open or change another person's notifications or preferences.

| Action | Not logged in | Logged in |
| --- | --- | --- |
| List, count, open, mark all read, stream | 401 | own notifications only. Someone else's notification id gives `404` |
| Read or change preferences | 401 | own preferences only |
| Watch / unwatch a task | 401 | needs `VIEW_WORK` in the task's project, so every project member including Viewers can watch. Not visible → `404`. A workspace admin who is not in the project → `403` |

---

## 2. Endpoints at a glance

All paths are under `/api/v1`. Responses are JSON in `camelCase`, except the stream, which is `text/event-stream`.

| # | Method | Path | Body | Success |
| --- | --- | --- | --- | --- |
| 3.1 | GET | `/notifications?unreadOnly=&workspaceId=&page=&size=` | none | `200 PageResponse<NotificationResponse>` |
| 3.2 | GET | `/notifications/unread-count` | none | `200 UnreadCountResponse` |
| 3.3 | POST | `/notifications/{id}/open` | none | `200 NotificationTargetResponse` |
| 3.4 | POST | `/notifications/read-all` | none | `200 UnreadCountResponse` (always `0`) |
| 3.5 | GET | `/notifications/stream` | none | `200 text/event-stream` ([§4](#4-real-time-stream-sse)) |
| 3.6 | GET | `/notification-preferences` | none | `200 NotificationPreferencesResponse` |
| 3.7 | PUT | `/notification-preferences` | `UpdateNotificationPreferencesRequest` | `200 NotificationPreferencesResponse` |
| 3.8 | GET | `/projects/{projectId}/tasks/{taskId}/watch` | none | `200 WatchResponse` |
| 3.9 | PUT | `/projects/{projectId}/tasks/{taskId}/watch` | none | `200 WatchResponse` (`watching: true`) |
| 3.10 | DELETE | `/projects/{projectId}/tasks/{taskId}/watch` | none | `200 WatchResponse` (`watching: false`) |

There is no endpoint to create, edit or delete a notification. Notifications are written by the backend as a side effect of the actions in [§5](#5-event-catalogue).

### Response bodies

```jsonc
// NotificationResponse
{
  "id": 981,                                  // stable, unique; use as the React key
  "type": "TASK_ASSIGNED",                    // one of §5
  "category": "ASSIGNMENT",                   // the preference category of `type` (§6)
  "createdAt": "2026-11-03T14:05:31.218Z",    // UTC instant
  "read": false,
  "readAt": null,                             // UTC instant once read
  "message": "Damir Gabaidullin assigned you MOB-42 \"Fix login\"",  // ready to display, plain text
  "excerpt": null,                            // short plain-text excerpt; only for TASK_COMMENTED and MENTIONED
  "actor": { "id": 31, "name": "Damir Gabaidullin" },  // null for security events
  "workspace": { "id": 4, "name": "Acme" },   // null for security events
  "project": { "id": 15, "key": "MOB", "name": "Mobile App" },  // null for workspace and security events
  "task": { "id": 230, "key": "MOB-42", "title": "Fix login" }   // null unless the notification is about a task
}

// UnreadCountResponse
{ "count": 3 }

// NotificationTargetResponse: where to navigate after "open" (§3.3)
{
  "kind": "TASK",          // TASK | PROJECT | NONE
  "workspaceId": 4,        // null when kind is NONE
  "projectId": 15,         // null when kind is NONE
  "taskId": 230,           // only for TASK
  "taskKey": "MOB-42"      // only for TASK
}

// NotificationPreferencesResponse: always all 8 categories, in the order of §6
{
  "preferences": [
    { "category": "ASSIGNMENT", "inApp": true, "email": true,  "locked": false, "defaultInApp": true, "defaultEmail": true },
    { "category": "STATUS_CHANGE", "inApp": true, "email": false, "locked": false, "defaultInApp": true, "defaultEmail": false },
    { "category": "INVITATION", "inApp": true, "email": true,  "locked": true,  "defaultInApp": true, "defaultEmail": true }
    // ...
  ]
}

// UpdateNotificationPreferencesRequest: only the categories and channels you send change
{
  "preferences": [
    { "category": "STATUS_CHANGE", "email": false },          // inApp omitted = unchanged
    { "category": "COMMENT", "inApp": true, "email": true }
  ]
}

// WatchResponse
{ "watching": true }

// PageResponse<T> (shared, see workspaces-docs.md §2 "Pagination and search")
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 37, "totalPages": 2 }
```

---

## 3. Endpoints in detail

### 3.1 List notifications

`GET /api/v1/notifications`

- **Order:** newest first (`createdAt` descending, then `id` descending). The order cannot be changed, and `?sort=` is ignored.
- **Query parameters** (all optional):

| Parameter | Format | Rules |
| --- | --- | --- |
| `unreadOnly` | `true` / `false` | Default `false`. When `true`, only unread notifications are returned. |
| `workspaceId` | positive integer | Only notifications of this workspace. Security notifications have no workspace and never match. A workspace you are not in is not an error: it matches nothing. |
| `page` | integer | 0-based, default `0`. |
| `size` | integer | Default `20`, at most `100`. A larger value is capped. |

- **Validation:** a non-numeric or non-positive `workspaceId`, or a `unreadOnly` that is not a boolean, gives `400 BAD_REQUEST`.
- **Response `200`:** a `PageResponse<NotificationResponse>`. A page past the end returns an empty `content`.
- Notifications from projects you can no longer see **are still listed** ([§8](#8-lost-access)).

**Errors:** `400`, `401`.

### 3.2 Unread count

`GET /api/v1/notifications/unread-count` returns `{ "count": n }`, the number of unread notifications across all workspaces.

The stream ([§4](#4-real-time-stream-sse)) also pushes the count whenever it changes. Call this endpoint on page load, and use the stream afterwards.

**Errors:** `401`.

### 3.3 Open a notification

`POST /api/v1/notifications/{id}/open`

Call this when the user clicks a notification. It **marks the notification read**, then tells the frontend where to go.

- **Marking read.** The notification is marked read **even when the answer is `404` because the target is gone**: the click still counts. Opening an already read notification is fine, and `readAt` keeps its first value.
- **Response `200`** is a `NotificationTargetResponse`:
  - `kind: "TASK"`: navigate to the task (`projectId`, `taskId`, `taskKey`).
  - `kind: "PROJECT"`: navigate to the project (project membership, roles and sprint notifications).
  - `kind: "NONE"`: there is nothing to open. This covers workspace invitations ("open the invitation email to join") and security notifications. Just show the message.
- **`404 RESOURCE_NOT_FOUND`** when:
  - the id does not exist or belongs to someone else (the notification is then *not* marked read);
  - the project no longer exists, or you are no longer allowed to see it;
  - the task was deleted, or you no longer have `VIEW_WORK` in its project.

  Show the normal "Not found" page.

**Errors:** `400` (non-numeric id), `401`, `404`.

### 3.4 Mark all as read

`POST /api/v1/notifications/read-all` marks every unread notification of the caller read, in all workspaces, and returns `{ "count": 0 }`. Calling it with nothing unread is fine.

**Errors:** `401`.

### 3.5 Stream

`GET /api/v1/notifications/stream`: see [§4](#4-real-time-stream-sse).

### 3.6 Read preferences

`GET /api/v1/notification-preferences` returns all 8 categories in a fixed order ([§6](#6-preferences)), each with:

- the effective `inApp` and `email` values;
- `locked`;
- the defaults (`defaultInApp`, `defaultEmail`), so the page can offer "Reset to defaults".

A new user gets the defaults, with nothing to set up.

**Errors:** `401`.

### 3.7 Change preferences

`PUT /api/v1/notification-preferences`

- **Partial.** Only the categories you list change, and within one, a channel you omit (or send as `null`) is unchanged.
- **Body rules** (each broken rule answers `400`):
  - `preferences` is required and must not be empty;
  - `category` is required and must be one of [§6](#6-preferences);
  - the same category must not appear twice.
- **Locked categories** (`INVITATION`, `SECURITY`): sending `false` for either channel answers `400 BAD_REQUEST` with `"<Category> notifications cannot be turned off."`. Sending `true` is accepted and changes nothing.
- **Response `200`:** the full `NotificationPreferencesResponse` after the change.
- A change applies to notifications created **after** it. It is not retroactive, and an email already queued is still sent.

**Errors:** `400`, `401`.

### 3.8–3.10 Watch a task

`GET | PUT | DELETE /api/v1/projects/{projectId}/tasks/{taskId}/watch`

- **Who:** anyone with `VIEW_WORK` in the project, including Viewers.
  - A project you cannot see, or a task that does not exist in this project (or was deleted), answers `404`.
  - A workspace admin who is not in the project answers `403`, as for every task endpoint.
- **`GET`** says whether the caller watches the task.
- **`PUT`** watches it. It is idempotent.
- **`DELETE`** unwatches it. It is idempotent.
- **Archived workspaces:** watching and unwatching are still allowed. They are personal settings, not project changes.

**Errors:** `400` (non-numeric id), `401`, `403`, `404`.

---

## 4. Real-time stream (SSE)

The bell is kept live by **Server-Sent Events**: one long-lived `GET` per browser tab that the server writes events into. It is one-way only (server to browser). The browser's `EventSource` reconnects on its own after a dropped connection.

**Connecting:**

```js
const es = new EventSource(`${API_URL}/api/v1/notifications/stream`, { withCredentials: true });
es.addEventListener("unread-count", e => setCount(JSON.parse(e.data).count));
es.addEventListener("notification", e => prependToList(JSON.parse(e.data)));   // a NotificationResponse
```

- `withCredentials: true` is required. It makes the browser send the `access_token` cookie, which is how the stream is authenticated (the API is cross-origin, `api.` vs `app.`).
- Open **one** stream per tab, after login. Close it (`es.close()`) on logout.

**Events the server sends:**

| Event name | `data` | When |
| --- | --- | --- |
| `unread-count` | `{ "count": n }` | Immediately after connecting, and every time the count changes. Changes come from a new notification, an open ([§3.3](#33-open-a-notification)) or read-all ([§3.4](#34-mark-all-as-read)), in this tab or in another tab or device. |
| `notification` | a `NotificationResponse` | A new in-app notification for the caller. It is always followed by an `unread-count`. |
| *(comment)* | none | `: connected` (together with `retry: 3000`) as the very first line, and `: keep-alive` every 25 s, so proxies do not close an idle connection. `EventSource` ignores comments. |

**Rules:**
- **Events are sent only after the action's transaction commits.** A refused or rolled-back action never pushes anything.
- **No replay.** Events sent while a tab was disconnected are not re-sent; the list endpoint is the source of truth. After every (re)connect the first event is the current `unread-count`. If the bell dropdown is open, re-fetch page 0 of [§3.1](#31-list-notifications).
- **The server closes the stream after about 10 minutes.** It sends `retry: 3000`, so `EventSource` reconnects 3 s later on its own. This keeps connections fresh and re-checks authentication regularly.
- **Expired login.** The access token lives 15 minutes. If it has expired when the stream reconnects, the server answers `401`. `EventSource` then fires `error` with `readyState === EventSource.CLOSED` and stops trying.
  - The frontend must call `POST /api/v1/auth/refresh`, then open a new `EventSource`.
  - If the refresh fails, the user is logged out.
  - This is the same refresh-and-retry rule as every other endpoint, done by hand because `EventSource` cannot retry with a refresh.
- **At most 5 open streams per user.** A 6th connection closes the oldest one.
- The response carries `X-Accel-Buffering: no` and `Cache-Control: no-cache`, so Nginx Proxy Manager delivers events at once without buffering. No proxy configuration is needed.

---

## 5. Event catalogue

Every `type` value, its preference `category`, who receives it and what it links to. Values are stable: branch on `type`, or display `message` as it is.

**General rules** (apply to every row unless it says otherwise):
1. **The actor is never a recipient.** Assigning yourself, moving your own task, adding yourself to a project or commenting on a task you watch sends nothing to you.
2. **Recipients must still be in the project** at the moment of the event. Watchers are removed when they leave ([§7](#7-watching)), and a mentioned user outside the project receives nothing.
3. **One notification per person per event.** If a comment both mentions you and is on a task you watch, you get **only** `MENTIONED`.
4. **Preferences are applied per recipient** ([§6](#6-preferences)):
   - in-app off: no bell entry and no stream event;
   - email off: no email;
   - both off: nothing is created at all.
5. **Names are snapshots.** The actor, workspace, project and task names are stored as they were when the notification was created. A later rename does not change old notifications.

| `type` | `category` | Produced when | Recipients | `task` | Open → `kind` | Example `message` |
| --- | --- | --- | --- | --- | --- | --- |
| `TASK_ASSIGNED` | `ASSIGNMENT` | A task is created with an assignee, or its assignee changes to another person (`POST` / `PATCH /projects/{id}/tasks`). Clearing the assignee or "changing" it to the same person sends nothing. | the new assignee | yes | `TASK` | *Damir Gabaidullin assigned you MOB-42 "Fix login"* |
| `TASK_STATUS_CHANGED` | `STATUS_CHANGE` | A task moves to another board column. This happens through task update, a board move, or deleting a column (which moves its tasks: **one notification per moved task**). Reordering inside the same column sends nothing. | the task's watchers | yes | `TASK` | *Damir Gabaidullin moved MOB-42 "Fix login" from To Do to In Progress* |
| `TASK_COMMENTED` | `COMMENT` | Someone comments on a task. **Produced once comments ship (PMP-133).** | the task's watchers, minus the people mentioned in the comment | yes, with `excerpt` | `TASK` | *Damir Gabaidullin commented on MOB-42 "Fix login"* |
| `MENTIONED` | `MENTION` | Someone @mentions you, or a team you are in, in a comment, a task or subtask description (PMP-136), or a project chat message (PMP-137). Editing notifies **only newly added** mentions. | the mentioned users. For `@team`, the team's members who are in this project. This includes people who unwatched the task. | yes, with `excerpt` | `TASK`; `PROJECT` for a chat mention | *Damir Gabaidullin mentioned you in MOB-42 "Fix login"*; in the chat: *Damir Gabaidullin mentioned you in the "Mobile App" chat* |
| `PROJECT_MEMBER_ADDED` | `PROJECT_MEMBERSHIP` | You are added to a project, directly or through a team (`POST /projects/{id}/members`, `/teams`). The creator of a new project gets nothing (they are the actor). | the added person | no | `PROJECT` | *Damir Gabaidullin added you to the project "Mobile App"* |
| `PROJECT_ROLES_CHANGED` | `PROJECT_MEMBERSHIP` | Your project roles change (`PUT /projects/{id}/members/{userId}/roles`) and the set actually differs | the person whose roles changed | no | `PROJECT` | *Damir Gabaidullin changed your roles in "Mobile App" to Project Manager, Member* |
| `SPRINT_STARTED` | `SPRINT` | A sprint is started. **Produced once sprints ship (PMP-119).** | every project member | no | `PROJECT` | *Damir Gabaidullin started "MOB Sprint 4" in "Mobile App"* |
| `SPRINT_COMPLETED` | `SPRINT` | A sprint is closed. **Produced once PMP-122 ships.** | every project member | no | `PROJECT` | *Damir Gabaidullin completed "MOB Sprint 4" in "Mobile App"* |
| `SPRINT_CANCELLED` | `SPRINT` | A sprint is cancelled. **Produced once PMP-122 ships.** | every project member | no | `PROJECT` | *Damir Gabaidullin cancelled "MOB Sprint 4" in "Mobile App"* |
| `WORKSPACE_INVITATION` | `INVITATION` 🔒 | An admin invites your email to a workspace (`POST /workspaces/{id}/invitations`) **and the email already has an account**. An email with no account gets only the invitation email. | the invited account | no | `NONE` | *Ada Lovelace invited you to the workspace "Acme". Open the invitation email to join.* |
| `PASSWORD_RESET` | `SECURITY` 🔒 | Your password was changed through the "forgot password" link | you (`actor: null`) | no | `NONE` | *Your password was changed. If this wasn't you, reset it again now and contact your workspace admin.* |
| `LOGIN_FAILED_REPEATED` | `SECURITY` 🔒 | 5 wrong passwords for your account within 15 minutes (the same detection as the audit log's `LOGIN_FAILED_REPEATED`) | you (`actor: null`) | no | `NONE` | *There were 5 failed sign-in attempts on your account. If this wasn't you, change your password.* |

🔒 = locked category: always on, in both channels.

**Notes:**
- `WORKSPACE_INVITATION`:
  - Its **email is the existing invitation email**, which carries the join link and is sent by the invite action itself. The notification does not send a second email.
  - The in-app notification cannot accept the invitation, because the join token exists only in the email. Nothing changes in the invitation flow ([workspaces-docs.md](./workspaces-docs.md)).
- `LOGIN_FAILED_REPEATED` counts only existing email-and-password accounts. The person who fails to log in still sees the normal `401` and nothing else.
- `excerpt` is plain text of at most 200 characters, ending in `…` when cut. Mentions inside it appear as `@Name`. It is never the full comment.

---

## 6. Preferences

The matrix the preferences page shows, in this order, with the defaults. **A user who never touched the page has exactly these values.**

| `category` | Covers | In-app default | Email default | Locked |
| --- | --- | --- | --- | --- |
| `ASSIGNMENT` | `TASK_ASSIGNED` | on | **on** | no |
| `MENTION` | `MENTIONED` | on | **on** | no |
| `COMMENT` | `TASK_COMMENTED` | on | off | no |
| `STATUS_CHANGE` | `TASK_STATUS_CHANGED` | on | off | no |
| `PROJECT_MEMBERSHIP` | `PROJECT_MEMBER_ADDED`, `PROJECT_ROLES_CHANGED` | on | off | no |
| `SPRINT` | `SPRINT_STARTED`, `SPRINT_COMPLETED`, `SPRINT_CANCELLED` | on | off | no |
| `INVITATION` | `WORKSPACE_INVITATION` | on | on | **yes** |
| `SECURITY` | `PASSWORD_RESET`, `LOGIN_FAILED_REPEATED` | on | on | **yes** |

- Preferences are **per user and global**, the same in every workspace and project. There are no per-project settings.
- **Locked rows:** show both switches as on and disabled, with a hint such as "Always on". The API refuses to turn them off.
- Unwatching a task and turning off a category are separate controls:
  - Unwatching stops comment and status notifications for **that task**.
  - Turning off `MENTION` stops mention notifications **everywhere**.
  - A mention reaches you on an unwatched task as long as `MENTION` is on.

---

## 7. Watching

Watchers receive `TASK_COMMENTED` and `TASK_STATUS_CHANGED` for a task.

**You start watching a task automatically when you:**
1. create it;
2. are assigned to it, by someone else or by yourself;
3. comment on it (once comments ship, PMP-133).

You can also watch it by hand (`PUT …/watch`).

**Unwatching** (`DELETE …/watch`) stops it at once. A **new** trigger makes you a watcher again: being assigned to the task again, or commenting on it again. A previous assignee who is replaced keeps watching until they unwatch.

**You stop watching automatically** when you leave the task's project or its workspace, or are removed from it. Your watcher rows for that project or workspace are deleted, so you receive nothing more from it.

**Deleted tasks:** watchers are kept with the task, so a restored task (PMP-131) keeps its watchers. A deleted task produces no events.

**Showing the toggle:** on the task detail page, call `GET …/watch` and show a "Watch" / "Watching" toggle. The task response itself does not carry the flag.

---

## 8. Lost access

Notifications belong to the recipient, not to the project, so they are **not deleted** when someone leaves a project or a workspace.

- The list still shows them, with their stored text. The text is limited to names, keys, titles and short excerpts, so nothing more sensitive than what the person already saw is kept.
- Opening one checks access **at that moment**: if the project or task can no longer be seen, `POST /notifications/{id}/open` answers `404`, and the notification is still marked read.
- No new notifications arrive from a project after leaving it ([§5](#5-event-catalogue), rule 2).
- **Exception:** when a workspace is **permanently deleted**, all its notifications are deleted with it.

---

## 9. What is never sent

Neither in-app notifications nor emails ever contain:
- passwords, password hashes, any token, or any link containing a token. The invitation link stays in the invitation email.
- the full text of a comment, task description or chat message. Only a 200-character excerpt is used, and only for comments and mentions.
- stakeholder contact details (charter, PMP-36).

Emails contain the task key, title, the excerpt if any, a link to the item, and a link to the preferences page. Nothing else.

---

## 10. Email delivery

1. **Queued, not sent inline.** When an action produces an email, the email is rendered and saved in an **outbox table** in the same transaction as the action. The action never waits for the mail server and never fails because of it. If the action rolls back, the email disappears with it.
2. **A background worker** checks the outbox every 15 seconds and sends due emails. In normal operation an email arrives within about 15–30 seconds of the action.
3. **Retries.** A failed send is retried after 1 min, 5 min, 15 min, 1 h and 6 h. After the 6th failed attempt the email is marked `FAILED`, logged, and given up.
4. **Content** uses the shared email layout ([email-service.md](./email-service.md)):
   - greeting: "Hi <first name>,";
   - body: the `message` (the same text as the bell), then the quoted `excerpt` if any;
   - a button: "Open task" for task notifications, "Open project" for project and sprint ones, "Open Sprintory" (the app's home page) for security ones;
   - a footer: "You are receiving this email because of your Sprintory notification preferences. Manage them at <preferences link>".

   Subjects, one per type. A subject is always one line (newlines in a title become spaces) and at most 255 characters:

   | `type` | Subject |
   | --- | --- |
   | `TASK_ASSIGNED` | `[Sprintory] MOB-42 Fix login: you were assigned` |
   | `TASK_STATUS_CHANGED` | `[Sprintory] MOB-42 Fix login: status changed` |
   | `TASK_COMMENTED` | `[Sprintory] MOB-42 Fix login: new comment` |
   | `MENTIONED` | `[Sprintory] MOB-42 Fix login: you were mentioned`; in the chat: `[Sprintory] You were mentioned in the Mobile App chat` |
   | `PROJECT_MEMBER_ADDED` | `[Sprintory] You were added to Mobile App` |
   | `PROJECT_ROLES_CHANGED` | `[Sprintory] Your roles in Mobile App changed` |
   | `SPRINT_STARTED` / `SPRINT_COMPLETED` / `SPRINT_CANCELLED` | `[Sprintory] A sprint started in Mobile App` / `… was completed in …` / `… was cancelled in …` |
   | `PASSWORD_RESET` | `[Sprintory] Your password was changed` |
   | `LOGIN_FAILED_REPEATED` | `[Sprintory] Failed sign-in attempts on your account` |
   | `WORKSPACE_INVITATION` | not sent by notifications: the invitation email is the email |
5. **Links** are built from configurable frontend routes (properties below). The placeholders `{workspaceId}`, `{projectId}`, `{taskId}` and `{taskKey}` are replaced. **Routes to confirm with the frontend (Amir):**

| Property | Default | Used for |
| --- | --- | --- |
| `app.notifications.task-path` | `/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}` | task notifications |
| `app.notifications.project-path` | `/workspaces/{workspaceId}/projects/{projectId}` | project and sprint notifications |
| `app.notifications.preferences-path` | `/settings` | the footer link of every notification email |

   All are appended to `app.frontend-url` and set in `application.properties`. If a route changes, only the property changes, not the code. Write the placeholders exactly as above (camelCase, in braces); any other text is left as it is. For example, a task email in development links to `http://localhost:5173/workspaces/4/projects/15/tasks/230`. The retention is `app.notifications.retention-days` (default `90`).

   In the app, the frontend builds the same routes from the answer of [§3.3](#33-open-a-notification), which carries `workspaceId`, `projectId` and `taskId`.
6. **Emails that do not go through the outbox:** account verification, password reset links and workspace invitations. They are sent inline as before ([auth-endpoints.md](./auth-endpoints.md), [workspaces-docs.md](./workspaces-docs.md)), so their success and failure behaviour is unchanged.

---

## 11. Errors

Errors use [API conventions §4](./api-conventions.md#4-standard-error-responses): `{ "timestamp", "status", "code", "message", "path", "fieldErrors?" }`. Branch on `code`, not on the wording of `message`.

| Status | Code | When |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | Invalid query parameter or id. Malformed preferences body, unknown or duplicate category, or an attempt to turn off a locked category. |
| 400 | `VALIDATION_ERROR` | `preferences` missing or empty, or `category` missing |
| 401 | `AUTHENTICATION_ERROR` | Not logged in or the access token expired: refresh and retry. For the stream, see [§4](#4-real-time-stream-sse) |
| 403 | `ACCESS_DENIED` | Watch endpoints: a workspace admin who is not a member of the project |
| 404 | `RESOURCE_NOT_FOUND` | Notification missing or not yours. Open: target gone or no longer visible. Watch: project not visible, or task missing or deleted |

| Endpoint | Possible errors |
| --- | --- |
| `GET /notifications` | 400, 401 |
| `GET /notifications/unread-count` | 401 |
| `POST /notifications/{id}/open` | 400, 401, 404 |
| `POST /notifications/read-all` | 401 |
| `GET /notifications/stream` | 401 |
| `GET /notification-preferences` | 401 |
| `PUT /notification-preferences` | 400, 401 |
| `GET / PUT / DELETE …/tasks/{taskId}/watch` | 400, 401, 403, 404 |

---

## 12. Frontend flows

The frontend implementation is **PMP-114** (Amir), and acceptance testing is **PMP-115** (Damir).

1. **After login:** call `GET /notifications/unread-count` and open the stream ([§4](#4-real-time-stream-sse)). Show the count on the bell, and hide the badge at `0`.
2. **Bell dropdown:** on open, load `GET /notifications?page=0&size=10`.
   - Unread items are highlighted.
   - Each item shows `message`, the `excerpt` if any, the workspace or project name, and the relative time.
   - Use "Load more" or a full page with pagination for older items. New items arrive at the top while the list is open: prepend stream events and deduplicate by `id`.
3. **Clicking an item:** call `POST /notifications/{id}/open`, then:
   - on `TASK`, navigate to the task;
   - on `PROJECT`, navigate to the project;
   - on `NONE`, stay (the item becomes read);
   - on `404`, show the "Not found" page.

   In every case mark it read locally. The stream's `unread-count` follows.
4. **"Mark all as read":** call `POST /notifications/read-all` and set every loaded item to read.
5. **Preferences page** (route `app.notifications.preferences-path`, linked from every email):
   - Build the table from `GET /notification-preferences`, using its order and its labels for your own text.
   - Show locked rows as on and disabled.
   - Save each toggle with `PUT /notification-preferences` carrying just that one change, and render the returned matrix.
   - "Reset to defaults" sends every unlocked category with its `defaultInApp` and `defaultEmail`.
6. **Watch toggle** on the task detail page: `GET …/watch` on load, then `PUT` or `DELETE` on click.
7. **Workspace filter** (optional): the full notifications page may offer a workspace dropdown that sends `workspaceId`.

---

## 13. Implementation status

As of 4 Nov 2026, on branch `features/notifications`:

| Part | Status |
| --- | --- |
| This contract | Written, agreed with the backend owner |
| Migration `V9__notifications.sql`, entities, repositories | Done; applied to PostgreSQL by the context test (`ddl-auto=validate` passes) |
| Preferences (defaults, locked categories, endpoints) | Done; `NotificationPreferenceServiceTest`, `NotificationPreferenceControllerTest`, `NotificationRequestValidationTest` |
| Watching (auto-watch, endpoints, cleanup on leaving) | Done; `TaskWatcherServiceTest`, `TaskWatchControllerTest`, plus the cleanup checks in `ProjectServiceTest` and `WorkspaceServiceTest`. The native watcher queries run on PostgreSQL in `TaskIntegrationTest` |
| Events and listener (every type in §5, including the future ones) | Done; `NotificationListenerTest`, `NotificationServiceTest`, `NotificationMessagesTest` |
| Producers: task assign and status, project member and roles, invitation, password reset, repeated failed logins | Done; `TaskApiServiceTest`, `TaskStatusHistoryServiceTest`, `ProjectServiceTest`, `InvitationServiceTest`, `AuthServiceTest`, `LoginFailureTrackerTest` |
| List, count, open, read-all | Done; `NotificationServiceTest`, `NotificationControllerTest` |
| PostgreSQL integration test | Done; `NotificationIntegrationTest` (7): assignment writes the notification, email and watchers in the action's transaction and a rollback removes them; bell order, workspace filter, count and read-all; open after leaving answers 404 and stays read (fails without `noRollbackFor`); someone else's notification; outbox due query; both cleanups; security notifications without a workspace |
| SSE stream | Done; `NotificationStreamServiceTest`, `NotificationControllerTest` |
| Email outbox and retries | Done; `NotificationEmailsTest`, `EmailOutboxServiceTest`, `EmailOutboxWorkerTest`, `EmailTemplateFactoryTest` |
| Nightly 90-day cleanup | Done; `NotificationServiceTest`, `EmailOutboxServiceTest` |
| Full suite (`clean verify`) | **581 passed, 0 failed** (4 Nov 2026, with Docker, Testcontainers tests included) |
| Manual end-to-end run with Mailpit (the acceptance criteria) | Not done yet |
| Producers for comments (PMP-133) and mentions (PMP-136) | Done (5 Nov 2026), against [comments-docs.md](./comments-docs.md) and [mentions-docs.md](./mentions-docs.md). Posting publishes `TaskCommented`; editing a comment and creating or editing a task or subtask description publish `Mentioned` with the newly added ids only |
| Chat mentions (PMP-137) | Done (6 Nov 2026). `ChatService` publishes `ProjectChatMentioned` on send and edit, with the newly added ids only; `NotificationListenerTest`, `NotificationEmailsTest`, and `MentionIntegrationTest` end to end on PostgreSQL |
| Producers for sprints (PMP-119, PMP-122) | Waiting for those features. They only publish the event ([§15](#15-notes-for-backend-developers)) |
| Frontend (PMP-114) and acceptance test (PMP-115) | Not started |

---

## 14. Decisions

| Date | Decision |
| --- | --- |
| 3 Nov 2026 | Notifications are an Observer-pattern consumer. Producers publish a Spring application event, and a synchronous listener writes the notifications and the queued emails **in the action's transaction**, so a rollback removes them. Stream pushes happen only after commit. |
| 3 Nov 2026 | Real time is Server-Sent Events, authenticated by the existing `access_token` cookie. It is not WebSocket/STOMP; the chat (PMP-137) may still use STOMP separately. One backend instance, so the open streams are kept in memory. |
| 3 Nov 2026 | One bell per user across all workspaces, with an optional workspace filter. |
| 3 Nov 2026 | Defaults: in-app on for everything; email on only for assignment and mention. Invitation and security are locked on. Only differences from the defaults are stored, so new users need no setup. |
| 3 Nov 2026 | Security notifications: password reset, and 5 failed logins within 15 minutes. |
| 3 Nov 2026 | The workspace invitation email stays inline (a send failure still answers `503`, see workspaces-docs.md). The in-app invitation notification is informational only, because the join token stays in the email. |
| 3 Nov 2026 | Unwatching deletes the watcher row. Being assigned again, or commenting again, re-watches. |
| 3 Nov 2026 | After losing access, old notifications stay listed and open to `404`. Their text never holds more than names, keys, titles and a short excerpt. |
| 3 Nov 2026 | Deleting a board column sends one status-change notification per moved task. |
| 3 Nov 2026 | You are never notified about your own actions. A mention replaces the comment notification for the same person. |
| 3 Nov 2026 | Emails go through a persisted outbox with retries (1 min, 5 min, 15 min, 1 h, 6 h, then `FAILED`). Frontend routes in emails are configuration properties. |
| 3 Nov 2026 | Retention: 90 days for notifications and finished outbox rows, cleaned nightly. This is a team choice. |
| 3 Nov 2026 | `SPRINT_CANCELLED` is defined next to started and completed, because PMP-41 notifies members on cancel too. Comment, mention and sprint types are defined now, so those features need no migration. |
| 6 Nov 2026 | A chat mention is its own event, `ProjectChatMentioned`, not `Mentioned` with an optional task: a chat has no task, and `Mentioned` keeps its task. It reuses the `MENTIONED` type and the `MENTION` preference (no migration), and the notification is about the project. |

---

## 15. Notes for backend developers

**Producing a notification.** Inject `ApplicationEventPublisher` and publish the event record from the service method that performs the action. Do it **after** the change has succeeded and **inside** its `@Transactional` method. Do not call notification services directly, and do not decide recipients in the producer. The listener resolves watchers, removes the actor, applies preferences and queues emails.

```java
// PMP-133, CommentService.create, after saving the comment:
events.publishEvent(new TaskCommented(task, user, comment.getId(), plainText, mentionedUserIds));

// PMP-136, mentions in a task description (only the newly added ids):
events.publishEvent(new Mentioned(task, user, newlyMentionedUserIds, plainText));

// PMP-137, mentions in a chat message (only the newly added ids):
events.publishEvent(new ProjectChatMentioned(project, user, messageId, newlyMentionedUserIds, plainText));

// PMP-119 / PMP-122, after the state change:
events.publishEvent(new SprintStarted(project, user, sprint.getName()));
events.publishEvent(new SprintCompleted(project, user, sprint.getName()));
events.publishEvent(new SprintCancelled(project, user, sprint.getName()));
```

Rules:
1. **Publish only real changes.** For example, "assigned to the same person" publishes nothing.
2. **`plainText`** is the plain text of the comment or description, with mention nodes rendered as `@Name`. The listener cuts it to the 200-character excerpt. Never pass rich-text JSON or HTML.
3. **`mentionedUserIds`** are user ids, with teams already expanded to their members in the project (PMP-136). The listener drops ids that are not project members and drops the actor. `TaskCommented` carries the mentions of the comment, so one event produces both the `MENTIONED` and the `TASK_COMMENTED` notifications without duplicates.
4. **Commenting auto-watches** the commenter. This is done by the listener on `TaskCommented`, so the producer does nothing extra.
5. **A new `type` or `category`** needs a migration for the check constraint, a row in [§5](#5-event-catalogue) or [§6](#6-preferences), and a line in [§14](#14-decisions).
6. **Removing someone from a project or workspace** must also delete their watcher rows there. `ProjectService.removeMember` and `WorkspaceService.removeMember` already do this; a new removal path must do the same.
7. **Notification emails go through the outbox**, never through `EmailService` directly. `NotificationEmails` builds them and `EmailOutboxService.enqueue` queues them; `EmailOutboxWorker` sends them.
8. **The stream request must never query the database.** Spring's open-in-view keeps that request's EntityManager, and any connection it borrows, for the whole life of the stream (up to 10 minutes). One query there would pin one pooled connection per open bell. `NotificationStreamService` reads the first count on a worker thread for this reason.
9. `NotificationCreated` and `UnreadCountChanged` are internal events of the stream; producers never publish them.

Code lives in `notification/{events,models,repositories,services,controllers,dto}`. The existing `notification/email/` module renders the emails.

| Class | Role |
| --- | --- |
| `NotificationListener` | the Observer: one `@EventListener` per event; recipients, auto-watch, preferences, both channels |
| `NotificationService` | creates rows (`MANDATORY`), list, count, open, read-all, nightly cleanup |
| `NotificationPreferenceService` | the matrix; `isEnabled(user, category, channel)` |
| `TaskWatcherService` | watch, unwatch, auto-watch |
| `NotificationStreamService` | the SSE streams; pushes after commit; keep-alive |
| `NotificationEmails` | subject, body, links of one email; queues it |
| `EmailOutboxService` / `EmailOutboxWorker` | the queue and its 15-second sender with retries |

---

## 16. Data model

| Table | Columns | Notes |
| --- | --- | --- |
| `notifications` | `id`, `recipient_id`, `type`, `workspace_id`, `workspace_name`, `project_id`, `project_key`, `project_name`, `task_id`, `task_key`, `task_title`, `actor_id`, `actor_name`, `message`, `excerpt`, `created_at`, `read_at` | `recipient_id` → `users` and `workspace_id` → `workspaces`, both **on delete cascade**. `project_id`, `task_id` and `actor_id` have **no foreign key** on purpose, so notifications outlive the things they mention (open then answers `404`). Check constraint on `type`. Indexes on `(recipient_id, created_at desc, id desc)` and a partial index on unread rows. |
| `notification_preferences` | `user_id`, `category`, `channel`, `enabled` | Primary key `(user_id, category, channel)`, `user_id` on delete cascade. A row exists only where the user differs from the default. Check constraints on `category` and `channel` (`IN_APP`, `EMAIL`). |
| `task_watchers` | `task_id`, `user_id`, `created_at` | Primary key `(task_id, user_id)`. Both FKs on delete cascade. |
| `email_outbox` | `id`, `recipient_email`, `subject`, `body_text`, `body_html`, `status`, `attempts`, `next_attempt_at`, `last_error`, `created_at`, `sent_at` | `status` is `PENDING`, `SENT` or `FAILED` (check constraint). Index on `(status, next_attempt_at)`. `last_error` holds the exception message only, never the body. |

- **Migration:** `V9__notifications.sql`.
- **Nightly cleanup (03:50 server time):** deletes notifications older than 90 days, and `SENT` or `FAILED` outbox rows older than 90 days.

---

## 17. Known limitations

- **Open streams are kept in memory.** This is fine with one backend instance on one VPS. Several instances would need a shared broker (for example Postgres `LISTEN/NOTIFY` or Redis).
- **No replay of missed stream events.** The list endpoint is the source of truth after a reconnect.
- **No digest or batching.** Deleting a column with 30 tasks sends 30 status-change notifications to their watchers (email is off for status changes by default).
- **No per-project or per-workspace preferences.**
- **Emails are in English only.**
- **No browser push or mobile notifications.** Only the bell and email.
- **A `FAILED` email is not resent automatically after its last retry.** It is visible only in the server log and the outbox table.
- **A rare duplicate email is possible.** If the database fails in the moment between the mail server accepting an email and the outbox row being marked `SENT`, the row stays `PENDING` and the email is sent again on the next run.
- **Changing preferences does not touch emails already queued.** They are still sent.