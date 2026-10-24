# Sprintory — Roles and permissions contract (R03)

**Status:** roles, the permission matrix and every rule are implemented in the services. **The HTTP endpoints are not built yet**; see [§3 Endpoints](#3-endpoints). See [§10 Implementation status](#10-implementation-status).
**Applies to:** workspace roles on `workspace_members`, project roles on `project_members`, and every permission check inside a project.
**Related:** [Workspaces](./workspaces-docs.md), [Projects](./projects-docs.md), [Teams](./teams-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R03, R04, R38.

This document is the single reference for who may do what in Sprintory. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **Two levels of roles.** A person has **one role per workspace** (Admin or Member) and **one or more roles per project**. They can hold different roles in different workspaces and projects.
- **Roles grant permissions; code checks permissions.** Every check inside a project asks "does this person have permission X here?", never "is this person a Scrum Master?". Which role grants which permission is defined once, in the matrix (§2).
- **Roles combine.** A person's permissions in a project are everything all their roles in that project allow together.
- **Project Manager, Product Owner and Scrum Master include every Member permission**, so they can work on tasks too.
- **The template decides the roles on offer.** Scrum projects offer all five project roles; Kanban projects offer Project Manager, Member and Viewer.
- **There is always someone in charge.** A workspace always keeps at least one Admin; a project always keeps at least one Project Manager.
- **Users have no global role** (decided 23 Oct 2026). Logins carry no authorities, so `hasRole(...)` checks never match; roles are checked in the services.

### Workspace roles

| Role | Purpose |
| --- | --- |
| `WORKSPACE_ADMIN` | Manages the workspace: members and their roles, invitations, teams, settings. Can manage every project of the workspace (§2, last column). |
| `WORKSPACE_MEMBER` | Belongs to the workspace, can create projects and be added to projects and teams. |

The creator of a workspace is its first admin; people who accept an invitation join as members.

### Project roles

| Role (API value) | Shown as | Templates | Purpose |
| --- | --- | --- | --- |
| `PROJECT_MANAGER` | Project Manager | Scrum, Kanban | Manages the project: settings, members, project roles, archiving, board |
| `PRODUCT_OWNER` | Product Owner | Scrum | Owns and orders the product backlog |
| `SCRUM_MASTER` | Scrum Master | Scrum | Plans, starts and closes sprints |
| `MEMBER` | **Developer** in Scrum, **Member** in Kanban | Scrum, Kanban | Creates and works on tasks, comments, logs time |
| `VIEWER` | Viewer | Scrum, Kanban | Read-only access to the project |

- `MEMBER` is **one value** with two display names. The API always sends `MEMBER`; the frontend shows "Developer" when the project's `template` is `SCRUM`.
- **Viewer cannot be combined** with any other role.
- The creator of a project becomes its `PROJECT_MANAGER`. People added to a project, one by one or through a team, get `MEMBER`.

---

## 2. Permission matrix

| Permission | What it allows | Feature | PM | PO | SM | Member | Viewer | WS Admin |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `VIEW_PROJECT` | See the project and its member list | R02 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `VIEW_WORK` | See tasks, comments, attachments, sprints, reports | Story 7 | ✓ | ✓ | ✓ | ✓ | ✓ | |
| `EDIT_SETTINGS` | Edit name, description and other settings | Story 8 | ✓ | | | | | ✓ |
| `ARCHIVE_PROJECT` | Complete and archive the project | R30 | ✓ | | | | | ✓ |
| `MANAGE_MEMBERS` | Add people or a team, remove people | R02 | ✓ | | | | | ✓ |
| `MANAGE_ROLES` | Change members' project roles | R03 | ✓ | | | | | ✓ |
| `CONFIGURE_BOARD` | Board columns and WIP limits | R16 | ✓ | | | | | |
| `MANAGE_BACKLOG` | Order and prioritize the backlog | R13 | | ✓ | | | | |
| `MANAGE_SPRINTS` | Plan, start and close sprints | R14, R15 | | | ✓ | | | |
| `EDIT_TASKS` | Create, edit and delete tasks | Story 9 | ✓ | ✓ | ✓ | ✓ | | |
| `COMMENT` | Comment on tasks | R10 | ✓ | ✓ | ✓ | ✓ | | |
| `LOG_TIME` | Log time on tasks | R19 | ✓ | ✓ | ✓ | ✓ | | |

- **WS Admin** is what a workspace admin gets in **every** project of their workspace, whether or not they are a member of it. They manage the project but **do not see its work** unless they are a member (story 7). An admin who is also a project member gets the admin column plus their project roles combined.
- **Strict Scrum roles:** only the Product Owner orders the backlog and only the Scrum Master runs sprints. A Project Manager who should do either gives themselves that role too.
- **Only the permissions marked R02 and R03 are enforced today.** The others are defined ahead of their features; each feature checks its permission when it is built.
- The matrix is fixed in code (`ProjectRole`), not configurable per workspace.

### Examples

| Person | Can | Cannot |
| --- | --- | --- |
| Scrum Master in project A, Viewer in project B | start sprints in A | edit tasks in B |
| Product Owner + Scrum Master | order the backlog **and** start a sprint | change roles |
| Product Owner | create and edit tasks | start a sprint |
| Developer only | create and edit tasks | start or close a sprint |
| Viewer only | see the project and its work | create, edit or delete a task, comment |
| Workspace admin, not in the project | add people, change roles, edit settings | see the project's tasks |

---

## 3. Endpoints

**Not built yet.** The service methods below are implemented and tested; the developer adding the controllers documents the endpoints here, following [API conventions](./api-conventions.md). The table is the agreed shape.

| Action | Planned endpoint | Service method |
| --- | --- | --- |
| Change a member's workspace role | `PATCH /workspaces/{workspaceId}/members/{userId}` `{ "role": "WORKSPACE_ADMIN" }` | `WorkspaceService.changeRole` → `WorkspaceMemberResponse` |
| Remove a member, or leave (own `userId`) | `DELETE /workspaces/{workspaceId}/members/{userId}` → `204` | `WorkspaceService.removeMember` |
| Replace a member's project roles | `PUT /projects/{projectId}/members/{userId}/roles` `{ "roles": ["PRODUCT_OWNER", "SCRUM_MASTER"] }` | `ProjectService.setRoles` → `ProjectMemberResponse` |

Still to add to the DTOs (other developer):

- `ProjectResponse.template`
- `ProjectMemberResponse.roles`
- the caller's own `roles` and `permissions` on the project response (`ProjectAccessResult.permissions()` holds them), so the frontend can hide controls (§7)
- request DTOs with validation for the three endpoints above

Project membership endpoints already exist and are documented in [projects-docs.md](./projects-docs.md); story 6 only changes who may call them (§4).

---

## 4. Rules

### 4.1 Workspace roles

| Rule | Result |
| --- | --- |
| Only an admin changes workspace roles, and only in an active workspace | otherwise `403`, or `409 WORKSPACE_ARCHIVED` |
| The person must be a member of the workspace | otherwise `404` "Member not found." |
| Demoting the **last admin**, oneself included | refused, `409 LAST_WORKSPACE_ADMIN` |
| Demoting an admin while another admin remains, oneself included | allowed |
| Giving someone the role they already have | allowed, changes nothing |

### 4.2 Removing a member and leaving

- An **admin** may remove anyone from the workspace. A **member** may remove only themselves, which is leaving. Anything else is `403`.
- The workspace must be active (`409 WORKSPACE_ARCHIVED`).
- Refused when the person is:
  - the **last admin** → `409 LAST_WORKSPACE_ADMIN`
  - the **only Project Manager** of one or more projects of the workspace → `409 LAST_PROJECT_MANAGER`, and the message names those projects. Someone else must be made Project Manager there first.
  - The only Scrum Master, Product Owner or Developer of a project **may** leave; the project simply has no one in that role until it is given again.
- Otherwise the person is removed from **every team and every project of this workspace**, with their project roles, and then from the workspace. Their memberships in other workspaces do not change.
- Pending invitations are not touched. Inviting the person again works as usual; they come back as a member with no teams, projects or roles.

### 4.3 Project roles

| Rule | Result |
| --- | --- |
| Only someone with `MANAGE_ROLES` changes roles: the project's Project Managers and the workspace's admins | a project member without it gets `403`; someone who cannot see the project gets `404` "Project not found." |
| The workspace must be active | otherwise `409 WORKSPACE_ARCHIVED` |
| The person must be a member of the project | otherwise `404` "Project member not found." |
| The new set replaces the old one completely | one call assigns, changes and removes |
| The set is empty | refused, `400 INVALID_PROJECT_ROLES` "A member must have at least one role." |
| Viewer together with another role | refused, `400 INVALID_PROJECT_ROLES` "Viewer cannot be combined with other roles." |
| A role the template does not offer, e.g. Scrum Master in Kanban | refused, `400 INVALID_PROJECT_ROLES` "Scrum Master is not available in Kanban projects." |
| Taking Project Manager away from the **last** Project Manager, oneself included | refused, `409 LAST_PROJECT_MANAGER` |

### 4.4 Project membership (changed by story 6)

- Adding people, adding a team and removing people need `MANAGE_MEMBERS`: **a project's Project Manager can now do it**, not only workspace admins.
- New project members get `MEMBER`. People already in the project keep their roles.
- Removing the **last Project Manager** from a project is refused with `409 LAST_PROJECT_MANAGER`. Anyone else can be removed.

### 4.5 Concurrency

The "at least one" rules are checked while holding a database lock:

- Workspace role changes and removals lock the workspace, so two admins demoting each other at the same moment cannot leave it without an admin.
- Project role changes and removals lock the project, so two Project Managers stepping down at the same moment cannot leave it without one.
- Removing someone from a workspace also locks the projects they manage, so that removal and a Project Manager stepping down cannot both pass.

The second request waits for the first and then sees the updated state.

---

## 5. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses). **The frontend should branch on `code`, not on `message`.**

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `LAST_WORKSPACE_ADMIN` | 409 | A workspace must have at least one admin. | Demoting or removing the last admin, or the last admin leaving |
| `LAST_PROJECT_MANAGER` | 409 | A project must have at least one Project Manager. | Taking the role from, or removing, a project's last Project Manager |
| `LAST_PROJECT_MANAGER` | 409 | A project must have at least one Project Manager. Assign another one first in: Mobile App, Website. | Removing from the workspace someone who is the only Project Manager of the named projects |
| `INVALID_PROJECT_ROLES` | 400 | A member must have at least one role. | Empty role set |
| `INVALID_PROJECT_ROLES` | 400 | Viewer cannot be combined with other roles. | Viewer plus another role |
| `INVALID_PROJECT_ROLES` | 400 | {Role} is not available in {Template} projects. | e.g. "Product Owner is not available in Kanban projects." |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | Missing permission, or a member trying to remove someone else |
| `RESOURCE_NOT_FOUND` | 404 | Member not found. | The workspace member to change or remove does not exist |
| `RESOURCE_NOT_FOUND` | 404 | Project member not found. | The project member whose roles are being set does not exist |
| `RESOURCE_NOT_FOUND` | 404 | Project not found. / Workspace not found. | The caller cannot see the project or workspace |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Any role change or removal in an archived workspace |

---

## 6. Data model

| Table | Columns | Constraints |
| --- | --- | --- |
| `workspace_members` | `role` (`WORKSPACE_ADMIN` / `WORKSPACE_MEMBER`) | check constraint; unchanged since R02 |
| `projects` | `template` (`SCRUM` / `KANBAN`) | not null, check constraint, never updated |
| `project_member_roles` | `project_member_id`, `role` | primary key (`project_member_id`, `role`); FK to `project_members` `on delete cascade`; check constraint on the five roles |

- Migration: `V3__project_roles.sql`. Existing projects became `SCRUM`. Existing members became `MEMBER`, and each project's creator `PROJECT_MANAGER`.
- Role rows go with their member row through the database cascade: removing someone from a project, deleting the project, deleting the workspace, or deleting the user.
- **Leaving a workspace is not a cascade.** It deletes only the `workspace_members` row, which team and project rows do not reference, so the service deletes the person's team and project rows of that workspace explicitly.
- **Adding a role or permission:** a new `ProjectRole` value needs a migration that changes `ck_project_member_roles_role`. A new `ProjectPermission` needs only code, plus the matrix test and §2 here.

### In code

| Class | Purpose |
| --- | --- |
| `project/models/ProjectPermission` | The permissions (rows of §2) and the `WORKSPACE_ADMIN` set |
| `project/models/ProjectRole` | The project roles, each with its permissions; `permissionsOf(roles)` combines them |
| `project/models/ProjectTemplate` | `SCRUM` / `KANBAN` and the roles each offers |
| `project/services/ProjectAccess` | `requireVisible` (404), `require(…, permission)` (403), `requireActive(…, permission)` (409). **Every check inside a project goes through here.** |
| `project/services/ProjectAccessResult` | The project, the caller's memberships and their effective permissions |
| `workspace/services/WorkspaceAccess` | Workspace-level checks, unchanged |

A later feature checks its permission with one line, for example `projectAccess.requireActive(projectId, user, ProjectPermission.MANAGE_SPRINTS)`.

---

## 7. Frontend notes

- **Hide controls by permission, not by role.** Once the project response carries `permissions`, show "Start sprint" only when it contains `MANAGE_SPRINTS`, the edit controls only with `EDIT_TASKS`, and so on. Hiding is for usability; the backend refuses either way.
- **Role picker:** offer the roles of the project's `template` (§1). Grey out Viewer combinations, and show `MEMBER` as "Developer" in Scrum projects.
- **Leave workspace:** call the remove endpoint with the user's own id. On `409 LAST_PROJECT_MANAGER`, show the message: it names the projects that need another Project Manager first.
- **Create project:** `template` is now required (`"SCRUM"` or `"KANBAN"`), see [projects-docs.md §3.1](./projects-docs.md#31-create-a-project).

---

## 8. What the API reveals

| Situation | Reveals |
| --- | --- |
| Changing roles in a project the caller cannot see | Nothing: the same `404` as a missing project |
| `404` "Member not found." / "Project member not found." | Only to someone who may manage that workspace or project, and only whether the person is a member of it |
| `409 LAST_PROJECT_MANAGER` on removal | Project names, only to an admin or to the person leaving, both of whom can already see those projects |

---

## 9. Known limitations

- **Endpoints and DTO fields are not built yet** (§3).
- **No audit log of role changes** yet; R05 (sprint 2) adds it.
- **A project whose creator had left before V3** has no Project Manager. A workspace admin can assign one.

---

## 10. Implementation status

As of 24 Oct 2026, on branch `features/roles`:

| Part | Status |
| --- | --- |
| `ProjectPermission`, `ProjectRole` (matrix), `ProjectTemplate` | Implemented |
| `Project.template`, required on create | Implemented |
| `ProjectMember.roles`, `project_member_roles` table | Implemented |
| `ProjectAccess` and `ProjectAccessResult` | Implemented |
| `ProjectService`: creator becomes PM, new members get Member, `MANAGE_MEMBERS` for membership, last-PM rule, `setRoles` | Implemented |
| `WorkspaceService`: `changeRole`, `removeMember` (remove and leave) | Implemented |
| Error codes `LAST_WORKSPACE_ADMIN`, `LAST_PROJECT_MANAGER`, `INVALID_PROJECT_ROLES` | Implemented |
| `V3__project_roles.sql` | Written, **not yet run against PostgreSQL** |
| Unit tests: `ProjectRoleTest` (14), `ProjectTemplateTest` (4), `ProjectAccessTest` (11), `ProjectServiceTest` (42), `WorkspaceServiceTest` (40), `ExceptionHandlerTest` (8) | Implemented, all passing |
| Database tests against PostgreSQL for the new queries, locks and cascades | Pending |
| Endpoints, DTO fields, controller tests | Pending (other developer) |

---

## 11. Decisions

| Date | Decision |
| --- | --- |
| 24 Oct 2026 | Code checks permissions, not roles. The matrix is fixed in `ProjectRole`, not configurable. |
| 24 Oct 2026 | Story 6 adds only the project `template` (immutable), because the roles on offer depend on it. Story 8 adds key, board and settings. |
| 24 Oct 2026 | Strict Scrum roles: only the Product Owner orders the backlog, only the Scrum Master runs sprints. |
| 24 Oct 2026 | Viewer cannot be combined with other roles. |
| 24 Oct 2026 | Member and Developer are one value, `MEMBER`; the frontend shows "Developer" in Scrum. |
| 24 Oct 2026 | A member's project roles are replaced as a whole set. |
| 24 Oct 2026 | Project creators become Project Manager; people added to a project get Member. |
| 24 Oct 2026 | Project Managers manage their project's members and roles, as do workspace admins. Workspace admins do not see project work unless they are members. |
| 24 Oct 2026 | Members may leave a workspace. The last admin cannot leave, and the only Project Manager of a project cannot leave until replaced. Other sole role holders may. |
| 24 Oct 2026 | Leaving or being removed takes the person out of the workspace's teams and projects. |
| 24 Oct 2026 | "At least one" rules are checked under a database row lock. |
| 24 Oct 2026 | Controllers and DTOs for story 6 are built by the other developer, against §3. |