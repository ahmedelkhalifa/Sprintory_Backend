# Sprintory — Projects API contract (R02, minimal)

**Status:** minimal projects and project membership implemented. Story 8 (R06) adds the project key, the Scrum/Kanban template and settings; story 6 (R03) adds project roles. See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/workspaces/{workspaceId}/projects` and `/api/v1/projects/**`.
**Related:** [Workspaces](./workspaces-docs.md) (roles, archiving, pagination), [Teams](./teams-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Authentication](./auth-endpoints.md), [Requirements](./requirements.md) R02, R04, R06.

This document is the single reference for how projects and their membership work end to end. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **A project lives inside one workspace.** For now it has only a name. Story 8 turns it into a Scrum or Kanban project with a key and a board.
- **Any member of a workspace can create a project** and becomes its first member.
- **Membership is what grants access.** Only the project's members can see it, plus the workspace's admins, who manage membership.
- **Only workspace admins manage membership** in this story: add people, add a whole team, remove people. Story 6 also lets the project's Project Manager do it.
- **Adding a team copies its current members once.** The project does not stay linked to the team: people who join the team later are not added, and nobody is removed when they leave the team.
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Anyone who may not see a project gets `404`, exactly as if it did not exist.
- **Archived workspaces are read-only.** Projects can still be listed and opened, but every change gets `409 WORKSPACE_ARCHIVED`.
- **Authentication** works as described in [auth-endpoints.md](./auth-endpoints.md). Every endpoint here needs the `access_token` cookie. Roles are the workspace roles from [workspaces-docs.md](./workspaces-docs.md#workspace-roles).

### Who can do what

The roles are the caller's role **in the project's workspace**.

| Action | Not logged in | Not in the workspace | Member, not in the project | Member, in the project | Admin |
| --- | --- | --- | --- | --- | --- |
| Create a project | 401 | 404 | allowed (active workspace) | allowed (active workspace) | allowed (active workspace) |
| List the workspace's projects | 401 | 404 | sees only their own projects | sees only their own projects | sees all projects |
| Get a project | 401 | 404 | 404 | allowed | allowed |
| List a project's members | 401 | 404 | 404 | allowed | allowed |
| Add people to a project | 401 | 404 | 404 | 403 | allowed (active workspace) |
| Add a team to a project | 401 | 404 | 404 | 403 | allowed (active workspace) |
| Remove a person from a project | 401 | 404 | 404 | 403 | allowed (active workspace) |

Why `404` for some members and `403` for others: someone who cannot see a project must not learn that it exists, so they get `404`. A project member can already see the project, so refusing a change with `403` reveals nothing new.

**Admins and project work.** Story 7 says a workspace admin manages any project's members and settings but does not see the project's *work* (tasks, comments, attachments, sprints) unless they are a member. Today a project has no work yet, so admins see everything in this document. From story 9 on, task endpoints must check project membership, not the admin role.

---

## 2. Endpoints at a glance

All paths are under `/api/v1`. Request and response bodies are JSON in `camelCase`.

| # | Method | Path | Request | Success |
| --- | --- | --- | --- | --- |
| 3.1 | POST | `/workspaces/{workspaceId}/projects` | `CreateProjectRequest` | `201` + `ProjectResponse` |
| 3.2 | GET | `/workspaces/{workspaceId}/projects?q=&page=&size=` | none | `200` + `PageResponse<ProjectResponse>` |
| 3.3 | GET | `/projects/{projectId}` | none | `200` + `ProjectResponse` |
| 3.4 | GET | `/projects/{projectId}/members?q=&page=&size=` | none | `200` + `PageResponse<ProjectMemberResponse>` |
| 3.5 | POST | `/projects/{projectId}/members` | `AddProjectMembersRequest` | `204` |
| 3.6 | POST | `/projects/{projectId}/teams` | `AddTeamToProjectRequest` | `204` |
| 3.7 | DELETE | `/projects/{projectId}/members/{userId}` | none | `204` |

Creating and listing sit under the workspace because a project belongs to exactly one. Everything else is addressed by the project's own id.

Not available yet, on purpose: renaming or editing a project and its settings (story 8), archiving a project (R30), deleting a single project, and project roles (story 6).

### Path variables

| Variable | Where it comes from |
| --- | --- |
| `{workspaceId}` | `WorkspaceResponse.id` (numeric, not the slug) |
| `{projectId}` | `ProjectResponse.id` |
| `{userId}` | `ProjectMemberResponse.userId` or `WorkspaceMemberResponse.userId` |

A non-numeric value, for example `/projects/abc`, gets `400 BAD_REQUEST` "Invalid value for 'projectId'."

### Pagination and search

The two lists (3.2 and 3.4) use the shared rules from [workspaces-docs.md, Pagination and search](./workspaces-docs.md#pagination-and-search):

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
  "totalElements": 12,   // after the search and the visibility rule
  "totalPages": 1
}
```

A page past the end returns `200` with an empty `content`.

### Bodies

```jsonc
// CreateProjectRequest
{ "name": "Mobile App" }

// AddProjectMembersRequest
{ "userIds": [7, 12, 15] }

// AddTeamToProjectRequest
{ "teamId": 4 }

// ProjectResponse
{ "id": 21, "workspaceId": 3, "name": "Mobile App" }

// ProjectMemberResponse: public profile data only (story 6 adds roles)
{ "userId": 7, "firstName": "Ada", "lastName": "Lovelace", "email": "ada@example.com" }
```

| Field | Rules |
| --- | --- |
| `name` | Required, not blank, at most 100 characters. Leading and trailing spaces are removed before saving. **Not unique**: two projects may share a name. Story 8's project key is the unique identifier. |
| `userIds` | Required, 1 to 100 ids, none `null`. Duplicates in the list count once. Every id must belong to a member of the project's workspace. |
| `teamId` | Required. A team of the **same** workspace as the project. |

Passwords, password hashes, tokens and provider are never part of a response body.

---

## 3. Endpoints in detail

### 3.1 Create a project

`POST /api/v1/workspaces/{workspaceId}/projects`

Creates a project in the workspace and makes the caller its first member.

- **Who:** any member of the workspace, admin or not. The workspace must be active.
- **Request:**
  ```json
  { "name": "Mobile App" }
  ```
- **Response `201`:**
  ```json
  { "id": 21, "workspaceId": 3, "name": "Mobile App" }
  ```
- **After creation:** the caller appears in 3.4. Story 8 will also make them the project's Project Manager.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `name` missing, blank or longer than 100 characters |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 404 | `RESOURCE_NOT_FOUND` "Workspace not found." | the workspace does not exist, or the caller is not in it |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

### 3.2 List a workspace's projects

`GET /api/v1/workspaces/{workspaceId}/projects?q=&page=&size=`

One page of projects, sorted by name.

- **Who:** any member of the workspace.
  - **Admins** get every project of the workspace.
  - **Plain members** get only the projects they are a member of. `totalElements` counts only those, and projects they are not in never appear, with or without a search (story 7).
- **Search:** `q` matches the project name, e.g. `?q=mob` finds "Mobile App".
- **Archived workspace:** allowed.
- **Response `200`:**
  ```json
  {
    "content": [
      { "id": 21, "workspaceId": 3, "name": "Mobile App" },
      { "id": 22, "workspaceId": 3, "name": "Website" }
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

### 3.3 Get a project

`GET /api/v1/projects/{projectId}`

- **Who:** an admin of the project's workspace, or a member of this project.
- **Archived workspace:** allowed.
- **Response `200`:**
  ```json
  { "id": 21, "workspaceId": 3, "name": "Mobile App" }
  ```
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | the project does not exist, the caller is not in its workspace, or the caller is a plain member who is not in this project |

### 3.4 List a project's members

`GET /api/v1/projects/{projectId}/members?q=&page=&size=`

One page of the project's members, sorted by first name, then last name.

- **Who:** an admin of the project's workspace, or a member of this project.
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
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | as in 3.3 |

### 3.5 Add people to a project

`POST /api/v1/projects/{projectId}/members`

The story's "add people to project": the admin selects people and they are added.

- **Who:** an admin of the project's workspace. The workspace must be active.
- **Request:**
  ```json
  { "userIds": [7, 12, 15] }
  ```
- **Rules:**
  - **All or nothing.** Every id must belong to a member of the project's workspace. If even one does not (unknown user, or someone outside the workspace), the request is refused with `400` and **nobody** is added.
  - People already in the project are skipped silently, so sending the same list twice is safe.
  - Duplicate ids in the list count once.
- **Response:** `204`, no body. Reload 3.4 to show the result.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `userIds` missing, empty, longer than 100, or containing `null` |
  | 400 | `BAD_REQUEST` "Some users are not members of this workspace." | at least one id is not a member of the project's workspace |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this project but is not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | as in 3.3 |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

The `400` does not say which ids were refused, so it does not reveal whether a user id exists outside the workspace.

### 3.6 Add a team to a project

`POST /api/v1/projects/{projectId}/teams`

The story's "add team to project": all the team's members are assigned to the project.

- **Who:** an admin of the project's workspace. The workspace must be active.
- **Request:**
  ```json
  { "teamId": 4 }
  ```
- **Rules:**
  - Everyone **currently** in the team becomes a project member. People already in the project are skipped.
  - It is a **one-time copy**, not a link:
    - people who join the team later are not added to the project
    - people who leave the team, or a deleted team, do not lose project access
    - to bring new team members in, add the team again; it only adds the missing people
  - An empty team changes nothing and still returns `204`.
  - The team must belong to the project's workspace. A team from another workspace gets the same `400` as a team that does not exist.
- **Response:** `204`, no body. Reload 3.4 to show the result.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `teamId` missing |
  | 400 | `BAD_REQUEST` "Team not found in this workspace." | the team does not exist, or belongs to another workspace |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this project but is not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | as in 3.3 |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

### 3.7 Remove a person from a project

`DELETE /api/v1/projects/{projectId}/members/{userId}`

- **Who:** an admin of the project's workspace. The workspace must be active.
- **Rules:**
  - The person loses access to the project **immediately**: their next request to it gets `404` (story 7).
  - They stay in the workspace and in their teams.
  - Removing someone who is not in the project changes nothing and still returns `204`.
  - The last member can be removed. Story 6 adds the rule that a project must keep at least one Project Manager.
- **Response:** `204`, no body.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `BAD_REQUEST` | `userId` is not a number |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this project but is not an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | as in 3.3 |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

---

## 4. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses):

```json
{
  "timestamp": "2026-10-23T12:30:00Z",
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "message": "Project not found.",
  "path": "/api/v1/projects/42"
}
```

**The frontend should branch on `code`, not on `message`.** Messages are safe to show, but they may be reworded.

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Request validation failed. | A body breaks the rules in §2. `fieldErrors` names the field, e.g. `{ "name": "must not be blank" }` or `{ "teamId": "must not be null" }`. |
| `BAD_REQUEST` | 400 | Malformed request body. | The body is not valid JSON, or a value has the wrong type (e.g. `"teamId": "four"`) |
| `BAD_REQUEST` | 400 | Invalid value for 'projectId'. | A path variable is not a number |
| `BAD_REQUEST` | 400 | Some users are not members of this workspace. | 3.5 with an id outside the workspace |
| `BAD_REQUEST` | 400 | Team not found in this workspace. | 3.6 with a missing team or a team of another workspace |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie. Try `POST /auth/refresh` first (auth §4.4). |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | A project member who is not an admin tries to change membership |
| `RESOURCE_NOT_FOUND` | 404 | Workspace not found. | 3.1 and 3.2: the workspace does not exist, or the caller is not in it |
| `RESOURCE_NOT_FOUND` | 404 | Project not found. | 3.3 to 3.7: the project does not exist or the caller cannot see it |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Any change while the workspace is archived |
| `INTERNAL_ERROR` | 500 | Something went wrong. Please try again. | Unexpected server error; details go to the server log only |

---

## 5. Frontend flows

### 5.1 Projects list of a workspace

1. Call `GET /workspaces/{id}` to get the caller's `role`.
2. Call `GET /workspaces/{id}/projects?page=0&size=20`. Use `q` for a search box and `page` for paging.
3. Show **Create project** to every member of an active workspace.
4. A plain member's list contains only their own projects. An empty list means they are in no project yet.

### 5.2 Project page (members, for now)

1. `GET /projects/{projectId}` for the name, and `GET /projects/{projectId}/members` for the people.
2. Show **Add people**, **Add team** and **Remove** only when the caller's workspace `role` is `WORKSPACE_ADMIN`. Hiding them is for usability; the backend enforces it either way.
3. `404` means the project does not exist or the user may not see it, for example because they were just removed. Send the user back to the projects list.

### 5.3 Adding people (admin)

1. Open a picker fed by `GET /workspaces/{workspaceId}/members?q=...`, so the admin can search a large workspace by name or email.
2. Send the chosen ids with `POST /projects/{projectId}/members { userIds }`.
3. On `204`, reload the member list.
4. On `400 BAD_REQUEST`, someone left the workspace in the meantime. Reload the picker and try again.

### 5.4 Adding a team (admin)

1. Open a picker fed by `GET /workspaces/{workspaceId}/teams?q=...`.
2. Send `POST /projects/{projectId}/teams { teamId }`.
3. On `204`, reload the member list.
4. Tell the admin this is a one-time copy: people who join the team later need the team added again.

### 5.5 Archived workspace

When `WorkspaceResponse.archivedAt` is set, show projects read-only and hide **Create project** and every membership control. A change that gets through anyway is refused with `409 WORKSPACE_ARCHIVED`.

---

## 6. Data model

| Table | Columns | Constraints |
| --- | --- | --- |
| `projects` | `id`, `workspace_id`, `name` (≤ 100), audit columns | none on the name; story 8 adds a key unique within the workspace |
| `project_members` | `id`, `project_id`, `user_id`, audit columns | unique on (`project_id`, `user_id`): the same person cannot be added twice |

- The audit columns are `created_at`, `updated_at`, `created_by`, `updated_by`, from `BaseEntity`.
- All foreign keys are `ON DELETE CASCADE` (`@OnDelete` in the entities):
  - deleting a **workspace** deletes its projects and their member rows
  - deleting a **project** deletes its member rows
  - deleting a **user** deletes their project memberships
- There is no link between `projects` and `teams`; adding a team only writes `project_members` rows.
- Code lives in `project/{models,repositories,services,controllers,dto}`. Access checks are in `ProjectService` and build on `WorkspaceAccess.findMember` (see [workspaces-docs.md, Access checks in code](./workspaces-docs.md#access-checks-in-code)).
- **Every future table under a project (tasks, sprints, comments, attachments) must also use `@OnDelete(action = OnDeleteAction.CASCADE)`**, or a permanent workspace delete fails on the foreign key.

---

## 7. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| `GET /projects/{id}` and its nested paths | Nothing about projects the caller cannot see. A project in another workspace, or a project in the caller's own workspace that they are not in, gives the same `404` as a missing one. |
| `GET /workspaces/{id}/projects` | A plain member sees only their own projects, with or without `q`; `totalElements` counts only those. |
| `POST /projects/{id}/members` | Not which ids were refused, so it does not reveal whether a user id exists outside the workspace. |
| `POST /projects/{id}/teams` | Not whether a team id exists in another workspace: missing and foreign teams get the same `400`. |

---

## 8. What later stories add

| Story | Adds |
| --- | --- |
| Story 6 (R03) | Project roles on `project_members`: Project Manager, Product Owner, Scrum Master, Member/Developer, Viewer. The creator becomes Project Manager; people added without a role get Member (Developer in Scrum). Project Managers can manage membership too. "At least one Project Manager" rule. |
| Story 7 (R04) | Applies this document's visibility rule to everything inside a project. Admins manage membership but see project work only if they are members. |
| Story 8 (R06) | Project key (unique in the workspace), Scrum/Kanban template chosen at creation, default board columns, editing name and description, settings page. |
| Story 9 (R09) | Tasks inside projects; task endpoints must check project membership. |
| R30 | Completing and archiving a project. |

### Known limitations

- **Leaving the workspace does not remove project memberships yet.** Removing people from a workspace arrives with story 6, which must also remove them from that workspace's projects.
- **A project can end up with no members.** Only admins can then see it. Story 6's "at least one Project Manager" rule closes this.
- **Projects cannot be renamed or deleted individually yet.** Renaming arrives with story 8; deleting the whole workspace removes its projects.

---

## 9. Implementation status

As of 23 Oct 2026, on branch `features/workspaces-teams-projects`:

| Part | Status |
| --- | --- |
| `Project`, `ProjectMember` tables and repositories | Implemented |
| `ProjectService`: create, list, get, list/add/remove members, add a team, visibility rules | Implemented |
| `ProjectController`: the seven endpoints in §2 | Implemented |
| Pagination and search on both lists | Implemented |
| Unit tests: `ProjectServiceTest` (25), `ProjectControllerTest` (5) | Implemented, all passing |
| Database test against PostgreSQL (`WorkspaceDataIntegrationTest`): "my projects", project member rows removed with the workspace | Implemented, all passing |

---

## 10. Decisions

| Date | Decision |
| --- | --- |
| 23 Oct 2026 | Story 5 builds a minimal project (name only); story 8 adds key, template and settings, story 6 adds roles. |
| 23 Oct 2026 | Any member of an active workspace can create a project and becomes its first member (story 8's rule). |
| 23 Oct 2026 | Projects are visible to their members and to workspace admins. Others get `404`; a project member who is not an admin gets `403` on membership changes. |
| 23 Oct 2026 | Only workspace admins manage project membership in this story. |
| 23 Oct 2026 | Adding people is all or nothing; everyone must already be a workspace member. People already in the project are skipped. |
| 23 Oct 2026 | Adding a team copies its current members once; the project is not linked to the team. |
| 23 Oct 2026 | Project names are not unique; story 8's key will be. |
| 23 Oct 2026 | Both project lists are paginated (`page`/`size`, max 100, fixed order) with a `?q=` search. |