# Sprintory — Timeline / schedule API (PMP-48, backend PMP-153)

**Status:** task dates and schedule/dependency API implemented against main `e0130ad`. V20 is reserved for PMP-153. Real milestone retrieval awaits PMP-46; this document explicitly identifies the pending integration. Full clean Maven verification on PostgreSQL passed on 26 November 2026: 1,066 tests, zero failures, errors or skipped tests; V20 applied successfully.

## 1. Dates on tasks

Existing endpoints under `/api/v1/projects/{projectId}/tasks` accept two optional ISO calendar dates (`YYYY-MM-DD`, years 0001–9999). They are returned on `TaskResponse`, including list, detail, creation, edit and move responses. Subtask editing is unchanged; subtasks do not participate in Timeline.

- `POST /api/v1/projects/{projectId}/tasks`: optional `startDate` and `dueDate`, both or neither.
- `PATCH /api/v1/projects/{projectId}/tasks/{taskId}`:

```json
{"startDate":"2026-11-10","dueDate":"2026-11-12"}
```

- Clear both using `{"clearDates":true}`. Omitted or null dates with no clear flag preserve the current dates. One non-null date, a reversed pair, or setting and clearing simultaneously returns `400 BAD_REQUEST`. Same-day dates are allowed. The date fields never change automatically, including on sprint carry-over.
- Date edits use `EDIT_TASKS`; a Viewer gets `403`. Existing task endpoint visibility rules remain unchanged (workspace admin who is not a project member gets `403` on task edits). An archived workspace gives `409 WORKSPACE_ARCHIVED`; a COMPLETED/ARCHIVED project gives `409 CONFLICT` for date-bearing creation or a date update/clear. Ordinary non-date task operations keep their existing behavior.
- `START_DATE` and `DUE_DATE` are separate `FIELD_CHANGED` history fields with ISO dates or `null` (including creation with dates). Only actual changes are logged, through direct `TaskStatusHistoryService.recordChange` calls in the same transaction. This follows CLAUDE.md rather than a separate `TaskDatesChanged` history event. Date/status edits lock the project; title-only edits do not. `@DynamicUpdate` prevents unrelated stale edits from overwriting a concurrent date change.

## 2. Read the Timeline

`GET /api/v1/projects/{projectId}/schedule`

Requires `VIEW_WORK` and actual project membership. A non-member (including a workspace admin who is not a project member) gets `404 RESOURCE_NOT_FOUND`. Viewers, completed/archived projects, and archived workspaces can read. Works for Scrum and Kanban.

Optional query parameters (all combine with AND):

| Parameter | Meaning |
| --- | --- |
| `assigneeId` | User ID |
| `statusId` | Project board column ID |
| `statusCategory` | `TODO`, `IN_PROGRESS`, `DONE` |
| `milestoneId` | Reserved for PMP-46; currently returns 400 if supplied |
| `sprintId` | Sprint ID |
| `type` | `STORY`, `TASK`, `BUG` |
| `hideDone` | `true` excludes DONE rows |
| `from`, `to` | Inclusive date window; include any bar overlapping the window; either bound may be omitted |

Invalid enum/date/ID/window inputs return 400. No pagination: the endpoint returns all matching rows. Filters apply to scheduled rows only; the Unscheduled panel remains project-wide, and milestones will be project-wide. Hidden endpoints suppress arrows, but do not suppress conflicts on visible rows. The global bounds remain the project's full scheduled range. A UI may extend its axis to include `today` when outside this range, or when there are no scheduled tasks; no dates are changed. Bars use their original dates even when they overlap a window boundary.

### Example response (200)

```json
{
  "today": "2026-11-26",
  "earliestStartDate": "2026-11-08",
  "latestDueDate": "2026-11-12",
  "canEditDates": true,
  "milestonesAvailable": false,
  "tasks": [
    {"id":10,"key":"PMP-10","title":"Backend","startDate":"2026-11-08","dueDate":"2026-11-10","statusCategory":"IN_PROGRESS","statusId":2,"statusName":"In Progress","type":"TASK","assigneeId":7,"assignee":"Ada Lovelace","sprintId":4,"milestoneId":null},
    {"id":11,"key":"PMP-11","title":"Frontend","startDate":"2026-11-10","dueDate":"2026-11-12","statusCategory":"TODO","statusId":1,"statusName":"To Do","type":"TASK","assigneeId":null,"assignee":null,"sprintId":4,"milestoneId":null}
  ],
  "dependencies": [{"id":21,"blockerId":10,"blockedId":11}],
  "milestones": [],
  "conflicts": [{"type":"DEPENDENCY","taskIds":[10,11],"dependencyId":21,"milestoneId":null,"message":"PMP-11 starts before PMP-10 ends"}],
  "unscheduled": [{"id":12,"key":"PMP-12","title":"Testing","type":"TASK","statusCategory":"TODO","statusId":1,"statusName":"To Do","assigneeId":null,"assignee":null,"sprintId":null,"milestoneId":null,"blockerKeys":["PMP-11"],"blockedTaskKeys":[],"hint":"blocked by PMP-11, set dates to see it on the chart"}]
}
```

- `today` is calculated with `LocalDate.now()` in the server JVM's default time zone. Bounds are null when no task is scheduled. `canEditDates` is false for Viewers and read-only states; the chart itself is view-only for everyone. Do not use this field to enable dragging/rescheduling.
- `tasks` contains live top-level tasks only, ordered by start date then ID. No description/labels or subtask rows. `assignee` is a display name or null; use `assigneeId` for filtering. `statusCategory` is stable semantics, while `statusId` and `statusName` identify the actual column (use an icon, not just color). Use calendar-day arithmetic and inclusive finish (`end = dueDate + 1 day` for drawing bar width).
- `dependencies` contains only links whose two endpoints are visible and scheduled. Deleted tasks and retained links touching them are excluded from Timeline. An arrow goes from the inclusive finish edge of `blockerId` to the start of `blockedId`. DONE dependencies remain visible unless a row is hidden.
- `unscheduled` includes live top-level tasks with no dates, sorted by ID. Hints cover both incoming and outgoing live dependencies, including DONE links; this is informational, not an open-blocker count. Hints are null if no links.
- `conflicts` are computed from the complete live project schedule, then narrowed to conflicts with at least one visible task ID. They can contain a hidden counterpart, even though its arrow is absent. Paint visible bars identified by `taskIds` and arrows identified by `dependencyId`. For dependency conflicts IDs are `[blockerId, blockedId]`; for milestone conflicts there is one task ID. Scroll to the blocked row if visible, otherwise the visible blocker. Tooltips use `message`. Never prevent saving because of a conflict.

## 3. Conflict calculation

`ScheduleConflicts.find(tasks, dependencies, milestones)` is a pure Java calculation using ID indexes. O(T + E + M) time and space. Inclusive boundary: a blocked task starting on the blocker's due date conflicts; starting the following day does not. Ignore a DONE blocker, regardless of the blocked task's status. Ignore unscheduled or missing endpoints. For milestones, an open scheduled task ending after (not on) its milestone's target date conflicts; DONE tasks do not. Conflicts are not stored and are recalculated on every read.

## 4. PMP-46 integration (pending)

The uploaded base contains no milestone entity, association, table or API. Do not create a second milestone schema or consume V21. The response has stable placeholder fields (`milestonesAvailable: false`, empty `milestones`, null `milestoneId`). Disable milestone filter and markers while false; do not pretend real milestone conflicts are supported. Once PMP-46 lands, update `ScheduleRepository.findRows` with the actual association and a project-wide milestone projection (`id`, `title`, `targetDate`), replace the empty list in `ScheduleService`, remove the milestone filter rejection and set `milestonesAvailable: true`. The pure calculation and `ScheduleFilters` already handle non-null milestone IDs. Verify actual milestone contract (including deleted/archived milestones and frontend route) before enabling. Add integration tests with real milestone persistence and conflict recalculation. No milestone database tables in V20.

## 5. Database and performance

V20 adds nullable DATE columns, paired-date/inclusive-order CHECK, `(project_id, start_date)` index and history field constraint extension preserving earlier values. Existing tasks remain unscheduled. No changes to V19 or sprint date storage. The schedule uses scalar projections, one task query and one dependency query after authorization, no N+1 entity hydration. Read transaction uses repeatable read so tasks and links come from a consistent database snapshot. No new dependencies.

`ScheduleIntegrationTest` checks real PostgreSQL plus HTTP, and records backend and HTTP timings for 500 tasks / 499 links. The criterion of opening the chart within 3 seconds and smooth scrolling also depends on the frontend and must be measured end-to-end there. Do not treat the backend test alone as proof of UI performance.

## 6. Implementation / acceptance status

| Acceptance | Backend status |
| --- | --- |
| 1–5 | Date storage, history, validation, permissions and lifecycle implemented; tests added |
| 6–10 | Rows, ordering, arrows, hints and subtask exclusion implemented; tests added |
| 11 | Milestone retrieval/marker link pending PMP-46 |
| 12–16 | DONE filter, range, today, row data and supported filters implemented; rendering/navigation belongs to frontend; milestone filter pending |
| 17–19, 21 | Dependency conflicts and recalculation implemented; tests added |
| 20 | Pure milestone conflict calculation implemented and unit-tested; real data integration pending |
| 22–25 | Membership, read-only states and Scrum/Kanban implemented; tests added |
| 26 | 500-row backend/HTTP test passed: 9 ms backend read, 32 ms HTTP read and JSON parse; frontend verification pending |

Verification on 26 November 2026: clean Maven build succeeded (1,066 tests; 0 failures, 0 errors, 0 skipped). Flyway applied all 20 migrations on PostgreSQL 16.15. The 500-task / 499-dependency integration benchmark measured 9 ms for the backend read and 32 ms for HTTP read and JSON parsing on the local test machine. End-to-end frontend performance and real milestone integration remain to be verified. To reproduce, run `bash ./mvnw -B clean verify` with Docker running.
