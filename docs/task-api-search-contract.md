# PMP-105 — Task REST API and universal search (shared branch handoff)

**Status:** DTOs, controller signatures, validation, and search-filter contracts are implemented.
**Persistence/search services are intentionally TODOs returning `501 NOT_IMPLEMENTED` until Ahmed integrates PMP-104.** Do not ship this branch into production before TODOs are completed.

PMP-104 owns task entity, key generation, persistence, soft deletion and transactional business rules. PMP-105 owns controllers, DTOs, search/filter contracts and HTTP behaviors. Use the existing `ProjectAccess`, `PageResponse`, and `Pageables` conventions.

## Task routes

| Method | Path | Response | Permission |
|---|---|---|---|
| POST | `/api/v1/projects/{projectId}/tasks` | 201 + `TaskResponse` | `EDIT_TASKS` |
| GET | `/api/v1/projects/{projectId}/tasks` | `PageResponse<TaskResponse>` | `VIEW_WORK` |
| GET | `/api/v1/projects/{projectId}/tasks/{taskId}` | `TaskResponse` | `VIEW_WORK` |
| PATCH | `/api/v1/projects/{projectId}/tasks/{taskId}` | `TaskResponse` | `EDIT_TASKS` |
| DELETE | `/api/v1/projects/{projectId}/tasks/{taskId}` | 204 | `EDIT_TASKS` |

The `TaskApiService` applies `ProjectAccess.require` or `requireActive` before the pending implementation. As implemented, permitted calls return 501 until Ahmed fills each TODO. Viewers cannot write (403); outsiders see 404, rather than resource details. Look up task IDs **within** the requested project to prevent cross-project data leakage.

### Create JSON

```json
{
  "title": "Add filters to tasks endpoint",
  "type": "TASK",
  "priority": "MEDIUM",
  "assigneeId": null,
  "estimate": 3,
  "labels": ["backend", "R09"],
  "description": "Rich text is stored as a string"
}
```

`title` is required/nonblank and <=255. Type defaults to `TASK`, priority to `MEDIUM` **in the service** when omitted; no fake defaults are applied by the DTO. Estimate is a nonnegative integer, assignee ID must be positive and belong to this project; labels may be `[]`/omitted. The key is server-assigned and never reused. Neither initial status nor sprint is client-specified: Scrum starts unsprinted in its backlog, Kanban starts in its Backlog column.

### PATCH behavior

`UpdateTaskRequest` supports partial changes. Omitted/null fields are **unchanged**; send `clearAssignee: true`, `clearEstimate: true`, or `clearDescription: true` to clear those fields. Send `labels: []` to remove all labels. Sending both a value and the matching clear flag is rejected (400). `statusId` must belong to this project's board. Task key/project/sprint are not editable in this endpoint.

## Project task search

`GET /api/v1/projects/12/tasks?q=PMP-42&type=BUG&priority=HIGH&statusId=3&assigneeId=7&label=backend&page=0&size=20`

`TaskSearchFilters` validates/normalizes optional filters, including case-insensitive `STORY|TASK|BUG` and `HIGHEST|HIGH|MEDIUM|LOW|LOWEST`. Combine filters using AND. Match `q` against title **OR** key as a literal case-insensitive substring (escape `%` and `_`). Exclude soft-deleted tasks from every read/search/board/backlog result. Use stable sort and correct total count. `statusId`, `assigneeId` must not broaden access; only show project members' task data.

## Universal search

`GET /api/v1/search?q=auth&types=TASK,PROJECT,TEAM,WORKSPACE&workspaceId=3&projectId=8&page=0&size=20`

- `q` is required, trimmed, nonblank, max 255.
- `types` is optional: comma-separated `TASK`, `PROJECT`, `TEAM`, `WORKSPACE` (case-insensitive); default all. Duplicate types collapse.
- `workspaceId` and `projectId` optional positive scopes. They **restrict** results.
- Response is `PageResponse<UniversalSearchResult>` with fields `type`, `id`, `title`, `subtitle`, `path`, `workspaceId`, `projectId`, `taskKey` (null for non-task hits).
- **TODO in `UniversalSearchService`:** implement efficient database search across selected types with deterministic global ordering and correct paging/count. Query must return only resources the caller can see: workspaces of which they are a member; teams/projects according to their existing visibility rules; tasks only from projects for which they have `VIEW_WORK` (workspace admin status alone is insufficient). Do not retrieve everything and filter it in memory. Exclude soft-deleted tasks.
- A 501 is intentional before implementation, not a successful empty search. Do not convert TODO into empty `List`/`PageResponse`.

## Coordination for Ahmed

1. Implement `TaskApiService` TODOs against PMP-104 entity/repositories/service, preserving signatures and permission checks; use `TaskType`, `TaskPriority`, `CreateTaskRequest`, `UpdateTaskRequest` and `TaskResponse` provided here.
2. Implement `UniversalSearchService.search()` once task persistence exists (or coordinate a separate staged implementation). All results must use the common `UniversalSearchResult` DTO.
3. **Requirements conflict:** R06 creates Kanban with `To Do` as column one; Jira says new Kanban tasks must start in a `Backlog` column. Align template/migration/creation behavior before completing task creation.
4. **Task key prerequisite:** Current project creation does not collect/persist a `PMP`-style project key. PMP-104 needs a project-key policy/schema migration for `PMP-42` style task keys.
5. Test 403/404 isolation, task/column/project foreign keys, transactions, key concurrency and never-reuse, soft delete, combined filters, pagination, and universal-search membership isolation. Full integration tests require PMP-104.
