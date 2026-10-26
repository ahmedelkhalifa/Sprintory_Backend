# Sprintory — Universal search API contract

**Status:** implemented: one search box across workspaces, projects, teams and tasks. See [§7 Implementation status](#7-implementation-status).
**Applies to:** backend `GET /api/v1/search`.
**Related:** [Tasks](./tasks-docs.md), [Projects](./projects-docs.md), [Teams](./teams-docs.md), [Workspaces](./workspaces-docs.md), [Roles and permissions](./roles-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5).

This document is the single reference for universal search. If the code and this file disagree, fix one of them in the same PR. The task list's own search (`GET /projects/{id}/tasks?q=`) is in [tasks-docs.md §3.2](./tasks-docs.md#32-list-and-search-a-projects-tasks).

---

## 1. Overview

- **One query, four kinds of results:** workspaces, projects, teams and tasks whose name (or title) contains `q`, ignoring case. Projects and tasks also match on their key (`MOB`, `MOB-42`).
- **Only what the caller can see**, by the same rules as the rest of the API:

  | Result | Visible to |
  | --- | --- |
  | Workspace | its members |
  | Project | the workspace's admins (all projects); other members only the projects they are in |
  | Team | the workspace's admins (all teams); other members only the teams they are in |
  | Task | members of its project (`VIEW_WORK`). **Not** a workspace admin who is not in the project. Never a deleted task. |

- **Filters only narrow** the results. Searching a workspace or project the caller cannot see returns an empty page, never an error, so the search does not reveal whether it exists.
- **Archived workspaces** are searched too (reading is allowed there).

---

## 2. Endpoint

`GET /api/v1/search?q=&types=&workspaceId=&projectId=&page=&size=`

| Parameter | Rules |
| --- | --- |
| `q` | **Required.** Trimmed, not blank, at most 255 characters. Case-insensitive "contains"; `%` and `_` are taken literally. |
| `types` | Optional, comma-separated: `WORKSPACE`, `PROJECT`, `TEAM`, `TASK`, ignoring case. Default: all four. Duplicates count once. |
| `workspaceId` | Optional. Only results of that workspace (the workspace itself included). |
| `projectId` | Optional. Only that project and its tasks; workspaces and teams are left out. |
| `page`, `size` | As everywhere: 0-based, default 20, at most 100. `?sort=` is ignored. |

- **Order:** best match first. An exact match of the name, title or key comes first, then names that start with `q`, then names that only contain it. Within each group, alphabetical by name/title, then by kind (workspace, project, team, task), then by id. The order is total, so pages never overlap.
- **Response `200`:** `PageResponse<UniversalSearchResult>`:

  ```jsonc
  {
    "content": [
      { "type": "TEAM", "id": 4, "title": "Auth", "subtitle": "Acme",
        "path": "/teams/4", "workspaceId": 3, "projectId": null, "taskKey": null },
      { "type": "TASK", "id": 55, "title": "Fix auth login", "subtitle": "MOB · Mobile App",
        "path": "/projects/8/tasks/55", "workspaceId": 3, "projectId": 8, "taskKey": "MOB-42" }
    ],
    "page": 0, "size": 20, "totalElements": 2, "totalPages": 1
  }
  ```

  | Field | Value |
  | --- | --- |
  | `type` | `WORKSPACE`, `PROJECT`, `TEAM` or `TASK` |
  | `id` | the id of that workspace, project, team or task |
  | `title` | its name (task: its title) |
  | `subtitle` | project and team: the workspace name; task: `KEY · project name`; workspace: `null` |
  | `path` | its **API** path: `/workspaces/{id}`, `/projects/{id}`, `/teams/{id}`, `/projects/{projectId}/tasks/{id}`. The frontend maps it to its own route. |
  | `workspaceId`, `projectId` | where it lives (`projectId` only for projects and tasks) |
  | `taskKey` | tasks only, e.g. `MOB-42` |

- **Errors:**

  | Status | Code | Message | When |
  | --- | --- | --- | --- |
  | 400 | `BAD_REQUEST` | Bad Request | `q` missing |
  | 400 | `BAD_REQUEST` | 'q' is required. | `q` blank |
  | 400 | `BAD_REQUEST` | 'q' must be at most 255 characters. | `q` too long |
  | 400 | `BAD_REQUEST` | Invalid 'types' filter: {VALUE}. | an unknown type (an empty item gives "Invalid 'types' filter.") |
  | 400 | `BAD_REQUEST` | 'workspaceId' must be positive. / 'projectId' must be positive. | an id of 0 or less |
  | 401 | `AUTHENTICATION_ERROR` | Authentication required. Please log in. | not logged in |

---

## 3. Frontend notes

1. Debounce the search box; send `q` once it has a character or two.
2. Inside a workspace or project page, pass `workspaceId` / `projectId` to search only there.
3. Group or label hits by `type`; show `subtitle` under the title and `taskKey` in front of a task's title.
4. Navigate with `type` and the ids (or `path`): task → the task in its project, project → project page, team → team page, workspace → workspace page.
5. Do not combine this with the per-project task search; for the task list's filters use [tasks-docs.md §3.2](./tasks-docs.md#32-list-and-search-a-projects-tasks).

---

## 4. How it works

`search/repositories/UniversalSearchRepository` builds one SQL query: one `SELECT` per requested kind, joined with `UNION ALL`, each already filtered by the visibility rules of §1 and the scopes, then sorted and paged by the database (`LIMIT`/`OFFSET`), plus a matching `count(*)`. Nothing is loaded and filtered in memory.

- **Native SQL**, through `NamedParameterJdbcTemplate`. Only fixed SQL fragments are concatenated (which parts and which scope conditions); every user value, `q` included, is a bound parameter.
- Because it is native SQL, the soft-delete filter on `Task` does not apply: the task part writes `deleted_at is null` itself.
- Every part names all its columns, because a `UNION` takes its column names from its first part and which part comes first depends on `types` and the scope.
- **Visibility must stay in line with the rest of the API.** If `ProjectAccess`, the team rules or the roles matrix change (for example a project role without `VIEW_WORK`), change the query too.

---

## 5. What the API reveals

| Request | Reveals |
| --- | --- |
| Any search | Only what the caller can already see in the lists of §1. |
| `workspaceId` / `projectId` of something the caller cannot see | Nothing: an empty page, the same as a scope with no matches. |
| Tasks | Nothing from projects the caller is not a member of, even for a workspace admin. |

---

## 6. Known limitations

- Matching is a plain "contains" on names, titles and keys, not on descriptions, labels or member names; there is no full-text ranking.
- Large workspaces: the query scans the visible rows with `like '%q%'`, which cannot use a normal index. Fine at course scale; a `pg_trgm` index would be the next step.

---

## 7. Implementation status

As of 26 Oct 2026, on branch `features/project-template-defaults` (Damir: endpoint, filters, result DTO; Ahmed: query):

| Part | Status |
| --- | --- |
| `UniversalSearchController`, `UniversalSearchFilters`, `UniversalSearchResult` | Implemented |
| `UniversalSearchService`, `UniversalSearchRepository` | Implemented |
| Unit tests: `UniversalSearchControllerTest`, `UniversalSearchFiltersTest`, `UniversalSearchServiceTest` | Implemented, all passing |
| `UniversalSearchIntegrationTest` (9) against PostgreSQL: visibility for member, admin and outsider, deleted tasks, keys, scopes, types, literal wildcards, paging | Implemented, all passing |
