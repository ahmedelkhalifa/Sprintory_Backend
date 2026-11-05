# Sprintory — Audit log API contract (R05, PMP-24)

**Status:** backend implemented and unit-tested (PMP-128, branch `features/audit-log`). Not yet run against PostgreSQL: the integration test and the full `clean verify` are still open. See [§12 Implementation status](#12-implementation-status).
**Applies to:** backend `/api/v1/workspaces/{workspaceId}/audit-log/**`, and the actions in [§4](#4-action-catalogue) that write entries.
**Related:** [Workspaces](./workspaces-docs.md), [Teams](./teams-docs.md), [Projects](./projects-docs.md), [Roles and permissions](./roles-docs.md), [Tasks](./tasks-docs.md), [Authentication](./auth-endpoints.md), [API and Git Conventions](./api-conventions.md) (error shape, visibility), [Requirements](./requirements.md) R05.

This document is the single reference for the audit log: the backend implements it, and the frontend (PMP-129) builds the admin page against it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a workspace admin, I want to review a log of key actions, so that I can trace who changed what and when."*

- **One log per workspace.** Every entry belongs to exactly one workspace. An admin sees the entries of their own workspace only.
- **Read-only, for workspace admins.** Nothing else about the log is exposed. There is **no endpoint to create, edit or delete an entry**, for anyone, including admins. Entries are written by the backend as a side effect of the actions in [§4](#4-action-catalogue).
- **Append-only.** An entry never changes after it is written.
- **Only successful actions are logged.** The entry is written in the same database transaction as the action. If the action fails, is refused (400/403/404/409) or rolls back, there is no entry. A call that changes nothing (renaming to the same name, setting the same roles, adding someone already in the team) also writes nothing.
- **Names are snapshots.** Every entry stores the names of the actor, the target and the project *as they were at that moment*. A user who later leaves the workspace, or a project renamed later, still shows the old name in old entries. Display the stored names; do not look them up again.
- **Times are UTC.** `occurredAt` is an ISO-8601 instant ending in `Z`. Convert to the viewer's local time for display.
- **No secrets, ever.** Passwords, password hashes, tokens, invitation or reset links are never stored, so they can never appear in a response.
- **Newest first**, paginated, with filters for date range, actor, action (or area) and project.

### Who can do what

"Admin" is the workspace role `WORKSPACE_ADMIN`; "Member" is `WORKSPACE_MEMBER`. Project roles play no part: a Project Manager who is not a workspace admin gets `403`.

| Action | Not logged in | Not in the workspace | Member | Admin |
| --- | --- | --- | --- | --- |
| Read the log (`GET /audit-log`) | 401 | 404 | **403** | allowed |
| Read the actor list (`GET /audit-log/actors`) | 401 | 404 | **403** | allowed |
| Create, edit or delete an entry | no such endpoint | no such endpoint | no such endpoint | no such endpoint |

- A workspace that does not exist and one the caller is not in both give the same `404` ([API conventions §5](./api-conventions.md)).
- **Archived workspaces stay readable.** Reading the log of an archived workspace is allowed for its admins; no `409` is returned by these endpoints.
- To decide whether to show the "Audit log" menu entry, use `role` from `WorkspaceResponse` (`GET /api/v1/workspaces/{workspaceId}` or the workspace list): show it only when `role === "WORKSPACE_ADMIN"`. The backend enforces the rule anyway.

---

## 2. Endpoints at a glance

All paths are under `/api/v1`. Responses are JSON in `camelCase`. Both endpoints are `GET` and take no body.

| # | Method | Path | Query | Success | Who |
| --- | --- | --- | --- | --- | --- |
| 3.1 | GET | `/workspaces/{workspaceId}/audit-log` | `from`, `to`, `actorId`, `projectId`, `actions`, `areas`, `page`, `size` (all optional) | `200 PageResponse<AuditLogEntryResponse>` | workspace admin |
| 3.2 | GET | `/workspaces/{workspaceId}/audit-log/actors` | none | `200 AuditActorResponse[]` | workspace admin |

Any other method on these paths (`POST`, `PUT`, `PATCH`, `DELETE`) answers `405` with code `BAD_REQUEST`: the log cannot be changed through the API.

### Response bodies

```jsonc
// AuditLogEntryResponse
{
  "id": 412,                                   // stable, unique; use as the React key
  "occurredAt": "2026-11-02T14:05:31.218Z",    // UTC instant
  "action": "PROJECT_ROLES_CHANGED",           // one of §4
  "area": "PROJECT",                           // WORKSPACE | TEAM | PROJECT | TASK | SECURITY
  "actor": {                                   // null when the system acted (LOGIN_FAILED_REPEATED)
    "id": 17,
    "name": "Ada Lovelace",                    // snapshot at the time of the action
    "email": "ada@example.com"                 // snapshot at the time of the action
  },
  "target": {                                  // what the action was done to; never null
    "type": "USER",                            // WORKSPACE | TEAM | PROJECT | TASK | USER | INVITATION
    "id": 23,                                  // null only for INVITATION
    "name": "Linus Torvalds"                   // snapshot; for INVITATION the invited email
  },
  "project": {                                 // null for workspace, team and security actions
    "id": 15,
    "name": "Mobile App"                       // snapshot
  },
  "changes": [                                 // may be empty, never null; in a fixed order per action
    { "field": "roles", "oldValue": "MEMBER", "newValue": "PROJECT_MANAGER, MEMBER" }
  ]
}

// AuditActorResponse
{ "id": 17, "name": "Ada Lovelace", "email": "ada@example.com" }

// PageResponse<T> (shared, see workspaces-docs.md §2 "Pagination and search")
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 37, "totalPages": 2 }
```

---

## 3. Endpoints in detail

### 3.1 Read the audit log

`GET /api/v1/workspaces/{workspaceId}/audit-log`

- **Who:** an admin of this workspace. Member → `403`, not in the workspace → `404`.
- **Order:** newest first (`occurredAt` descending, then `id` descending, so entries written in the same millisecond keep a stable order and pages never overlap). The order cannot be changed; `?sort=` is ignored.
- **Filters:** all optional. They combine with **AND**: an entry is returned only if it matches every filter given. Inside `actions` and inside `areas`, the listed values combine with **OR**.

| Parameter | Format | Example | Rules |
| --- | --- | --- | --- |
| `from` | ISO-8601 UTC instant | `2026-11-01T00:00:00.000Z` | Only entries with `occurredAt >= from` (inclusive). Use exactly what JavaScript's `Date.prototype.toISOString()` produces. |
| `to` | ISO-8601 UTC instant | `2026-11-03T00:00:00.000Z` | Only entries with `occurredAt < to` (**exclusive**). |
| `actorId` | positive integer | `17` | Only entries whose actor is this user. Take the ids from [§3.2](#32-read-the-actor-list). Entries with no actor (system) never match. |
| `projectId` | positive integer | `15` | Only entries of this project. Workspace, team and security entries never match. |
| `actions` | comma-separated action names | `PROJECT_ROLES_CHANGED,WORKSPACE_ROLE_CHANGED` | Only these actions ([§4](#4-action-catalogue)). Case-insensitive, spaces around commas are ignored. |
| `areas` | comma-separated area names | `PROJECT,TASK` | Only actions of these areas. Same parsing as `actions`. |
| `page` | integer | `0` | 0-based, default `0`. A negative value is treated as `0`. |
| `size` | integer | `20` | Default `20`, at most `100`; a larger value is capped at `100`. |

- **Validation** (each gives `400 BAD_REQUEST` with a message naming the parameter):
  - `from` or `to` is not a valid instant: `Invalid 'from': use an ISO-8601 instant such as 2026-11-01T00:00:00Z.`
  - `from` is not before `to` (when both are given): `'from' must be before 'to'.`
  - `actorId` or `projectId` is zero, negative or not a number: `'actorId' must be a positive ID.` / `Invalid value for 'actorId'.`
  - an unknown value in `actions` or `areas`: `Invalid 'actions' filter: FOO.`
- **No leaks through filters:** an `actorId` or `projectId` that does not exist, or belongs to another workspace, is not an error. It simply matches nothing, and the response is an empty page.
- **Response `200`:** a `PageResponse<AuditLogEntryResponse>`. A page past the end returns `200` with an empty `content`.

Example: role changes in project 15 during 1–2 November (local midnight in UTC+3), first page.

```http
GET /api/v1/workspaces/4/audit-log?from=2026-10-31T21:00:00.000Z&to=2026-11-02T21:00:00.000Z&projectId=15&actions=PROJECT_ROLES_CHANGED&page=0&size=20
```

```json
{
  "content": [
    {
      "id": 412,
      "occurredAt": "2026-11-02T14:05:31.218Z",
      "action": "PROJECT_ROLES_CHANGED",
      "area": "PROJECT",
      "actor": { "id": 17, "name": "Ada Lovelace", "email": "ada@example.com" },
      "target": { "type": "USER", "id": 23, "name": "Linus Torvalds" },
      "project": { "id": 15, "name": "Mobile App" },
      "changes": [ { "field": "roles", "oldValue": "MEMBER", "newValue": "PROJECT_MANAGER, MEMBER" } ]
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

**Errors:** `401`, `404` (workspace missing or not yours), `403` (not an admin), `400` (invalid filter).

### 3.2 Read the actor list

`GET /api/v1/workspaces/{workspaceId}/audit-log/actors`

Feeds the "Actor" filter dropdown. It lists **everyone who appears as the actor of at least one entry in this workspace's log**, including people who have since left the workspace (the members endpoint no longer lists them, but their entries remain).

- **Who:** same as §3.1.
- **Response `200`:** a plain JSON array (not paginated), sorted by `name` (case-insensitive), then `id`. Each person appears once, with the name and email from their **most recent** entry. Empty array if the log is empty. The system actor (`actor: null`) is not listed.

```json
[
  { "id": 17, "name": "Ada Lovelace", "email": "ada@example.com" },
  { "id": 31, "name": "Grace Hopper", "email": "grace@example.com" },
  { "id": 23, "name": "Linus Torvalds", "email": "linus@example.com" }
]
```

**Errors:** `401`, `404`, `403`.

---

## 4. Action catalogue

Every `action` value, the `area` it belongs to, and what the entry contains. Values are stable: branch on them, and map them to your own labels.

**How to read `changes`:**
- Each change is `{ field, oldValue, newValue }`. Both values are **strings or `null`**, never numbers or arrays.
- `oldValue: null` means "did not exist before" (something was added or granted); `newValue: null` means "no longer exists" (something was removed).
- Role values are the raw enum names (`WORKSPACE_ADMIN`, `PROJECT_MANAGER`, …); map them to labels. A set of project roles is one string, comma-plus-space separated, in this fixed order: `PROJECT_MANAGER, PRODUCT_OWNER, SCRUM_MASTER, MEMBER, VIEWER`. Split on `", "` if you need the list.
- `description` values can be long (project descriptions are free text). Truncate them in the table and show the full text in a detail view or tooltip.

### 4.1 Workspace (`area: "WORKSPACE"`)

| `action` | Written when | `actor` | `target` | `project` | `changes` | Suggested sentence |
| --- | --- | --- | --- | --- | --- | --- |
| `WORKSPACE_RENAMED` | An admin renames the workspace (`PATCH /workspaces/{id}`) and the name actually changes | the admin | `WORKSPACE` | null | `name`: old → new | *Ada renamed the workspace from "Acme" to "Acme Corp"* |
| `MEMBER_INVITED` | An admin invites an email (`POST /workspaces/{id}/invitations`). One entry per invited email. Emails that already belong to members are skipped. Re-inviting the same email writes a new entry. | the admin | `INVITATION`, `id: null`, `name` = the invited email | null | `role`: null → `WORKSPACE_MEMBER` | *Ada invited bob@example.com* |
| `MEMBER_JOINED` | Someone accepts an invitation and becomes a member. Not written if they were already a member. | the person who joined | `USER` (the same person) | null | `role`: null → `WORKSPACE_MEMBER` | *Bob Smith joined the workspace* |
| `MEMBER_REMOVED` | An admin removes another member (`DELETE /workspaces/{id}/members/{userId}`) | the admin | `USER` (the removed person) | null | `role`: their role → null | *Ada removed Bob Smith from the workspace* |
| `MEMBER_LEFT` | A member removes themselves (the same endpoint with their own id) | the person who left | `USER` (the same person) | null | `role`: their role → null | *Bob Smith left the workspace* |
| `WORKSPACE_ROLE_CHANGED` | An admin changes a member's workspace role (`PATCH /workspaces/{id}/members/{userId}`) and the role actually changes | the admin | `USER` (whose role changed) | null | `role`: old → new | *Ada changed Bob Smith's workspace role from Member to Admin* |

Leaving or being removed also removes the person from the workspace's teams and projects and unassigns their tasks. Those follow-on removals are **not** logged separately; the single `MEMBER_REMOVED` / `MEMBER_LEFT` entry covers them.

### 4.2 Teams (`area: "TEAM"`)

| `action` | Written when | `actor` | `target` | `project` | `changes` | Suggested sentence |
| --- | --- | --- | --- | --- | --- | --- |
| `TEAM_CREATED` | An admin creates a team | the admin | `TEAM` | null | `name`: null → name | *Ada created the team "Backend"* |
| `TEAM_RENAMED` | An admin renames a team and the name actually changes | the admin | `TEAM` | null | `name`: old → new | *Ada renamed the team "Backend" to "Platform"* |
| `TEAM_DELETED` | An admin deletes a team | the admin | `TEAM` (`id` of the deleted team, `name` as it was) | null | `name`: name → null | *Ada deleted the team "Platform"* |
| `TEAM_MEMBER_ADDED` | An admin adds people to a team. One entry per person actually added; people already in the team are skipped. | the admin | `USER` (the added person) | null | `team`: null → team name | *Ada added Linus Torvalds to the team "Backend"* |
| `TEAM_MEMBER_REMOVED` | An admin removes a person from a team | the admin | `USER` (the removed person) | null | `team`: team name → null | *Ada removed Linus Torvalds from the team "Backend"* |

### 4.3 Projects (`area: "PROJECT"`)

For every project action, `project` is the project concerned.

| `action` | Written when | `actor` | `target` | `project` | `changes` | Suggested sentence |
| --- | --- | --- | --- | --- | --- | --- |
| `PROJECT_CREATED` | Someone creates a project | the creator | `PROJECT` (the new project) | the new project | `name`: null → name; `key`: null → key; `template`: null → `SCRUM` / `KANBAN` | *Ada created the project "Mobile App" (MOB, Scrum)* |
| `PROJECT_SETTINGS_CHANGED` | Someone edits the project settings (`PATCH /projects/{id}`) and at least one value actually changes | the editor | `PROJECT` | the project | Only the fields that changed, in this order: `name`: old → new; `description`: old → new (either side may be `null` = no description) | *Ada changed the project name from "Mobile" to "Mobile App"* |
| `PROJECT_MEMBER_ADDED` | Someone adds people to the project, directly (`POST /projects/{id}/members`) or through a team (`POST /projects/{id}/teams`). One entry per person actually added; people already in the project are skipped. | the person who added them | `USER` (the added person) | the project | `roles`: null → `MEMBER`; plus, when added through a team, `viaTeam`: null → team name | *Ada added Linus Torvalds to "Mobile App" (via team "Backend")* |
| `PROJECT_MEMBER_REMOVED` | Someone removes a person from the project (`DELETE /projects/{id}/members/{userId}`) | the person who removed them | `USER` (the removed person) | the project | `roles`: their roles → null | *Ada removed Linus Torvalds from "Mobile App"* |
| `PROJECT_ROLES_CHANGED` | Someone changes a member's project roles (`PUT /projects/{id}/members/{userId}/roles`) and the set actually changes | the person who changed them | `USER` (whose roles changed) | the project | `roles`: old set → new set | *Ada changed Linus Torvalds's roles in "Mobile App" from Member to Project Manager, Member* |

### 4.4 Tasks (`area: "TASK"`)

Only deletion and restoration are logged. Every other task change belongs to the task's own history, not the audit log.

| `action` | Written when | `actor` | `target` | `project` | `changes` | Suggested sentence |
| --- | --- | --- | --- | --- | --- | --- |
| `TASK_DELETED` | Someone deletes a task (`DELETE /projects/{id}/tasks/{taskId}`, a soft delete) | the person who deleted it | `TASK`, `name` = `"<KEY> <title>"`, e.g. `"MOB-42 Fix login"` | the project | none | *Ada deleted MOB-42 "Fix login" in "Mobile App"* |
| `TASK_RESTORED` | Someone restores a deleted task. **PMP-131:** restoration writes this action for an explicitly restored task or subtask. | the person who restored it | `TASK`, same name format | the project | none | *Ada restored MOB-42 "Fix login" in "Mobile App"* |

### 4.5 Security (`area: "SECURITY"`)

These events belong to a person, not to a workspace. **The same entry is written into every workspace the person is a member of at that moment**, so each of those workspaces' admins sees it. A person in no workspace leaves no visible entry.

| `action` | Written when | `actor` | `target` | `project` | `changes` | Suggested sentence |
| --- | --- | --- | --- | --- | --- | --- |
| `PASSWORD_RESET` | A person sets a new password through the "forgot password" link | the person themself | `USER` (the same person) | null | none | *Bob Smith reset their password* |
| `LOGIN_FAILED_REPEATED` | **5 failed logins (wrong password) within 15 minutes** for an existing account. One entry per burst of 5: the 10th failure in the window writes another one. | **null** (the system; nobody is signed in) | `USER` (the account) | null | `failedAttempts`: null → `"5"` | *5 failed sign-in attempts for Bob Smith* |

Notes on `LOGIN_FAILED_REPEATED`:
- Only wrong passwords on existing email-and-password accounts count. Unknown emails, Google-only accounts and unverified accounts do not count, and nothing is logged for them.
- A successful login resets the count.
- The count is kept in memory on the server. A server restart resets it, so a burst that spans a restart may not be logged (see [§10](#10-known-limitations)).
- The person who failed to log in sees the normal `401` and nothing else; logging never changes the login response.

### 4.6 Not logged

These actions write **no** audit entry (by decision, see [§13](#13-decisions)):
- workspace created, archived, restored or permanently deleted (a permanent delete removes the workspace's whole log with it);
- invitation link looked up, or expired invitations cleaned up;
- board column changes (PMP-28) and every task change other than delete and restore;
- successful logins, logouts, registration, email verification, token refresh, Google sign-in;
- any read.

---

## 5. Field reference

| Field | Type | Null? | Notes |
| --- | --- | --- | --- |
| `id` | number | never | Unique and increasing. |
| `occurredAt` | string (ISO-8601 UTC) | never | When the action committed. |
| `action` | string enum | never | [§4](#4-action-catalogue). |
| `area` | string enum | never | Always the area of `action`; the same value the `areas` filter uses. |
| `actor` | object | **yes**, only for `LOGIN_FAILED_REPEATED` | Show "System" when null. |
| `actor.id` | number | never | The user id. It stays valid for filtering after the person leaves. |
| `actor.name` | string | never | `firstName lastName` at the time. |
| `actor.email` | string | never | Email at the time. |
| `target.type` | string enum | never | `WORKSPACE`, `TEAM`, `PROJECT`, `TASK`, `USER`, `INVITATION`. |
| `target.id` | number | only for `INVITATION` | Do **not** turn a target into a link without checking it still exists: teams can be deleted, people can leave, tasks can be deleted. |
| `target.name` | string | never | Snapshot. Person: `firstName lastName`; task: `KEY title`; invitation: the email. |
| `project` | object | yes | Set for `PROJECT` and `TASK` area actions only. |
| `project.id`, `project.name` | number, string | never (when `project` is set) | Snapshot name. |
| `changes` | array | never (may be `[]`) | [§4](#4-action-catalogue) lists the fields per action. |

---

## 6. Errors

Errors use [API conventions §4](./api-conventions.md#4-standard-error-responses): `{ "timestamp", "status", "code", "message", "path", "fieldErrors?" }`. Branch on `code`, not on the wording of `message`.

| Status | Code | When |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | Invalid `from` / `to`, `from` not before `to`, non-positive or non-numeric `actorId` / `projectId`, unknown value in `actions` / `areas`, non-numeric `workspaceId` |
| 401 | `AUTHENTICATION_ERROR` | Not logged in, or the session expired (refresh and retry, as for every endpoint) |
| 403 | `ACCESS_DENIED` | Logged in and in the workspace, but not a workspace admin |
| 404 | `RESOURCE_NOT_FOUND` | The workspace does not exist, or the caller is not in it |
| 405 | `BAD_REQUEST` | Any method other than `GET` on the audit-log paths |

| Endpoint | Possible errors |
| --- | --- |
| `GET /workspaces/{id}/audit-log` | 400, 401, 403, 404 |
| `GET /workspaces/{id}/audit-log/actors` | 400 (non-numeric id), 401, 403, 404 |

---

## 7. Frontend flows

The frontend implementation is **PMP-129** (Aliaksandr); acceptance testing is **PMP-130** (Mobeen).

1. **Entry point.** Show an "Audit log" item in the workspace's settings/admin area only when the current workspace's `role` is `WORKSPACE_ADMIN`. If someone reaches the page by URL anyway, the API answers `403`: show an "Only workspace admins can view the audit log" page, not an error toast. On `404`, show the usual "workspace not found" page.
2. **First load.** Call `GET /audit-log?page=0&size=20` and `GET /audit-log/actors` in parallel. No filters means the whole log, newest first.
3. **Table columns** (suggested): time (local, with the full UTC time in a tooltip), actor (`actor.name`, or "System" when `actor` is null), action (your label for `action`), target (`target.name`), project (`project.name` or "—"), details (rendered `changes`). The suggested sentences in [§4](#4-action-catalogue) can replace the action/target/details columns in a compact or mobile view.
4. **Filter bar.** All filters are optional and combine with AND. When any filter changes, go back to `page=0`.
   - **Date range:** the user picks local calendar days. Send `from` = local start of the first day, and `to` = local start of the day **after** the last day, both converted with `toISOString()`. Example in UTC+3, "1 Nov – 2 Nov": `from=2026-10-31T21:00:00.000Z`, `to=2026-11-02T21:00:00.000Z`. Do not send `from` ≥ `to` (the API answers `400`); disable "Apply" instead.
   - **Actor:** a dropdown filled from `/audit-log/actors` (shows removed people too), sending `actorId`. Show `name` with `email` as secondary text, since names can repeat.
   - **Action type:** a multi-select grouped by area ([§4](#4-action-catalogue)). Selecting a whole area may send `areas=PROJECT`; selecting individual actions sends `actions=A,B`. Use the catalogue in this document as the list of values; there is no endpoint for it.
   - **Project:** a dropdown from `GET /workspaces/{workspaceId}/projects` (admins get every project), sending `projectId`.
   - Keep the filters in the page URL's query string so a filtered view can be reloaded or shared with another admin.
5. **Pagination.** Use `page`, `size`, `totalElements`, `totalPages` from the response. New entries appear at the top while someone is reading, so page 2 may repeat an entry from page 1 if the log grew in between; deduplicate by `id` if you implement "load more".
6. **Empty states.** No entries at all: "No activity has been recorded yet." No entries for the filters: "No entries match these filters", with a "Clear filters" button.
7. **Rendering `changes`.** For each change, show `field: oldValue → newValue`. When `oldValue` is null show only the new value ("added"); when `newValue` is null show the old value struck through ("removed"). Map role enum values to the labels used elsewhere in the app. Truncate long `description` values.
8. **Removed people and deleted things.** Always display the names stored in the entry. Never hide an entry because its actor or target no longer exists, and don't link to a target without handling a `404`.
9. **No edit or delete controls** anywhere on the page: the API has none, and the acceptance criteria require that it be impossible.

---

## 8. What is never logged or shown

- Passwords, password hashes, refresh or access tokens, verification/reset/invitation tokens, and any link containing them.
- The content of task descriptions, comments or chat messages (task titles appear only as part of the `TASK` target name).
- IP addresses and user agents (not collected; see [§10](#10-known-limitations)).

What the endpoints reveal, and to whom:

| Caller | Sees |
| --- | --- |
| Workspace admin | Every entry of their own workspace, including names of projects they are not in and task titles in `TASK_DELETED` / `TASK_RESTORED` entries |
| Workspace member | Nothing (`403`) |
| Anyone else | Nothing (`404`, the same as a workspace that does not exist) |

---

## 9. Data model

| Table | Columns | Notes |
| --- | --- | --- |
| `audit_log_entries` | `id`, `workspace_id`, `occurred_at` (timestamptz), `action`, `actor_id`, `actor_name`, `actor_email`, `target_type`, `target_id`, `target_name`, `project_id`, `project_name` | `workspace_id` → `workspaces` **on delete cascade** (a permanently deleted workspace takes its log with it). `actor_id` and `project_id` have **no foreign key** on purpose, so entries outlive the people and projects they mention. Check constraints on `action` and `target_type`. Indexes on `(workspace_id, occurred_at desc, id desc)`, `(workspace_id, actor_id)`, `(workspace_id, project_id)`. |
| `audit_log_changes` | `entry_id`, `sort_order`, `field`, `old_value`, `new_value` | Primary key `(entry_id, sort_order)`; `entry_id` → `audit_log_entries` on delete cascade. |

- **Migration:** `V8__audit_log.sql`. Adding an `action` value later needs a new migration that changes the check constraint.
- **Code:** `audit/{models,repositories,services,controllers,dto}`, plus `security/auth/services/LoginFailureTracker`. The entity is Hibernate `@Immutable` with every column `updatable = false`, and no code path updates or deletes an entry. Changes are batch-loaded (`@BatchSize`), so a page costs two queries.

---

## 10. Notes for backend developers

**Writing an entry.** Call `AuditLogService.record(...)` from the service method that performs the action, **after** the change has succeeded and **inside** the same `@Transactional` method. `record` uses `Propagation.MANDATORY`, so calling it without a transaction is an error rather than a silently separate commit. If the action later throws, the entry rolls back with it.

Rules:
1. Log only real changes. Return early (without `record`) on no-ops.
2. Take name snapshots **before** a delete or rename changes them.
3. Never pass a token, a password or a link into `changes`.
4. Pick an existing `AuditAction`. A new value needs a migration for the check constraint, a row in [§4](#4-action-catalogue), and a line in [§13](#13-decisions).

The signature is `record(workspace, actor, action, target, project, changes...)`:
- `actor` is the `User` who acted (null only for the system); `project` is null outside projects.
- Build `target` with the `AuditTarget` factories (`user`, `team`, `project`, `task`, `workspace`, `invitation`) so names look the same everywhere.
- Each change is `AuditChange.of(field, oldValue, newValue)`, with display strings.

```java
// TaskApiService.delete, after the soft delete:
auditLog.record(project.getWorkspace(), user, AuditAction.TASK_DELETED, AuditTarget.task(task), project);

// ProjectService.setRoles, after saving:
auditLog.record(project.getWorkspace(), user, AuditAction.PROJECT_ROLES_CHANGED,
        AuditTarget.user(target.getUser()), project, AuditChange.of("roles", oldRoles, newRoles));
```

**PMP-131 (task restore):** `TASK_RESTORED` is emitted after restoration, inside its `@Transactional`:

```java
auditLog.record(project.getWorkspace(), user, AuditAction.TASK_RESTORED, AuditTarget.task(task), project);
```

Restored subtasks need no entries of their own; the parent's entry covers them.

**Security events** use `recordInAllWorkspacesOf(subject, actor, action, changes...)`, which writes one entry per workspace the subject is a member of. Failed logins go through `LoginFailureTracker` (`security/auth/services`), which writes in its own transaction because the login it counts has none.

---

## 11. Known limitations

- **The failed-login counter is in memory.** It resets when the server restarts. This is acceptable with one backend instance on one VPS; several instances would need a shared store.
- **Project delete / restore / archive are not logged** because they do not exist yet (R30). They will get their own actions when built.
- **No IP address or device** is recorded for security events.
- **No export** (CSV/PDF) of the log.
- **No retention limit:** entries are kept as long as the workspace exists.

---

## 12. Implementation status

As of 2 Nov 2026, on branch `features/audit-log`:

| Part | Status |
| --- | --- |
| This contract | Written, agreed with the backend owner |
| Migration `V8__audit_log.sql`, entities, repository | Written; compiles. **Not yet applied to PostgreSQL** (needs the integration test or `clean verify` with Docker) |
| `AuditLogService.record` / `recordInAllWorkspacesOf` | Done; `AuditLogServiceTest` |
| Workspace and invitation entries | Done; `WorkspaceServiceTest` (42), `InvitationServiceTest` (18) |
| Team entries | Done; `TeamServiceTest` (27) |
| Project entries | Done; `ProjectServiceTest` (66) |
| Task delete entry | Done; `TaskApiServiceTest` (26). `TASK_RESTORED` is written by PMP-131 restoration |
| `PASSWORD_RESET` and `LOGIN_FAILED_REPEATED` | Done; `AuthServiceTest` (20), `LoginFailureTrackerTest` (7) |
| `GET /audit-log` and `GET /audit-log/actors` with filters | Done; `AuditLogServiceTest` (14 in total), `AuditLogFiltersTest` (6), `AuditLogControllerTest` (3) |
| Access-denied test required by the DoD | Done: `AuditLogServiceTest.aMemberIsDeniedTheLogAndNothingIsQueried` |
| Mocked test suite | **403 passed, 0 failed** (2 Nov 2026; Testcontainers tests excluded, Docker not running) |
| PostgreSQL integration test (rollback, filters, snapshots, cascade) | **Not written**: skipped on 2 Nov 2026 by the backend owner's decision. Run `clean verify` with Docker before merging |
| Frontend page (PMP-129) and acceptance test (PMP-130) | Not started |

Every service test also checks that a refused or no-op action writes no entry (acceptance criterion "an action fails or is refused, then no entry is created").

---

## 13. Decisions

| Date | Decision |
| --- | --- |
| 2 Nov 2026 | Only the actions in the PMP-24 table are logged. Workspace archive/restore, invitation revoke and board column changes are not. |
| 2 Nov 2026 | Entries are written by a direct call in the action's own transaction (no events), so a rollback removes them and refused actions leave nothing. No-op calls write nothing. |
| 2 Nov 2026 | Security events are written into every workspace the person belongs to. |
| 2 Nov 2026 | "Login failed (repeated)" means 5 wrong passwords within 15 minutes for an existing account, counted in memory, reset by a successful login; the actor is the system (`null`). This resolves the story's conflict with "only successful actions": the logged fact is the detected burst, not a single failure. |
| 2 Nov 2026 | Names are stored as snapshots; actor and project ids carry no foreign key, so entries survive people leaving and projects disappearing. |
| 2 Nov 2026 | Reading the log needs the workspace admin role (`WorkspaceAccess.requireAdmin`), not a project permission. Archived workspaces remain readable. |
| 2 Nov 2026 | An actors endpoint feeds the actor filter, because the members list no longer contains people who left. |
| 2 Nov 2026 | `TASK_RESTORED` is defined now so PMP-131 needs no migration. |
| 2 Nov 2026 | The story's criterion "a Member changes another member's project role" is read as "whoever holds `MANAGE_ROLES`" (the Project Manager or a workspace admin); members cannot change roles. |