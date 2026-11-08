# PMP-119 — Scrum sprint planning backend

**Status:** implementation patch for review. Requires Flyway `V15__sprint_planning.sql`, migration validation and the full Maven suite in the Sprintory backend environment. PMP-120 frontend must disable its local sprint mock.

## 1. Roles, routing, and security

All endpoints are prefixed `/api/v1`; JWT authentication follows existing API conventions. Sprints exist only for `SCRUM` projects. Every read checks `VIEW_WORK`; every sprint mutation checks `MANAGE_SPRINTS`, i.e. **Scrum Master** (including a multi-role member). Other project roles, including PM, cannot plan or start a sprint without Scrum Master permission. Sprint-length project setting checks `EDIT_SETTINGS` (PM). Changes are blocked for completed/archived projects and archived workspaces. Nonmembers receive the existing project-access 404 behavior.

`SPRINT_ALREADY_ACTIVE` is HTTP 409; other state conflicts use `CONFLICT` 409. Date errors use `BAD_REQUEST` 400. Malformed/invalid DTO fields use `VALIDATION_ERROR` 400.

## 2. Existing frontend endpoints (contract retained)

| Method | Endpoint | Body | Response |
| --- | --- | --- | --- |
| GET | `/projects/{projectId}/sprints` | — | Array of PLANNED + ACTIVE `SprintResponse` (oldest number first) |
| POST | `/projects/{projectId}/sprints` | `{name?, goal?, capacity?, startDate?, endDate?}` | `201 SprintResponse` |
| GET | `/sprints/{sprintId}` | — | `SprintResponse`, including COMPLETED |
| PATCH | `/sprints/{sprintId}` | Same fields (partial; `{clearCapacity:true}` to reset) | `SprintResponse` |
| DELETE | `/sprints/{sprintId}` | — | `204`; PLANNED only |
| POST | `/sprints/{sprintId}/start` | `{startDate?, endDate?, goal?}` | `SprintResponse`; goal + dates + ≥1 task required |
| POST | `/projects/{projectId}/sprints/move` | `{taskId, sprintId, afterTaskId}` | `204`; `sprintId:null` restores original backlog rank |
| POST | `/sprints/{sprintId}/complete` | `{carryOverSprintId?, createSprint?}` | `{sprint, carriedOver, toSprint}`; active only |

Existing `src/api/sprintsApi.js` in Sprintory_Frontend expects these routes. **Amir must set `VITE_MOCK_SPRINTS=false`** in the frontend environment and restart Vite; by default the frontend uses a browser-local sprint mock. The mock can be removed after integration.

`SprintResponse` example:

```json
{
  "id": 3, "projectId": 7, "name": "PMP Sprint 4", "goal": "Deliver login",
  "capacity": 27, "startDate": "2026-11-09", "endDate": "2026-11-22", "status": "PLANNED",
  "taskIds": [55, 41, 60], "completedAt": null, "committedPoints": null,
  "completedPoints": null, "selectedPoints": 33,
  "unestimatedTaskIds": [60], "overCapacity": true
}
```

`taskIds` contains **top-level tasks only** in sprint order. Their subtasks remain linked to their parent and inherit the sprint ID in `TaskResponse.sprintId`. The sprint assignment is stored on the parent task, never independently on a subtask. In Scrum, a task with `sprintId:null` is in the backlog. Existing `TaskResponse.from()` now returns the actual sprint ID. `GET /projects/{id}/board` returns `activeSprintId` from the ACTIVE sprint.

## 3. New planning/capacity endpoints (PMP-120 integration)

| Method | Endpoint | Permission | Response |
| --- | --- | --- | --- |
| GET | `/sprints/{sprintId}/capacity-suggestion` | `VIEW_WORK` | `CapacitySuggestionResponse` |
| PUT | `/sprints/{sprintId}/availability/{userId}` | `MANAGE_SPRINTS` | Body `{availableDays:5}`, returns new suggestion |
| GET | `/sprints/{sprintId}/scope-history?page=0&size=20` | `VIEW_WORK` | `PageResponse<SprintScopeChangeResponse>` |
| GET | `/sprints/{sprintId}/commitment` | `VIEW_WORK` | Array of immutable committed item snapshots |
| GET | `/projects/{projectId}/sprint-length` | `VIEW_PROJECT` | `{sprintLengthDays:14}` |
| PUT | `/projects/{projectId}/sprint-length` | `EDIT_SETTINGS` | Body `{days:14}`, returns `{sprintLengthDays:14}`; affects NEW sprints only |

Suggestion:

```json
{
  "suggestedCapacity": 27,
  "averageVelocity": 30.0,
  "normalPersonDays": 50,
  "availablePersonDays": 45,
  "workingDays": 10,
  "members": [{"userId":11,"availableDays":10}, {"userId":12,"availableDays":5}]
}
```

The complete `members` array contains every current project member, not only the two shown above. Workdays default to **Monday–Friday** inclusive; public holidays are not modeled. Average velocity uses the last **up to three COMPLETED** sprints, counting story points of DONE top-level items at completion. No completed history ⇒ `suggestedCapacity:null`. Suggestion is `round(avgVelocity × availablePersonDays / normalPersonDays)`; the Scrum Master still chooses and saves any nonnegative `capacity`. Per-member available days default to all working days, bounded to `0..workingDays` when manually overridden. The frontend should show missing estimate IDs and `overCapacity` as *warnings*, not blocking errors.

## 4. Validation and lifecycle

- Creation sets `PLANNED`, automatically numbered `KEY Sprint N`, with consecutive default **14-calendar-day** periods (configurable for new sprints via sprint-length endpoint). Two supplied dates must be both present; end must be strictly after start. Dates must never overlap any sprint in the project, including completed. The project row is locked for all mutations to serialize date and active-state checks.
- Multiple PLANNED sprints are allowed. PostgreSQL's unique partial index additionally guarantees at most one ACTIVE sprint per project.
- On start: `goal` required (`"Add a Sprint Goal first."`), ≥1 top-level task required, dates rechecked, no other ACTIVE. Saves `committedPoints` and **immutable item/estimate snapshots** before publishing the existing `SprintStarted` event to notify project members.
- Moving or reordering tasks updates sprint order without changing `backlog_rank`. Moving out restores the task's original backlog position. A deleted PLANNED sprint releases its items. Cross-project items and independent subtask moves are rejected.
- Post-start cross-sprint/backlog additions and removals emit persistent `sprint_scope_changes` records with task key, actor ID, time and action. Reordering inside the same sprint is not a scope change.
- Completing sets `COMPLETED`, snapshots `completedPoints`, keeps DONE tasks in that sprint and moves unfinished work into an existing PLANNED sprint, a new PLANNED sprint or the backlog. The `SprintCompleted` notification is emitted. Full cancel/reopen lifecycle belongs to the separate PMP-122 scope.
- Project completion/archival (PMP-63) is already represented by the lifecycle status; sprint mutations are forbidden in those states.

## 5. Migration and tests

`V15__sprint_planning.sql` creates the sprint tables, availability rows, committed snapshots, scope-change history, and nullable assignment/order columns on tasks. It also adds `projects.sprint_length_days DEFAULT 14`. The migration assumes V14 attachments has already run.

Tests: `SprintCapacityCalculatorTest` (normal/available ratios, weekends, no history), `SprintPlanningIntegrationTest` (real PostgreSQL/Flyway: permissions, dates, ordering, start, snapshot, warnings, completed velocity and availability). Run `bash ./mvnw clean verify` with Docker/Testcontainers available. API and schema changes must not be committed without passing the suite.
