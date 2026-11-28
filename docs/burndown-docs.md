# Sprintory — Burndown chart and velocity API contract (R17, PMP-42)

**Status:** contract written before the code (28 Nov 2026) for the frontend: burndown chart (PMP-157, Amir) and velocity chart (PMP-165, Aliaksandr). Backend: PMP-156, branch `features/burndown-velocity`, built and tested 28 Nov 2026. Acceptance testing: PMP-158 (Mobeen). See [§11 Implementation status](#11-implementation-status).
**Applies to:** backend `GET /api/v1/sprints/{sprintId}/burndown`, `GET /api/v1/projects/{projectId}/velocity`, the daily sprint snapshots, and the velocity average of the capacity suggestion (`GET /api/v1/sprints/{sprintId}/capacity-suggestion`).
**Related:** [Sprints](./sprints-docs.md), [Board](./board-docs.md) (column categories, `activeSprint`), [Roles and permissions](./roles-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R17.

This document is the single reference for the burndown chart and the velocity chart. The backend implements it, the frontend builds both charts against it, and acceptance testing checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a Scrum Master, I want a burndown chart and the team's velocity, so that I can see whether the sprint is on track and plan future sprints realistically."*

- **Scrum only.** Kanban projects have no sprints. Hide both views there.
- **Read-only.** Nothing on these pages can be edited. There is no new permission: anyone who can see the project's work (`VIEW_WORK`) sees both charts, Viewers included.
- **Burndown:** the work remaining in one sprint, day by day, against an **ideal line** that goes from the amount committed at the start down to 0 on the end date. Actual above ideal means behind, below means ahead.
- **Velocity:** committed and completed story points of the last **6 completed** sprints, and an **average line** of the last **3**. That average is the same number sprint planning uses for its capacity suggestion.
- **Both are calculated by the backend.** The frontend only draws what it receives; it never computes the ideal line, the remaining work or the status label.
- **Daily data:** the backend saves one snapshot per active sprint per day ([§5](#5-daily-snapshots)). Today's point is always computed live, so a task that reaches Done shows up at once.
- **Not real-time.** Reload the chart when the page opens or regains focus.
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Someone who cannot see the project gets `404`.
- **Completed and archived projects** (and archived workspaces) stay readable: both charts work there.

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Member/Developer | Scrum Master, Product Owner, Project Manager |
| --- | --- | --- | --- | --- | --- |
| See the burndown | 404 | 403 | allowed | allowed | allowed |
| See the velocity chart | 404 | 403 | allowed | allowed | allowed |

A workspace admin who is not a project member gets `403`, as for tasks, sprints and retros: `VIEW_WORK` is not part of the workspace admin's permissions.

---

## 2. Endpoints at a glance

All endpoints need the access-token cookie. Responses are JSON, fields in `camelCase`, dates as `YYYY-MM-DD`, times as ISO-8601 UTC.

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| `GET` | `/api/v1/sprints/{sprintId}/burndown?unit=points\|tasks&days=working\|calendar` | `VIEW_WORK` | `200 BurndownResponse` ([§3](#3-burndown)) |
| `GET` | `/api/v1/projects/{projectId}/velocity?limit=6` | `VIEW_WORK` | `200 VelocityResponse` ([§4](#4-velocity)) |

The sprint selector needs no new endpoint: it uses the existing sprint lists ([§7.1](#71-sprint-selector)).

### Query parameters

| Endpoint | Parameter | Values | Default | Invalid |
| --- | --- | --- | --- | --- |
| burndown | `unit` | `points` (story points) or `tasks` (task count), case-insensitive | `points` | `400 BAD_REQUEST` "Invalid 'unit': use points or tasks." |
| burndown | `days` | `working` (Monday to Friday) or `calendar` (every day), case-insensitive | `working` | `400 BAD_REQUEST` "Invalid 'days': use working or calendar." |
| velocity | `limit` | How many completed sprints to show, 1 to 20 | `6` | `400 BAD_REQUEST` "'limit' must be between 1 and 20." |

A blank value means the default.

---

## 3. Burndown

`GET /api/v1/sprints/{sprintId}/burndown?unit=points&days=working`

Works for any **started** sprint: `ACTIVE`, `COMPLETED` or `CANCELLED`. A `PLANNED` sprint answers `409 CONFLICT` "Sprint has not started."

### 3.1 Example

Sprint "MOB Sprint 4", Monday 30 Nov to Friday 11 Dec 2026 (10 working days), 30 points committed. Today is Wednesday 2 Dec. On Tuesday 5 points reached Done. On Wednesday a 3-point task was added and 2 more points reached Done.

```json
{
  "sprintId": 42,
  "sprintName": "MOB Sprint 4",
  "sprintStatus": "ACTIVE",
  "startDate": "2026-11-30",
  "endDate": "2026-12-11",
  "endedOn": null,
  "today": "2026-12-02",
  "unit": "POINTS",
  "days": "WORKING",
  "hasDailyData": true,
  "dates":        ["2026-11-30", "2026-11-30", "2026-12-01", "2026-12-02", "2026-12-03", "2026-12-04", "2026-12-05", "2026-12-06", "2026-12-07", "2026-12-08", "2026-12-09", "2026-12-10", "2026-12-11"],
  "ideal":        [30, 27, 24, 21, 18, 15, 15, 15, 12, 9, 6, 3, 0],
  "actual":       [30, 30, 25, 26, null, null, null, null, null, null, null, null, null],
  "scopeChanges": [null, 0, 0, 3, null, null, null, null, null, null, null, null, null],
  "summary": {
    "committed": 30,
    "currentScope": 33,
    "scopeChange": 3,
    "done": 7,
    "remaining": 26,
    "daysLeft": 7,
    "unestimatedTasks": 1
  },
  "status": "BEHIND"
}
```

Wednesday: remaining 26, ideal 21. The difference, 5, is more than 10 % of the committed 30 (3), so the label is `BEHIND`.

### 3.2 Fields

| Field | Type | Meaning |
| --- | --- | --- |
| `sprintId`, `sprintName` | number, string | The sprint. |
| `sprintStatus` | `ACTIVE`, `COMPLETED`, `CANCELLED` | Show a "Cancelled" badge for `CANCELLED`. |
| `startDate`, `endDate` | date | The sprint's planned dates. Closing early does not change `endDate`. |
| `endedOn` | date or `null` | The day the sprint was closed or cancelled (UTC); `null` while `ACTIVE`. |
| `today` | date | The server's date (UTC), so the frontend can mark today without trusting the browser's clock. |
| `unit` | `POINTS` or `TASKS` | The unit of every number below. |
| `days` | `WORKING` or `CALENDAR` | The day mode the ideal line and `daysLeft` use. |
| `hasDailyData` | boolean | `false` for a sprint that ended before this feature existed: show "No daily data for this sprint" instead of the chart. `actual` and `scopeChanges` are then all `null`. |
| `dates` | date[] | The x-axis. **Index 0 is the Start point** (its date is the first day of the axis, normally `startDate`; label it "Start"). Then one entry per day from the first to the last day of the axis ([§3.3](#33-the-axis)). All four arrays have the same length. |
| `ideal` | number[] | The ideal remaining work at the **end** of each day. Can have decimals (rounded to 2). All `null` when the sprint has no committed amount ([§3.4](#34-the-ideal-line)). |
| `actual` | (integer or `null`)[] | The actual remaining work at the **end** of each day. `null` means no value: a day after today, a day after the sprint ended, or a day before the first data. Draw a gap (or connect the points), never a 0. |
| `scopeChanges` | (integer or `null`)[] | The net change of the total that day, for the tooltip: `3` → "+3 pts", `-2` → "−2 pts", in tasks mode "+1 task". `null` where `actual` is `null` and at the Start point. `0` means no change. |
| `summary` | object | See below. |
| `status` | `ON_TRACK`, `AHEAD`, `BEHIND` or `null` | Only for an `ACTIVE` sprint with a committed amount; `null` otherwise. Show "On track", "Ahead", "Behind". |

`summary`, all in `unit`:

| Field | Meaning |
| --- | --- |
| `committed` | The amount committed at the start: `committedPoints` of the sprint (points) or the number of committed items (tasks). `null` for a sprint that was not started in Sprintory (Jira import). |
| `currentScope` | The total now (active) or at the end (ended). |
| `scopeChange` | `currentScope − committed`: show it next to the scope, e.g. "33 (+3)". `null` when `committed` is `null`. |
| `done` | Work in `DONE`-category columns. |
| `remaining` | `currentScope − done`. |
| `daysLeft` | Days after today up to and including `endDate`, counted in the chosen day mode. `0` when the sprint is past its end date or has ended. |
| `unestimatedTasks` | Top-level tasks in the sprint without a story-point estimate. In points mode, when above 0, show "3 tasks have no estimate and are not counted" ("1 task has…"). |

`currentScope`, `done`, `remaining` and `unestimatedTasks` are `null` when `hasDailyData` is `false`.

### 3.3 The axis

- Index 0, the **Start** point, is the moment the sprint started: ideal and actual are both the committed amount.
- Then one entry per calendar day, **including weekends in both modes**, from `startDate` to the later of `endDate` and the last actual day. A sprint **started before its `startDate`** begins on the day it was started instead, and the ideal line stays at the committed amount until `startDate`. The last actual day is today for an `ACTIVE` sprint and `endedOn` for an ended one. So an overdue sprint, or one closed after its end date, extends the axis; after `endDate` the ideal line stays at 0.
- In working-days mode, Saturdays and Sundays are still on the axis (the ideal line is flat there). The frontend can shade them: a date's weekday is `new Date(date + "T00:00:00Z").getUTCDay()` (0 Sunday, 6 Saturday).

### 3.4 The ideal line

- It starts at the **committed** amount at the Start point and reaches **0 at the end of `endDate`**.
- **Working days** (default): it descends only on Monday to Friday, by the same step each working day, and is flat on Saturday and Sunday. 30 points over 10 working days: 27, 24, 21, … 0, i.e. 3 per working day.
- **Calendar days:** it descends every day. 30 points over 12 calendar days: 2.5 per day.
- It never moves when scope changes, so added work shows as the actual line going up.
- If the sprint has no working day at all (a weekend-only sprint), working-days mode falls back to calendar days.
- A sprint without a committed amount (a Jira-imported sprint) has no ideal line: all `null`, and `status` is `null`.

### 3.5 The actual line and scope changes

- **Remaining work at the end of a day = total work in the sprint − work in `DONE`-category columns** at that moment. "Done" is the column's category, never its name ([board-docs.md](./board-docs.md)).
- **Only top-level tasks count** (stories, tasks, bugs). Subtasks follow their parent and are never counted. Deleted tasks are not counted.
- **Points mode:** a task without an estimate counts 0 points (see `unestimatedTasks`). **Tasks mode:** every top-level task counts 1.
- **Work added** to the sprint after the start raises the actual line; **work removed** lowers it without being counted as done. **Changing an estimate** of a task in the sprint changes the total too and shows as a scope change.
- **A task moved back out of Done** raises the remaining work again.
- **Today** (active sprint) is computed live from the current tasks, not from a stored snapshot.
- **A day without a snapshot** (the server was down that night) **repeats the previous day's value** ([§5](#5-daily-snapshots)).
- **Ended sprints are frozen:** the line ends on `endedOn`, and editing an old task later never changes the chart.
- `scopeChanges` of a day = that day's total − the previous entry's total (the Start point's total is the committed amount).

### 3.6 The status label

Only for an `ACTIVE` sprint. Compare today's actual remaining with today's ideal value:

| Today's actual − today's ideal | `status` |
| --- | --- |
| more than 10 % of `committed` above | `BEHIND` |
| more than 10 % of `committed` below | `AHEAD` |
| otherwise | `ON_TRACK` |

`COMPLETED` and `CANCELLED` sprints have no label (`null`). The 10 % is one constant in the backend.

---

## 4. Velocity

`GET /api/v1/projects/{projectId}/velocity?limit=6`

```json
{
  "sprints": [
    { "id": 38, "name": "MOB Sprint 1", "committedPoints": 30, "completedPoints": 28, "completedAt": "2026-10-16T14:02:11Z" },
    { "id": 39, "name": "MOB Sprint 2", "committedPoints": 30, "completedPoints": 30, "completedAt": "2026-10-30T15:40:00Z" },
    { "id": 41, "name": "MOB Sprint 3", "committedPoints": 34, "completedPoints": 32, "completedAt": "2026-11-13T16:20:45Z" }
  ],
  "averageVelocity": 30.0,
  "averageOf": 3
}
```

| Field | Meaning |
| --- | --- |
| `sprints` | The last `limit` **completed** sprints, **oldest first** (draw left to right). Each has `committedPoints` (frozen at start) and `completedPoints` (frozen at close). Empty when none exists. |
| `averageVelocity` | Mean `completedPoints` of the last **3** completed sprints, or of the ones there are when fewer. `null` when there is none: show "Velocity appears after the first completed sprint." |
| `averageOf` | How many sprints the average uses (0 to 3), e.g. for the legend "Average of last 3". |

Rules:

- **Always story points.** The burndown's unit option does not apply here.
- **Cancelled sprints are never shown and never counted.** Active and planned sprints neither.
- **Only sprints that have points count.** A sprint brought in by the Jira importer has no committed or completed points (the importer does not invent them), so it is skipped, in the bars and in the average.
- **No partial credit:** a story that was not Done when the sprint closed counted 0, even if most of its subtasks were done ([sprints-docs.md §2](./sprints-docs.md#2-sprint-endpoints)).
- Sprints closed before this feature existed are included: their points were saved when they closed.
- **The average is the capacity suggestion's average.** `averageVelocity` here always equals `averageVelocity` of `GET /sprints/{id}/capacity-suggestion` ([sprints-docs.md §3](./sprints-docs.md#3-task-list-backlog-board-capacity)): both use the same query and calculation.
- The order is by close time, newest sprints kept, then reversed for display.

---

## 5. Daily snapshots

The backend stores, per sprint and day, the totals the burndown needs. The frontend never reads them directly.

- **What:** total and remaining points, total and remaining tasks, unestimated tasks, for the sprint's live top-level tasks, as at the **end of that day**.
- **When:**
  1. when the sprint **starts**;
  2. every night at **23:55 UTC** for every `ACTIVE` sprint;
  3. when the sprint is **closed or cancelled**, taken **before** unfinished work is carried over, so the last day still shows what was left.
- **One row per sprint and day.** Writing the same day again replaces it, so running the job twice is harmless and a close at 23:58 overwrites the 23:55 row.
- **"Today" and every date are UTC**, like the sprint dates and the close rules ([sprints-docs.md §1](./sprints-docs.md#1-rules-and-access)). Workspaces have no time zone of their own.
- **Ended sprints are never written again.**
- A sprint that was already `ACTIVE` when this feature was deployed has data from the deployment day on; its earlier days are `null`.

---

## 6. Errors

All errors have the common shape `{status, code, message, path, timestamp}` ([API conventions §4](./api-conventions.md)).

| Status, `code` | When | `message` |
| --- | --- | --- |
| `400 BAD_REQUEST` | `unit`, `days` or `limit` invalid | See [§2 Query parameters](#query-parameters) |
| `400 BAD_REQUEST` | The project is a Kanban project (velocity; a Kanban project has no sprints, so the burndown cannot get here) | "Sprints are only available in Scrum projects." |
| `401 AUTHENTICATION_ERROR` | Not signed in | |
| `403 ACCESS_DENIED` | A workspace admin who is not a project member | |
| `404 RESOURCE_NOT_FOUND` | The sprint or project does not exist, or the caller cannot see the project | Show "Sprint not found" / "Project not found" |
| `409 CONFLICT` | Burndown of a `PLANNED` sprint | "Sprint has not started." |

The story asked for `404` on Kanban projects. Every other sprint endpoint answers `400` with the message above, so these two do the same ([§12](#12-decisions)).

---

## 7. Frontend flows

Both views live in the project navigation's **Reports** section ("Burndown", "Velocity"; PMP-55 reuses them later). Hide them in Kanban projects (`project.template === "KANBAN"`).

### 7.1 Sprint selector

1. Load `GET /projects/{projectId}/sprints` (planned and active) and `GET /projects/{projectId}/sprints/history` (completed and cancelled).
2. Offer only **started** sprints: the `ACTIVE` one and every ended one. Never offer `PLANNED`.
3. Default: the `ACTIVE` sprint; when there is none, the ended sprint with the latest `completedAt`. When there is no started sprint at all, show "Sprint has not started." (or "No sprint has started yet.").
4. Mark cancelled sprints "Cancelled" in the list.
5. If a stored or linked sprint id answers `409`, show "Sprint has not started."; on `404`, fall back to the default sprint.

### 7.2 Burndown page (PMP-157)

1. **Entry points:** Reports → Burndown, and a **"View burndown"** link in the active sprint's board header (`activeSprintId` of `GET /projects/{projectId}/board`, [board-docs.md](./board-docs.md)).
2. **Options:** unit (Story points / Task count) and days (Working days only / Calendar days). Defaults `points` and `working`. Remember the choice in `localStorage`, inside `try/catch`, falling back to the defaults when it is unavailable.
3. Call `GET /sprints/{sprintId}/burndown?unit=…&days=…` whenever the sprint or an option changes.
4. **`hasDailyData: false`:** show "No daily data for this sprint" instead of the chart (the summary's `committed` can still be shown).
5. **Chart:** a line chart. The x labels are `dates`, except index 0, which is "Start". `ideal` as a dashed line, `actual` as a solid line that stops at its last non-null value. In working-days mode, shade Saturdays and Sundays ([§3.3](#33-the-axis)). Mark `today` for an active sprint.
6. **Tooltip:** the day's actual and ideal, and the scope change when it is not 0 or null: "+3 pts", "−2 pts" (tasks mode: "+1 task", "−2 tasks").
7. **Summary** above the chart: Committed `committed`, Scope `currentScope` (`scopeChange` as "+3" / "−2" when not 0), Done `done`, Remaining `remaining`, Days left `daysLeft`. In points mode with `unestimatedTasks > 0`, the notice "N tasks have no estimate and are not counted".
8. **Status:** when `status` is not null, a badge "On track" / "Ahead" / "Behind". A `CANCELLED` sprint shows a "Cancelled" badge and no status.
9. No edit actions anywhere on the page, for any role.

### 7.3 Velocity page (PMP-165)

1. Call `GET /projects/{projectId}/velocity?limit=6`.
2. **Empty `sprints`** (and `averageVelocity: null`): show "Velocity appears after the first completed sprint."
3. **Chart:** grouped bars, one pair per sprint in the order returned (oldest left): "Committed" `committedPoints` and "Completed" `completedPoints`, labelled with `name`. A horizontal line at `averageVelocity`, labelled e.g. "Average (last 3): 30".
4. Always story points, whatever the burndown is set to.

---

## 8. Data model

New table `sprint_daily_snapshots` (migration `V22__sprint_daily_snapshots.sql`):

| Column | Meaning |
| --- | --- |
| `id` | Primary key |
| `sprint_id` | The sprint; `ON DELETE CASCADE` |
| `snapshot_date` | The day (UTC) the values are the end of |
| `total_points`, `remaining_points` | Story points of the live top-level tasks in the sprint; remaining = not in a `DONE` column |
| `total_tasks`, `remaining_tasks` | The same as counts |
| `unestimated_tasks` | Top-level tasks without an estimate |
| `recorded_at` | When the row was last written |

`UNIQUE (sprint_id, snapshot_date)`. Writes are an upsert (`insert … on conflict … do update`).

Velocity needs no new table: it reads `sprints.committed_points` and `sprints.completed_points` (V15, V16). The committed task count is the number of `sprint_commitments` rows of the sprint.

---

## 9. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| Both | Nothing about projects the caller cannot see: `404`, as for a missing one |
| Burndown | Only daily totals: no task titles, keys or people |
| Velocity | Sprint names and points, as the sprint history already does |

---

## 10. Known limitations

- **No holidays:** working days are Monday to Friday.
- **Start point:** a task that was already Done when the sprint started is counted as remaining at Start and burns on the first day.
- **Estimate changes** inside a sprint appear as scope changes, not as progress.
- **Sprints started before this feature** have data from the deployment day on; sprints ended before it have none ("No daily data for this sprint").
- **Jira-imported sprints** have no committed amount: no ideal line, no status, and no velocity bars.
- **UTC days:** for users far from UTC, "today" can change in the late evening or early morning.
- **Not real-time:** reload to see other people's changes.

---

## 11. Implementation status

As of 28 Nov 2026:

| Part | Status |
| --- | --- |
| This contract | Written before the code, agreed with the backend owner |
| `V22__sprint_daily_snapshots.sql`, entity, repository | Done |
| Snapshots at start, close, cancel, and the nightly job | Done (`SprintSnapshotWriter`, `SprintSnapshotJob`) |
| Burndown calculation (ideal, actual, scope changes, status, summary) | Done (`BurndownCalculator`, unit-tested) |
| `GET /sprints/{id}/burndown` | Done |
| `GET /projects/{id}/velocity`, shared average with the capacity suggestion | Done |
| Integration test on PostgreSQL | Done (`BurndownVelocityIntegrationTest`) |
| Frontend burndown (PMP-157), velocity (PMP-165), acceptance test (PMP-158) | Not started |

---

## 12. Decisions

| Date | Decision |
| --- | --- |
| 28 Nov 2026 | No new permission: both charts need `VIEW_WORK`, so Viewers see them and a workspace admin outside the project gets `403`. |
| 28 Nov 2026 | Every value is the **end** of its day, so 30 points over 10 working days burn 3 per working day. The arrays start with a **Start** point (ideal and actual = committed) so the lines visibly start at the committed amount. |
| 28 Nov 2026 | The backend sends parallel arrays (`dates`, `ideal`, `actual`, `scopeChanges`), as in the story; the frontend zips them for its chart library. |
| 28 Nov 2026 | Velocity and the capacity suggestion's average skip sprints without points (Jira imports). Before, the suggestion took the last 3 completed sprints and then dropped those without points, so three imported sprints gave no suggestion. |
| 28 Nov 2026 | Kanban answers `400` "Sprints are only available in Scrum projects.", like every sprint endpoint, not the story's `404`. |
| 28 Nov 2026 | All dates are UTC, like the sprint rules. The nightly snapshot runs at 23:55 UTC. |
| 28 Nov 2026 | Snapshots are taken at start, nightly and at close/cancel; today is always live. No snapshot on every task change. |
| 28 Nov 2026 | A sprint started before its `startDate` begins its axis on the day it was started (found while testing), so an early start never shows "No daily data". |
