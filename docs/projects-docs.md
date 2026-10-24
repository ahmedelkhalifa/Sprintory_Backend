# Sprintory — Projects API contract (R02, minimal)

**Status:** projects with a Scrum/Kanban template, project membership and project roles implemented. Story 8 (R06) adds the project key, the board and settings. Roles and permissions are in [roles-docs.md](./roles-docs.md). See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/workspaces/{workspaceId}/projects` and `/api/v1/projects/**`.
**Related:** [Roles and permissions](./roles-docs.md), [Workspaces](./workspaces-docs.md) (archiving, pagination), [Teams](./teams-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Authentication](./auth-endpoints.md), [Requirements](./requirements.md) R02, R04, R06.

This document is the single reference for how projects and their membership work end to end. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **A project lives inside one workspace.** It has a name and a **template**, `SCRUM` or `KANBAN`, chosen at creation and never changed. Story 8 adds a key and a board.
- **Any member of a workspace can create a project** and becomes its **Project Manager**.
- **Membership is what grants access.** Only the project's members can see it, plus the workspace's admins, who manage it.
- **Each member has one or more project roles** (Project Manager, Product Owner, Scrum Master, Member/Developer, Viewer). What each role allows is in [roles-docs.md](./roles-docs.md).
- **Project Managers and workspace admins manage membership:** add people, add a whole team, remove people. New members get the `MEMBER` role. A project always keeps at least one Project Manager.
- **Adding a team copies its current members once.** The project does not stay linked to the team: people who join the team later are not added, and nobody is removed when they leave the team.
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Anyone who may not see a project gets `404`, exactly as if it did not exist.
- **Archived workspaces are read-only.** Projects can still be listed and opened, but every change gets `409 WORKSPACE_ARCHIVED`.
- **Authentication** works as described in [auth-endpoints.md](./auth-endpoints.md). Every endpoint here needs the `access_token` cookie.

### Who can do what

"Admin" is the workspace admin role; "PM" is the Project Manager role in this project. Other project roles behave like "In the project, not PM" here.

| Action | Not logged in | Not in the workspace | Workspace member, not in the project | In the project, not PM | PM | Admin |
| --- | --- | --- | --- | --- | --- | --- |
| Create a project | 401 | 404 | allowed (active workspace) | allowed (active workspace) | allowed (active workspace) | allowed (active workspace) |
| List the workspace's projects | 401 | 404 | sees only their own projects | sees only their own projects | sees only their own projects | sees all projects |
| Get a project | 401 | 404 | 404 | allowed | allowed | allowed |
| List a project's members | 401 | 404 | 404 | allowed | allowed | allowed |
| Add people to a project | 401 | 404 | 404 | 403 | allowed (active workspace) | allowed (active workspace) |
| Add a team to a project | 401 | 404 | 404 | 403 | allowed (active workspace) | allowed (active workspace) |
| Remove a person from a project | 401 | 404 | 404 | 403 | allowed (active workspace) | allowed (active workspace) |

Why `404` for some members and `403` for others: someone who cannot see a project must not learn that it exists, so they get `404`. A project member can already see the project, so refusing a change with `403` reveals nothing new.

The membership changes need the `MANAGE_MEMBERS` permission; see the full matrix in [roles-docs.md §2](./roles-docs.md#2-permission-matrix).

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

Not available yet, on purpose: renaming or editing a project and its settings (story 8), archiving a project (R30), and deleting a single project. Changing a member's project roles is implemented in the service; its endpoint is listed in [roles-docs.md §3](./roles-docs.md#3-endpoints).

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
{ "name": "Mobile App", "template": "SCRUM" }

// AddProjectMembersRequest
{ "userIds": [7, 12, 15] }

// AddTeamToProjectRequest
{ "teamId": 4 }

// ProjectResponse
{ "id": 21, "workspaceId": 3, "name": "Mobile App" }

// ProjectMemberResponse: public profile data only (the roles field is still to be added, roles-docs §3)
{ "userId": 7, "firstName": "Ada", "lastName": "Lovelace", "email": "ada@example.com" }
```

| Field | Rules |
| --- | --- |
| `name` | Required, not blank, at most 100 characters. Leading and trailing spaces are removed before saving. **Not unique**: two projects may share a name. Story 8's project key is the unique identifier. |
| `template` | Required, `"SCRUM"` or `"KANBAN"`. Cannot be changed after creation. Decides which project roles the project offers ([roles-docs.md §1](./roles-docs.md#project-roles)). |
| `userIds` | Required, 1 to 100 ids, none `null`. Duplicates in the list count once. Every id must belong to a member of the project's workspace. |
| `teamId` | Required. A team of the **same** workspace as the project. |

Passwords, password hashes, tokens and provider are never part of a response body.

---

## 3. Endpoints in detail

### 3.1 Create a project

`POST /api/v1/workspaces/{workspaceId}/projects`

Creates a project in the workspace and makes the caller its Project Manager.

- **Who:** any member of the workspace, admin or not. The workspace must be active.
- **Request:**
  ```json
  { "name": "Mobile App", "template": "SCRUM" }
  ```
- **Response `201`:**
  ```json
  { "id": 21, "workspaceId": 3, "name": "Mobile App" }
  ```
- **After creation:** the caller appears in 3.4 with the `PROJECT_MANAGER` role.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `name` missing, blank or longer than 100 characters, or `template` missing |
  | 400 | `BAD_REQUEST` | `template` is not `SCRUM` or `KANBAN` |
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

The story's "add people to project": the admin or Project Manager selects people and they are added.

- **Who:** a Project Manager of this project, or an admin of its workspace. The workspace must be active.
- **Request:**
  ```json
  { "userIds": [7, 12, 15] }
  ```
- **Rules:**
  - **All or nothing.** Every id must belong to a member of the project's workspace. If even one does not (unknown user, or someone outside the workspace), the request is refused with `400` and **nobody** is added.
  - New members get the `MEMBER` role (shown as Developer in Scrum). Other roles are given afterwards ([roles-docs.md §4.3](./roles-docs.md#43-project-roles)).
  - People already in the project are skipped silently and keep their roles, so sending the same list twice is safe.
  - Duplicate ids in the list count once.
- **Response:** `204`, no body. Reload 3.4 to show the result.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `VALIDATION_ERROR` | `userIds` missing, empty, longer than 100, or containing `null` |
  | 400 | `BAD_REQUEST` "Some users are not members of this workspace." | at least one id is not a member of the project's workspace |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this project but is neither its Project Manager nor an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | as in 3.3 |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

The `400` does not say which ids were refused, so it does not reveal whether a user id exists outside the workspace.

### 3.6 Add a team to a project

`POST /api/v1/projects/{projectId}/teams`

The story's "add team to project": all the team's members are assigned to the project.

- **Who:** a Project Manager of this project, or an admin of its workspace. The workspace must be active.
- **Request:**
  ```json
  { "teamId": 4 }
  ```
- **Rules:**
  - Everyone **currently** in the team becomes a project member with the `MEMBER` role. People already in the project are skipped and keep their roles.
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
  | 403 | `ACCESS_DENIED` | the caller is in this project but is neither its Project Manager nor an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | as in 3.3 |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |

### 3.7 Remove a person from a project

`DELETE /api/v1/projects/{projectId}/members/{userId}`

- **Who:** a Project Manager of this project, or an admin of its workspace. The workspace must be active.
- **Rules:**
  - The person loses access to the project **immediately**: their next request to it gets `404` (story 7).
  - They stay in the workspace and in their teams.
  - Removing someone who is not in the project changes nothing and still returns `204`.
  - The project's **last Project Manager cannot be removed**; make someone else Project Manager first. Project Managers can remove themselves while another one remains.
- **Response:** `204`, no body.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `BAD_REQUEST` | `userId` is not a number |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | the caller is in this project but is neither its Project Manager nor an admin |
  | 404 | `RESOURCE_NOT_FOUND` "Project not found." | as in 3.3 |
  | 409 | `LAST_PROJECT_MANAGER` | the person is the project's last Project Manager |
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
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | A project member who is neither its Project Manager nor an admin tries to change membership |
| `LAST_PROJECT_MANAGER` | 409 | A project must have at least one Project Manager. | 3.7 on the project's last Project Manager |
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
2. Show **Add people**, **Add team** and **Remove** only to the project's Project Managers and the workspace's admins. Once the project response carries the caller's `permissions` ([roles-docs.md §3](./roles-docs.md#3-endpoints)), check for `MANAGE_MEMBERS` instead. Hiding them is for usability; the backend enforces it either way.
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
| `projects` | `id`, `workspace_id`, `name` (≤ 100), `template` (`SCRUM` / `KANBAN`), audit columns | none on the name; `template` not null, check constraint, never updated; story 8 adds a key unique within the workspace |
| `project_members` | `id`, `project_id`, `user_id`, audit columns | unique on (`project_id`, `user_id`): the same person cannot be added twice |
| `project_member_roles` | `project_member_id`, `role` | primary key on both; check constraint on the five roles ([roles-docs.md §6](./roles-docs.md#6-data-model)) |

- The audit columns are `created_at`, `updated_at`, `created_by`, `updated_by`, from `BaseEntity`.
- All foreign keys are `ON DELETE CASCADE` (`@OnDelete` in the entities):
  - deleting a **workspace** deletes its projects, their member rows and their role rows
  - deleting a **project** deletes its member rows and their role rows
  - deleting a **member row** deletes its role rows
  - deleting a **user** deletes their project memberships
- Leaving a workspace is not a cascade: the service removes the person's project rows of that workspace explicitly ([roles-docs.md §4.2](./roles-docs.md#42-removing-a-member-and-leaving)).
- There is no link between `projects` and `teams`; adding a team only writes `project_members` rows.
- Code lives in `project/{models,repositories,services,controllers,dto,exceptions}`. Access checks are in `ProjectAccess`, which builds on `WorkspaceAccess.findMember` and checks permissions ([roles-docs.md §6](./roles-docs.md#in-code)).
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
| Story 6 (R03) | **Done in the services**, see [roles-docs.md](./roles-docs.md): the template, project roles, Project Managers managing membership, the "at least one Project Manager" rule. Endpoints for changing roles are still to be built. |
| Story 7 (R04) | Applies this document's visibility rule to everything inside a project, through `ProjectAccess` and `VIEW_WORK`. Admins manage membership but see project work only if they are members. |
| Story 8 (R06) | Project key (unique in the workspace), default board columns per template, editing name and description (`EDIT_SETTINGS`), settings page showing the template read-only. |
| Story 9 (R09) | Tasks inside projects; task endpoints check `EDIT_TASKS` / `VIEW_WORK` through `ProjectAccess`. |
| R30 | Completing and archiving a project (`ARCHIVE_PROJECT`). |

### Known limitations

- **Projects cannot be renamed or deleted individually yet.** Renaming arrives with story 8; deleting the whole workspace removes its projects.
- **A project whose creator left before migration V3** has no Project Manager; a workspace admin can assign one.

---

## 9. Implementation status

As of 24 Oct 2026, on branch `features/roles`:

| Part | Status |
| --- | --- |
| `Project`, `ProjectMember` tables and repositories | Implemented |
| `ProjectService`: create, list, get, list/add/remove members, add a team | Implemented |
| `ProjectController`: the seven endpoints in §2 | Implemented |
| Pagination and search on both lists | Implemented |
| Template on create, project roles, `ProjectAccess` (story 6) | Implemented, see [roles-docs.md §10](./roles-docs.md#10-implementation-status) |
| Unit tests: `ProjectServiceTest` (42), `ProjectAccessTest` (11), `ProjectControllerTest` (5) | Implemented, all passing |
| Database test against PostgreSQL (`WorkspaceDataIntegrationTest`): "my projects", project member rows removed with the workspace | Implemented; not yet re-run with migration V3 |

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
| 24 Oct 2026 | The template (`SCRUM` / `KANBAN`) is required at creation and never changes; story 6 adds it ahead of story 8. |
| 24 Oct 2026 | The creator becomes Project Manager; Project Managers manage membership as well as workspace admins; new members get `MEMBER`. Supersedes the two 23 Oct rows on admins-only membership. See [roles-docs.md §11](./roles-docs.md#11-decisions). |