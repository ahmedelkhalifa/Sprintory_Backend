# Sprintory — Workspaces API contract (R02)

**Status:** workspaces and invitations implemented. See [§10 Implementation status](#10-implementation-status).
**Applies to:** backend `/api/v1/workspaces/**` and `/api/v1/invitations/**`, plus the rules every feature inside a workspace builds on (roles, archiving, pagination).
**Related:** [Teams](./teams-docs.md), [Projects](./projects-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private resources §5), [Authentication](./auth-endpoints.md), [Email service](./email-service.md), [Requirements](./requirements.md) R02, R04.

This document is the single reference for how workspaces work end to end. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **A workspace is the top-level container** for an organization. Teams ([teams-docs.md](./teams-docs.md)) and projects ([projects-docs.md](./projects-docs.md)) live inside it.
- **Any logged-in user can create a workspace** and becomes its admin. A user can belong to many workspaces.
- **Roles are per workspace, not per user.** The same person can be an admin in one workspace and a member in another. The role is stored on the membership row (`workspace_members`), not on the user.
- **People join only by invitation.** An admin invites an email address. The person clicks the emailed link and joins as a member, registering first if they have no account.
- **Private by default.** Someone who is not a member of a workspace gets `404`, exactly as if it did not exist. A member who lacks the admin role gets `403`.
- **Archive before delete.** Archiving makes a workspace read-only and can be undone. A permanent delete is only allowed on an archived workspace.
- **Authentication** works as described in [auth-endpoints.md](./auth-endpoints.md). Every endpoint here needs the `access_token` cookie, except `POST /invitations/lookup`.

### Workspace roles

| Role | Can do |
| --- | --- |
| `WORKSPACE_ADMIN` | Everything a member can, plus rename, archive, restore and delete the workspace, invite people, see and manage every team, see every project and manage its membership |
| `WORKSPACE_MEMBER` | See the workspace and its member list, see the teams and projects they belong to, create projects |

Story 6 adds role changes (promote a member to admin, "at least one admin" rule) and project roles.

### Who can do what

| Action | Not logged in | Not a member | Member | Admin |
| --- | --- | --- | --- | --- |
| Create a workspace | 401 | allowed | allowed | allowed |
| List my workspaces | 401 | allowed (own list) | allowed | allowed |
| Get a workspace, list its members | 401 | 404 | allowed | allowed |
| Rename | 401 | 404 | 403 | allowed (active only) |
| Archive, restore | 401 | 404 | 403 | allowed |
| Delete permanently | 401 | 404 | 403 | allowed (archived only) |
| Invite people | 401 | 404 | 403 | allowed (active only) |
| Look up an invitation | allowed | allowed | allowed | allowed |
| Accept an invitation | 401 | allowed if the email matches | allowed if the email matches | allowed if the email matches |

For teams and projects, see [teams-docs.md, Who can do what](./teams-docs.md#who-can-do-what) and [projects-docs.md, Who can do what](./projects-docs.md#who-can-do-what).

---

## 2. Endpoints

All paths are under `/api/v1`. Request and response bodies are JSON in `camelCase`.

| Method | Path | Request | Success | Who |
| --- | --- | --- | --- | --- |
| POST | `/workspaces` | `CreateWorkspaceRequest` | `201` + `WorkspaceResponse` | any logged-in user |
| GET | `/workspaces` | none | `200` + `WorkspaceResponse[]` | any logged-in user |
| GET | `/workspaces/{workspaceId}` | none | `200` + `WorkspaceResponse` | member |
| GET | `/workspaces/{workspaceId}/members?q=&page=&size=` | none | `200` + `PageResponse<WorkspaceMemberResponse>` | member |
| PATCH | `/workspaces/{workspaceId}` | `UpdateWorkspaceRequest` | `200` + `WorkspaceResponse` | admin, active workspace |
| POST | `/workspaces/{workspaceId}/archive` | none | `200` + `WorkspaceResponse` | admin |
| POST | `/workspaces/{workspaceId}/restore` | none | `200` + `WorkspaceResponse` | admin |
| DELETE | `/workspaces/{workspaceId}` | none | `204` | admin, archived workspace |
| POST | `/workspaces/{workspaceId}/invitations` | `InviteMembersRequest` | `201` + `InvitationsSentResponse` | admin, active workspace |
| POST | `/invitations/lookup` | `InvitationTokenRequest` | `200` + `InvitationLookupResponse` | anyone, **no login needed** |
| POST | `/invitations/accept` | `InvitationTokenRequest` | `200` + `WorkspaceResponse` | logged in, email must match the invitation |

`{workspaceId}` is the numeric id from `WorkspaceResponse.id`, not the slug.

The team endpoints (`/workspaces/{workspaceId}/teams` and `/teams/**`) are documented in [teams-docs.md](./teams-docs.md), and the project endpoints (`/workspaces/{workspaceId}/projects` and `/projects/**`) in [projects-docs.md](./projects-docs.md).

### Request bodies

```jsonc
// CreateWorkspaceRequest
{ "name": "Acme Engineering" }

// UpdateWorkspaceRequest (rename)
{ "name": "Acme Platform Team" }

// InviteMembersRequest
{ "emails": ["grace@example.com", "Linus@Example.com "] }

// InvitationTokenRequest (lookup and accept)
{ "token": "<value of ?token= from the invitation link>" }
```

| Field | Rules |
| --- | --- |
| `name` | required, not blank, at most 100 characters. Leading and trailing spaces are removed before saving. |
| `emails` | required, 1 to 50 entries. Each entry must be a valid, non-blank email. Each is trimmed and lower-cased, and duplicates in the list are sent only once. |
| `token` | required, not blank |

### Response bodies

```jsonc
// WorkspaceResponse: a workspace as seen by the caller, so it carries the caller's role
{
  "id": 3,
  "name": "Acme Engineering",
  "slug": "acme-engineering",
  "role": "WORKSPACE_ADMIN",
  "archivedAt": null              // ISO 8601 UTC instant once archived, e.g. "2026-10-23T09:15:00Z"
}

// WorkspaceMemberResponse
{ "userId": 7, "firstName": "Ada", "lastName": "Lovelace", "email": "ada@example.com", "role": "WORKSPACE_MEMBER" }

// InvitationsSentResponse
{
  "invited": ["grace@example.com", "linus@example.com"],   // emailed now
  "alreadyMembers": ["ada@example.com"]                     // skipped, no email sent
}

// InvitationLookupResponse
{ "workspaceName": "Acme Engineering", "email": "grace@example.com" }
```

- `GET /workspaces` is sorted by workspace name and is **not** paginated, because a person belongs to only a few workspaces. It **includes archived workspaces**; use `archivedAt` to show them separately.
- `GET /workspaces/{id}/members` is paginated and searchable by first name, last name or email (see below), sorted by first name, then last name.
- Passwords, password hashes, tokens and provider are never part of a response body.

### Pagination and search

Every list that can grow large is paginated the same way across the API: workspace members here, teams and team members ([teams-docs.md](./teams-docs.md)), and projects and project members ([projects-docs.md](./projects-docs.md)). This section is the shared definition.

| Query parameter | Default | Rules |
| --- | --- | --- |
| `page` | `0` | 0-based page number. A negative value is treated as `0`. |
| `size` | `20` | Items per page, at most `100`. A larger value is capped at `100`. |
| `q` | none | Optional search, where the list supports it. Case-insensitive "contains". Blank means no search. |
| `sort` | — | **Ignored.** Each list has a fixed order. |

```jsonc
// PageResponse<T>
{
  "content": [ /* items of type T, for example WorkspaceMemberResponse */ ],
  "page": 0,            // the page returned, 0-based
  "size": 20,           // the page size used
  "totalElements": 37,  // items across all pages (after the search, if any)
  "totalPages": 2
}
```

- `GET /workspaces/{id}/members?q=ada` matches first name, last name or email. Each list documents what its `q` matches.
- A page past the end returns `200` with an empty `content`; `totalElements` and `totalPages` still describe the whole list.
- Searches only cover what the caller is allowed to see. A search can never reveal anything a plain list would not.
- An app-wide search (one box across workspaces, teams, projects and tasks) is planned for after tasks exist. It will combine these per-list searches.

---

## 3. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses):

```json
{
  "timestamp": "2026-10-23T12:30:00Z",
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "message": "Workspace not found.",
  "path": "/api/v1/workspaces/42"
}
```

**The frontend should branch on `code`, not on `message`.** Messages are safe to show, but they may be reworded.

### Error codes used by workspaces

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Request validation failed. | A request body breaks the rules in §2. `fieldErrors` names the field, e.g. `{ "name": "must not be blank" }` or `{ "emails[1]": "must be a well-formed email address" }`. |
| `BAD_REQUEST` | 400 | Malformed request body. | The body is not valid JSON, or a value has the wrong type |
| `BAD_REQUEST` | 400 | Invalid value for 'workspaceId'. | A path value is not a number, e.g. `GET /workspaces/abc`. The name in quotes is the path variable. |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie. Try `POST /auth/refresh` first (see auth §4.4). |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | A member tries an admin action, **or** someone accepts an invitation that was sent to a different email |
| `RESOURCE_NOT_FOUND` | 404 | Workspace not found. | The workspace does not exist **or** the caller is not a member. Identical on purpose. |
| `RESOURCE_NOT_FOUND` | 404 | Invitation not found or expired. | The invitation link is unknown, already used, replaced by a newer one, expired, or its workspace was deleted |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Any change to an archived workspace: rename, invite, accepting an invitation, team changes, creating projects and changing their membership |
| `WORKSPACE_NOT_ARCHIVED` | 409 | Archive the workspace before deleting it. | `DELETE` on an active workspace |
| `CONFLICT` | 409 | That value is already in use. | Two workspaces with the same name created at the same instant ended up with the same slug. Retrying succeeds. |
| `SERVICE_UNAVAILABLE` | 503 | We couldn't send the email. Please try again later. | An invitation email could not be sent. **No invitation from that request was kept**, so the admin can retry the whole list. |
| `INTERNAL_ERROR` | 500 | Something went wrong. Please try again. | Unexpected server error; details go to the server log only |

### What each endpoint can return

| Endpoint | Possible errors |
| --- | --- |
| `POST /workspaces` | 400 `VALIDATION_ERROR`, 401, 409 `CONFLICT` |
| `GET /workspaces` | 401 |
| `GET /workspaces/{id}` | 401, 404 |
| `GET /workspaces/{id}/members` | 401, 404 |
| `PATCH /workspaces/{id}` | 400 `VALIDATION_ERROR`, 401, 403, 404, 409 `WORKSPACE_ARCHIVED` |
| `POST /workspaces/{id}/archive` | 401, 403, 404 |
| `POST /workspaces/{id}/restore` | 401, 403, 404 |
| `DELETE /workspaces/{id}` | 401, 403, 404, 409 `WORKSPACE_NOT_ARCHIVED` |
| `POST /workspaces/{id}/invitations` | 400 `VALIDATION_ERROR`, 401, 403, 404, 409 `WORKSPACE_ARCHIVED`, 503 |
| `POST /invitations/lookup` | 400 `VALIDATION_ERROR`, 404 |
| `POST /invitations/accept` | 400 `VALIDATION_ERROR`, 401, 403 (email mismatch), 404, 409 `WORKSPACE_ARCHIVED` |

Any endpoint with a JSON body or a numeric path variable can also return 400 `BAD_REQUEST`, and any endpoint can return 500 `INTERNAL_ERROR`.

---

## 4. Flows

### 4.1 Creating a workspace

1. The frontend sends `POST /workspaces { name }`.
2. The backend:
   1. trims the name
   2. generates a slug from it (§5)
   3. saves the workspace
   4. adds the caller as its `WORKSPACE_ADMIN`
3. Response: `201` with the workspace. `role` is `WORKSPACE_ADMIN`, `archivedAt` is `null`.
4. The new workspace is empty: no other members, no teams, no projects.

### 4.2 Opening workspaces

- After login, the frontend calls `GET /workspaces` to show the user's workspaces and their role in each.
- To open one, it calls `GET /workspaces/{id}` and `GET /workspaces/{id}/members`.
- Show admin-only controls (rename, invite, archive, delete) only when `role` is `WORKSPACE_ADMIN`. Hiding them is for usability only; the backend enforces it either way.
- A `404` means the workspace is gone or the user was never in it. Send the user back to their list.

### 4.3 Renaming

1. `PATCH /workspaces/{id} { name }`. Admin only, and the workspace must be active.
2. Only the name changes. **The slug never changes**, so links that contain it keep working.

### 4.4 Archiving, restoring and deleting

1. **Archive:** `POST /workspaces/{id}/archive`.
   - Sets `archivedAt` to now. Archiving an already archived workspace changes nothing and still returns `200`.
   - Members can still open the workspace and read everything in it.
   - Every change is refused with `409 WORKSPACE_ARCHIVED`: renaming, inviting, accepting an invitation, team changes, and project changes.
   - Pending invitation links stay in the database, but accepting them is refused until the workspace is restored.
2. **Restore:** `POST /workspaces/{id}/restore`.
   - Clears `archivedAt`. Restoring an active workspace changes nothing and still returns `200`.
   - Everything works again. Invitations that have not expired yet can be accepted again.
3. **Delete permanently:** `DELETE /workspaces/{id}`.
   - Only for an **archived** workspace; otherwise `409 WORKSPACE_NOT_ARCHIVED`.
   - Removes the workspace and everything inside it: memberships, invitations, teams, projects, and later their tasks. **It cannot be undone.**
   - The frontend should ask for confirmation, for example by making the admin type the workspace name.
   - Response: `204`.

There is no waiting period between archive and delete in this sprint.

### 4.5 Inviting people

1. An admin sends `POST /workspaces/{id}/invitations { emails: [...] }`. The workspace must be active.
2. For each address, after trimming, lower-casing and removing duplicates:
   1. if it belongs to someone who is **already a member** → skipped, listed in `alreadyMembers`, no email
   2. otherwise:
      - any earlier invitation to this address for this workspace is deleted, so its link stops working
      - a new invitation is created, valid for **7 days**
      - an email is sent with the link `FRONTEND_URL + INVITE_PATH + "?token=" + token`, which is `/accept-invite?token=...` by default
3. The address does **not** need to have an account. Invitations are addressed to an email, not to a user.
4. If any email cannot be sent, the whole request is rolled back and the response is `503`. No invitation from that request is kept, and links in emails that did go out do not work. The admin retries the whole list.
5. Response: `201` with `invited` and `alreadyMembers`.

The email reads "{Inviter} invited you to join the {Workspace} workspace on Sprintory", with a **Join workspace** button, and says the invitation expires in 7 days.

### 4.6 Accepting an invitation (frontend page `INVITE_PATH`)

The emailed link opens the frontend's **accept-invite page** with `?token=...`.

1. The page reads `token` from the URL and sends `POST /invitations/lookup { token }`. This works without login.
   - `200`: show "Join {workspaceName}?" and the invited `email`.
   - `404`: show "This invitation has expired or was already used."
2. The page then checks whether the user is logged in (`GET /auth/me`).
3. **Logged in with the invited email:** send `POST /invitations/accept { token }`.
   - `200`: the user is now a `WORKSPACE_MEMBER`. The response is the workspace; open it.
   - If the user was already a member, accepting still returns `200` and simply uses the invitation up.
4. **Logged in with a different email:** accept returns `403`. Tell the user the invitation was sent to `email`, and offer to log out and continue with the right account.
5. **Not logged in, and the person has an account:** keep the token (for example in `sessionStorage`), send them to login, then come back and call accept.
6. **Not logged in, and the person has no account:** keep the token, send them to register **with the invited email**. They follow the normal flow from auth §4.1 (register, verify email, log in), then the page calls accept.
   - Registration does not know about the invitation. Using a different email at registration leads to the `403` in step 4.
   - Google sign-in works the same way, as long as the Google account's email is the invited one.
7. An invitation works once. After it is accepted, the link returns `404`.

---

## 5. Slugs

The slug is a URL-friendly version of the name, generated once at creation and never changed.

| Rule | Example |
| --- | --- |
| Lower-cased (with `Locale.ROOT`, so a Turkish system locale does not change `I`) | `Acme` → `acme` |
| Accents removed | `Çalışma Alanı` → `calisma-alani` |
| Turkish dotless `ı` becomes `i` | `Işık` → `isik` |
| Every run of characters other than `a-z` and `0-9` becomes one `-` | `R&D / Ops` → `r-d-ops` |
| No `-` at the start or end | `--Team--` → `team` |
| At most 100 characters, the same as the name | |
| Nothing left after the rules above | `!!!` → `workspace` |
| Already taken | second `Acme` → `acme-2`, third → `acme-3` |

Slugs are unique across all workspaces. The API addresses workspaces by numeric id; the slug is for display and for friendly frontend URLs.

---

## 6. Data model

| Table | Columns | Notes |
| --- | --- | --- |
| `workspaces` | `id`, `name` (≤ 100), `slug` (≤ 100, unique, never updated), `archived_at` (null while active), audit columns | |
| `workspace_members` | `id`, `workspace_id`, `user_id`, `role` (`WORKSPACE_ADMIN` / `WORKSPACE_MEMBER`), audit columns | unique on (`workspace_id`, `user_id`) |
| `workspace_invitations` | `id`, `workspace_id`, `email`, `token_hash` (SHA-256, unique), `expires_at`, audit columns | unique on (`workspace_id`, `email`) |

Team tables are described in [teams-docs.md §6](./teams-docs.md#6-data-model), project tables in [projects-docs.md §6](./projects-docs.md#6-data-model).

- The audit columns are `created_at`, `updated_at`, `created_by`, `updated_by`, from `BaseEntity`.
- Every foreign key to a workspace or user is `ON DELETE CASCADE`. Deleting a workspace deletes its memberships, invitations, teams and projects (with their member rows) in the same statement; deleting a user deletes their memberships.
- **Every future table under a workspace (tasks, sprints, comments) must also use `@OnDelete(action = OnDeleteAction.CASCADE)`**, or a permanent workspace delete fails on the foreign key.
- Invitation tokens follow the same rules as the auth link tokens: 32 random bytes in the link, only the SHA-256 hash in the database, deleted on use.
- Expired invitations are removed by a nightly job at 03:40, server time.

### Access checks in code

All workspace rules go through `WorkspaceAccess`, which later features (teams, projects) reuse:

| Method | Use for | Fails with |
| --- | --- | --- |
| `requireMember` | reads | 404 |
| `requireAdmin` | archive, restore, delete | 404, 403 |
| `requireActiveMember` | changes any member may make | 404, 409 `WORKSPACE_ARCHIVED` |
| `requireActiveAdmin` | changes only an admin may make | 404, 403, 409 `WORKSPACE_ARCHIVED` |
| `findMember` | features with their own 404 message, e.g. teams answer "Team not found." instead of "Workspace not found." | nothing, returns empty |

---

## 7. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| `GET /workspaces/{id}` and every nested path | Nothing about workspaces the caller is not in: missing and private both give the same `404` |
| `POST /workspaces/{id}/invitations` | To an admin: which invited addresses are already members of **their own** workspace. It does not reveal whether an address has a Sprintory account. |
| `POST /invitations/lookup` | To whoever holds a valid link: the workspace name and the invited address. The link was emailed to that address, so this tells the holder nothing they could not read in the email. |

---

## 8. Emails

Sent through `EmailService` (see [email-service.md](./email-service.md)). Delivery is synchronous SMTP.

| Email | Sent by | Link |
| --- | --- | --- |
| You're invited to join {Workspace} on Sprintory (expires in 7 days) | `POST /workspaces/{id}/invitations` | `FRONTEND_URL + INVITE_PATH?token=...` |

For local development, use Mailpit as described in [auth-endpoints.md §8](./auth-endpoints.md#8-emails).

---

## 9. Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `FRONTEND_URL` | `http://localhost:5173` | Base of the invitation link (shared with auth) |
| `INVITE_PATH` | `/accept-invite` | Frontend route that handles the invitation link |

Fixed in `application.properties`:
- invitation lifetime: `app.workspace.invite-expiration-ms=604800000` (7 days). The email text says "7 days", so change both together.

Give every variable in `.env` a value. An empty value defeats its default.

---

## 10. Implementation status

As of 23 Oct 2026, on branch `features/workspaces-teams-projects`:

| Part | Status |
| --- | --- |
| `Workspace`, `WorkspaceMember` and `WorkspaceInvitation` tables | Implemented |
| Slug generation (`SlugGenerator`) | Implemented |
| Per-workspace roles; `User.role` removed | Implemented |
| `WorkspaceAccess` checks (404 / 403 / 409 archived) | Implemented |
| Create, list, get, members, rename, archive, restore, delete (`WorkspaceService`, `WorkspaceController`) | Implemented |
| Invite, lookup, accept, nightly cleanup (`InvitationService`, `InvitationController`) | Implemented |
| Invitation email template | Implemented |
| Pagination (`PageResponse`) and search on the workspace member list | Implemented |
| Teams | Implemented, see [teams-docs.md §9](./teams-docs.md#9-implementation-status) |
| Projects (minimal) | Implemented, see [projects-docs.md §9](./projects-docs.md#9-implementation-status) |
| Unit tests: `SlugGeneratorTest` (12), `WorkspaceServiceTest` (20), `InvitationServiceTest` (17), `WorkspaceControllerTest` (10, includes invitations), `WorkspaceRequestValidationTest` (6), `ExceptionHandlerTest` (5) | Implemented, all passing |
| Database test against PostgreSQL: `WorkspaceDataIntegrationTest` (9) — searches, paging, visibility queries, invitation replacement, workspace delete cascade | Implemented, all passing |
| End-to-end check against a running app with Mailpit | Pending |

### Known limitations

- **No way to leave or remove people yet.** Removing a member from a workspace, and changing a member's role, arrive with story 6.
- **No "at least one admin" rule yet.** Story 6 adds it together with role changes. Until then the creator stays the only admin.
- **Archived pending invitations still count.** Archiving does not delete pending invitations, so they become usable again after a restore if they have not expired.

---

## 11. Decisions

| Date | Decision |
| --- | --- |
| 23 Oct 2026 | Workspace roles live on `workspace_members`, one per person per workspace. `User.role` is removed and logins carry no global role. |
| 23 Oct 2026 | Any logged-in user can create a workspace and becomes its admin. |
| 23 Oct 2026 | Non-members get `404`, members without permission get `403`, following [API conventions §5](./api-conventions.md#5-private-projects-and-access-control) over the "access forbidden" wording in the story. |
| 23 Oct 2026 | The slug is generated from the name, at most 100 characters, and never changes. Renaming keeps it. |
| 23 Oct 2026 | Invitations are addressed to emails, valid for 7 days, single use. Inviting again replaces the old link. Accepting requires the logged-in email to match. |
| 23 Oct 2026 | An invitee without an account registers through the normal flow; the frontend keeps the token and calls accept afterwards. |
| 23 Oct 2026 | Workspaces are archived first (read-only, restorable) and can be deleted permanently only once archived. No waiting period this sprint. |
| 23 Oct 2026 | Workspace deletion is a hard delete through `ON DELETE CASCADE`. Task soft delete (story 9) and project archiving (R30) are separate. |
| 23 Oct 2026 | Adding a team to a project copies the team's current members once; later team changes do not affect the project. |
| 23 Oct 2026 | Every list that can grow large is paginated with one shared `PageResponse` shape (`page`/`size`, max 100, fixed order, client `sort` ignored). Lists get a per-list `?q=` search; an app-wide search is deferred until tasks exist. No JPA Specifications until a list needs several optional filters (tasks, story 9). |