# Sprintory — Teams API contract (R02)

**Status:** implemented. See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/workspaces/{workspaceId}/teams` and `/api/v1/teams/**`.
**Related:** [Workspaces](./workspaces-docs.md) (roles, archiving, pagination), [Projects](./projects-docs.md) (adding a team to a project), [API and Git Conventions](./api-conventions.md) (error shape §4, private resources §5), [Authentication](./auth-endpoints.md), [Requirements](./requirements.md) R02, R04.

This document is the single reference for how teams work end to end. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **A team is a named group of people inside one workspace**, for example "Backend" or "Design".
- **Only workspace members can be in a team.** A team never contains someone from outside its workspace.
- **A person can be in several teams** of the same workspace.
- **Only workspace admins manage teams:** create, rename, delete, add and remove people.
- **Teams are private.**
  - Admins see every team of their workspace.
  - A plain member sees only the teams they belong to.
  - Anyone else gets `404`, exactly as if the team did not exist.
- **Adding a team to a project** ([projects-docs.md §3.6](./projects-docs.md#36-add-a-team-to-a-project)) copies the team's current members into the project once. Later team changes do not change the project.
- **Archived workspaces are read-only.** Teams can still be listed and opened, but every change gets `409 WORKSPACE_ARCHIVED`.
- **Authentication** works as described in [auth-endpoints.md](./auth-endpoints.md). Every endpoint here needs the `access_token` cookie. Roles are the workspace roles from [workspaces-docs.md](./workspaces-docs.md#workspace-roles).

### Who can do what

The roles are the caller's role **in the team's workspace**.

| Action | Not logged in | Not in the workspace | Member, not in the team | Member, in the team | Admin |
| --- | --- | --- | --- | --- | --- |
| Create a team | 401 | 404 | 403 | 403 | allowed (active workspace) |
| List the workspace's teams | 401 | 404 | sees only their own teams | sees only their own teams | sees all teams |
| Get a team | 401 | 404 | 404 | allowed | allowed |
| List a team's members | 401 | 404 | 404 | allowed | allowed |
| Rename a team | 401 | 404 | 404 | 403 | allowed (active workspace) |
| Delete a team | 401 | 404 | 404 | 403 | allowed (active workspace) |
| Add people to a team | 401 | 404 | 404 | 403 | allowed (active workspace) |
| Remove a person from a team | 401 | 404 | 404 | 403 | allowed (active workspace) |

Why `404` for some members and `403` for others: someone who cannot see a team must not learn that it exists, so they get `404`. A team member can already see the team, so refusing a change with `403` reveals nothing new.

---

## 2. Endpoints at a glance

All paths are under `/api/v1`. Request and response bodies are JSON in `camelCase`.

| # | Method | Path | Request | Success |
| --- | --- | --- | --- | --- |
| 3.1 | POST | `/workspaces/{workspaceId}/teams` | `CreateTeamRequest` | `201` + `TeamResponse` |
| 3.2 | GET | `/workspaces/{workspaceId}/teams?q=&page=&size=` | none | `200` + `PageResponse<TeamResponse>` |
| 3.3 | GET | `/teams/{teamId}` | none | `200` + `TeamResponse` |
| 3.4 | PATCH | `/teams/{teamId}` | `UpdateTeamRequest` | `200` + `TeamResponse` |
| 3.5 | DELETE | `/teams/{teamId}` | none | `204` |
| 3.6 | GET | `/teams/{teamId}/members?q=&page=&size=` | none | `200` + `PageResponse<TeamMemberResponse>` |
| 3.7 | POST | `/teams/{teamId}/members` | `AddTeamMembersRequest` | `204` |
| 3.8 | DELETE | `/teams/{teamId}/members/{userId}` | none | `204` |

Creating and listing sit under the workspace because a team belongs to exactly one. Everything else is addressed by the team's own id.

### Path variables

| Variable | Where it comes from |
| --- | --- |
| `{workspaceId}` | `WorkspaceResponse.id` (numeric, not the slug) |
| `{teamId}` | `TeamResponse.id` |
| `{userId}` | `TeamMemberResponse.userId` or `WorkspaceMemberResponse.userId` |

A non-numeric value, for example `/teams/abc`, gets `400 BAD_REQUEST` "Invalid value for 'teamId'."

### Pagination and search

The two lists (3.2 and 3.6) use the shared rules from [workspaces-docs.md, Pagination and search](./workspaces-docs.md#pagination-and-search):

| Query parameter | Default | Rules |
| --- | --- | --- |
| `page` | `0` | 0-based. A negative value is treated as `0`. |
| `size` | `20` | At most `100`; a larger value is capped. |
| `q` | none | Optional, case-insensitive "contains". Blank means no search. |
| `sort` | — | Ignored. Each list has a fixed order. |

```jsonc
// PageResponse<T>
{
  "content": [ /* items of type T */ ],
  "page": 0,
  "size": 20,
  "totalElements": 37,   // after the search and the visibility rule
  "totalPages": 2
}
```

A page past the end returns `200` with an empty `content`.

### Bodies

```jsonc
// CreateTeamRequest, UpdateTeamRequest
{ "name": "Backend" }

// AddTeamMembersRequest
{ "userIds": [7, 12, 15] }

// TeamResponse
{ "id": 4, "workspaceId": 3, "name": "Backend" }

// TeamMemberResponse: public profile data only
{ "userId": 7, "firstName": "Ada", "lastName": "Lovelace", "email": "ada@example.com" }
```

| Field | Rules |
| --- | --- |
| `name` | Required, not blank, at most 100 characters. Leading and trailing spaces are removed before saving. **Unique within the workspace**; other workspaces may use the same name. Names differing only in case ("Backend" and "backend") are different names. |
| `userIds` | Required, 1 to 100 ids, none `null`. Duplicates in the list count once. Every id must belong to a member of the team's workspace. |

Passwords, password hashes, tokens and provider are never part of a response body.

---

## 3. Endpoints in detail

### 3.1 Create a team

`POST /api/v1/workspaces/{workspaceId}/teams`

Creates an empty team in the workspace.

- **Who:** an admin of the workspace. The workspace must be active.
- **Request:**
  ```json
  { "name": "Backend" }
  ```
- **Response `201`:**
  ```json
  { "id": 4, "workspaceId": 3, "name": "Backend" }
  ```
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `name` missing, blank or longer than 100 characters |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is a member but not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Workspace not found." | the workspace does not exist, or the caller is not in it |
  | 409 | `CONFLICT` | a team with this name already exists in the workspace |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

### 3.2 List a workspace's teams

`GET /api/v1/workspaces/{workspaceId}/teams?q=&page=&size=`

One page of teams, sorted by name.

- **Who:** any member of the workspace.
  - **Admins** get every team of the workspace.
  - **Plain members** get only the teams they belong to. `totalElements` counts only those.
- **Search:** `q` matches the team name, e.g. `?q=back` finds "Backend" and "Backoffice".
- **Archived workspace:** allowed.
- **Response `200`:**
  ```json
  {
    "content": [
      { "id": 4, "workspaceId": 3, "name": "Backend" },
      { "id": 9, "workspaceId": 3, "name": "Design" }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
  ```
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 404 | `RESOURCE_NOT_FOUND` "Workspace not found." | the workspace does not exist, or the caller is not in it |

### 3.3 Get a team

`GET /api/v1/teams/{teamId}`

- **Who:** an admin of the team's workspace, or a member of this team.
- **Archived workspace:** allowed.
- **Response `200`:**
  ```json
  { "id": 4, "workspaceId": 3, "name": "Backend" }
  ```
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 404 | `RESOURCE_NOT_FOUND` "Team not found." | the team does not exist, the caller is not in its workspace, or the caller is a plain member who is not in this team |

### 3.4 Rename a team

`PATCH /api/v1/teams/{teamId}`

- **Who:** an admin of the team's workspace. The workspace must be active.
- **Request:**
  ```json
  { "name": "Platform" }
  ```
- **Response `200`:** the team with its new name.
  ```json
  { "id": 4, "workspaceId": 3, "name": "Platform" }
  ```
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `name` missing, blank or longer than 100 characters |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this team but is not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Team not found." | as in 3.3 |
  | 409 | `CONFLICT` | another team in the workspace already has this name |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

Renaming a team to its current name succeeds and changes nothing.

### 3.5 Delete a team

`DELETE /api/v1/teams/{teamId}`

Deletes the team and its member list. **It cannot be undone.**

- **Who:** an admin of the team's workspace. The workspace must be active.
- **What is kept:**
  - every person stays in the workspace
  - project access is kept, because adding a team to a project copied its members into the project
- **Response:** `204`, no body.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this team but is not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Team not found." | as in 3.3, including a team that was already deleted |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

### 3.6 List a team's members

`GET /api/v1/teams/{teamId}/members?q=&page=&size=`

One page of the team's members, sorted by first name, then last name.

- **Who:** an admin of the team's workspace, or a member of this team.
- **Search:** `q` matches first name, last name or email, e.g. `?q=ada`.
- **Archived workspace:** allowed.
- **Response `200`:**
  ```json
  {
    "content": [
      { "userId": 7, "firstName": "Ada", "lastName": "Lovelace", "email": "ada@example.com" },
      { "userId": 12, "firstName": "Grace", "lastName": "Hopper", "email": "grace@example.com" }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
  ```
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 404 | `RESOURCE_NOT_FOUND` "Team not found." | as in 3.3 |

### 3.7 Add people to a team

`POST /api/v1/teams/{teamId}/members`

- **Who:** an admin of the team's workspace. The workspace must be active.
- **Request:**
  ```json
  { "userIds": [7, 12, 15] }
  ```
- **Rules:**
  - **All or nothing.** Every id must belong to a member of the team's workspace. If even one does not (unknown user, or someone outside the workspace), the request is refused with `400` and **nobody** is added.
  - People already in the team are skipped silently, so sending the same list twice is safe.
  - Duplicate ids in the list count once.
- **Response:** `204`, no body. Reload 3.6 to show the result.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `userIds` missing, empty, longer than 100, or containing `null` |
  | 400 | `BAD_REQUEST` "Some users are not members of this workspace." | at least one id is not a member of the team's workspace |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this team but is not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Team not found." | as in 3.3 |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

The `400` does not say which ids were refused. That is deliberate: naming them would tell an admin whether a user id exists outside their workspace.

### 3.8 Remove a person from a team

`DELETE /api/v1/teams/{teamId}/members/{userId}`

- **Who:** an admin of the team's workspace. The workspace must be active.
- **Rules:**
  - The person stays in the workspace, and keeps any project access they already have.
  - Removing someone who is not in the team changes nothing and still returns `204`.
  - An admin may remove themselves from a team; as an admin they can still see and manage it.
- **Response:** `204`, no body.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `BAD_REQUEST` | `userId` is not a number |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this team but is not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Team not found." | as in 3.3 |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

---

## 4. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses):

```json
{
  "timestamp": "2026-10-23T12:30:00Z",
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "message": "Team not found.",
  "path": "/api/v1/teams/42"
}
```

**The frontend should branch on `code`, not on `message`.** Messages are safe to show, but they may be reworded.

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Request validation failed. | A body breaks the rules in §2. `fieldErrors` names the field, e.g. `{ "name": "must not be blank" }` or `{ "userIds[2]": "must not be null" }`. |
| `BAD_REQUEST` | 400 | Malformed request body. | The body is not valid JSON, or a value has the wrong type (e.g. `"userIds": ["seven"]`) |
| `BAD_REQUEST` | 400 | Invalid value for 'teamId'. | A path variable is not a number |
| `BAD_REQUEST` | 400 | Some users are not members of this workspace. | 3.7 with an id outside the workspace |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie. Try `POST /auth/refresh` first (auth §4.4). |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | A non-admin tries a change they are not allowed to make |
| `RESOURCE_NOT_FOUND` | 404 | Workspace not found. | 3.1 and 3.2: the workspace does not exist, or the caller is not in it |
| `RESOURCE_NOT_FOUND` | 404 | Team not found. | 3.3 to 3.8: the team does not exist or the caller cannot see it |
| `CONFLICT` | 409 | That value is already in use. | 3.1 and 3.4: the name is taken in this workspace |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Any change while the workspace is archived |
| `INTERNAL_ERROR` | 500 | Something went wrong. Please try again. | Unexpected server error; details go to the server log only |

---

## 5. Frontend flows

### 5.1 Teams page of a workspace

1. Call `GET /workspaces/{id}` to get the caller's `role`.
2. Call `GET /workspaces/{id}/teams?page=0&size=20`. Use `q` for a search box and `page` for paging.
3. Show **Create team**, **Rename**, **Delete** and **Manage members** only when `role` is `WORKSPACE_ADMIN`. Hiding them is for usability; the backend enforces it either way.
4. A plain member's list contains only their own teams. An empty list means they are in no team yet.

### 5.2 Team page

1. `GET /teams/{teamId}` for the name, and `GET /teams/{teamId}/members` for the people.
2. `404` means the team was deleted or the user may not see it. Send the user back to the teams page.

### 5.3 Adding people (admin)

1. Open a picker fed by `GET /workspaces/{workspaceId}/members?q=...`, so the admin can search a large workspace by name or email.
2. Send the chosen ids with `POST /teams/{teamId}/members { userIds }`.
3. On `204`, reload the member list.
4. On `400 BAD_REQUEST`, someone left the workspace in the meantime. Reload the picker and try again.

### 5.4 Removing and deleting (admin)

- Removing a person: `DELETE /teams/{teamId}/members/{userId}`, then reload the member list.
- Deleting a team: ask for confirmation first, then `DELETE /teams/{teamId}` and go back to the teams page. Make clear that people keep their workspace and project access.

### 5.5 Archived workspace

When `WorkspaceResponse.archivedAt` is set, show teams read-only and hide every change control. A change that gets through anyway is refused with `409 WORKSPACE_ARCHIVED`.

---

## 6. Data model

| Table | Columns | Constraints |
| --- | --- | --- |
| `teams` | `id`, `workspace_id`, `name` (≤ 100), audit columns | unique on (`workspace_id`, `name`): many teams per workspace, one of each name |
| `team_members` | `id`, `team_id`, `user_id`, audit columns | unique on (`team_id`, `user_id`): the same person cannot be added twice |

- The audit columns are `created_at`, `updated_at`, `created_by`, `updated_by`, from `BaseEntity`.
- All foreign keys are `ON DELETE CASCADE` (`@OnDelete` in the entities):
  - deleting a **workspace** deletes its teams and their member rows
  - deleting a **team** deletes its member rows
  - deleting a **user** deletes their team memberships
- `team_members` is its own entity (`TeamMember`) rather than a `@ManyToMany`, like `workspace_members`, so both foreign keys cascade.
- Code lives in `team/{models,repositories,services,controllers,dto}`. Access checks are in `TeamService` and build on `WorkspaceAccess.findMember` (see [workspaces-docs.md, Access checks in code](./workspaces-docs.md#access-checks-in-code)).

---

## 7. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| `GET /teams/{id}` and its nested paths | Nothing about teams the caller cannot see. A team in another workspace, or another team in the caller's own workspace, gives the same `404` as a missing one. |
| `GET /workspaces/{id}/teams` | A plain member sees only their own teams; `totalElements` counts only those, so the number of other teams is not revealed. |
| `POST /teams/{id}/members` | Not which ids were refused, so it does not reveal whether a user id exists outside the workspace. |

---

## 8. Known limitations

- **Leaving the workspace does not remove team memberships yet.** Removing people from a workspace arrives with story 6, which must also remove them from that workspace's teams.
- **Names differing only in case are allowed**, e.g. "Backend" and "backend" in the same workspace.

---

## 9. Implementation status

As of 23 Oct 2026, on branch `features/workspaces-teams-projects`:

| Part | Status |
| --- | --- |
| `Team`, `TeamMember` tables and repositories | Implemented |
| `TeamService`: create, list, get, rename, delete, list/add/remove members, visibility rules | Implemented |
| `TeamController`: the eight endpoints in §2 | Implemented |
| Pagination and search on both lists | Implemented |
| Unit tests (`TeamServiceTest`, `TeamControllerTest`) | Pending (piece 8) |
| Check against a real database (context test with Docker) | Pending |

---

## 10. Decisions

| Date | Decision |
| --- | --- |
| 23 Oct 2026 | Teams are visible to workspace admins and to their own members only. Others get `404`; a team member who is not an admin gets `403` on changes. |
| 23 Oct 2026 | Only workspace admins create, rename and delete teams and manage their members. |
| 23 Oct 2026 | Team names are unique within a workspace, at most 100 characters. |
| 23 Oct 2026 | Adding people is all or nothing; everyone must already be a workspace member. People already in the team are skipped. |
| 23 Oct 2026 | Deleting a team is a hard delete of the group only. Nobody loses workspace or project access. |
| 23 Oct 2026 | Adding a team to a project copies its current members once; later team changes do not affect the project. |
| 23 Oct 2026 | Both team lists are paginated (`page`/`size`, max 100, fixed order) with a `?q=` search. |