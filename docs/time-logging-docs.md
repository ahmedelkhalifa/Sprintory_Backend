# Sprintory — Time logging API (R19, PMP-49 / PMP-144)

**Frontend handoff:** PMP-145 (Mobeen). Backend PR #35 (`features/time-logging-pmp-49`). Migration **V23** follows V22, which is already merged into main. This story implements personal work logs and task totals, not PMP-50 reporting.

## 1. Endpoints

All paths are under `/api/v1`. Use existing cookie authentication (`credentials: 'include'`) and error handling. Task IDs include subtasks; entry IDs are database-generated. No project ID or user ID is accepted in a request body; the server derives these from the task and authenticated actor.

| Method | Path | Success |
| --- | --- | --- |
| POST | `/tasks/{taskId}/time-entries` | 201, entry |
| PUT | `/time-entries/{entryId}` | 200, updated entry |
| DELETE | `/time-entries/{entryId}` | 204, no body |
| GET | `/tasks/{taskId}/time-entries?mine=true` | 200, array of own entries |
| GET | `/tasks/{taskId}/time-summary` | 200, summary |

### 1.1 Create

`POST /api/v1/tasks/42/time-entries`

```json
{
  "workDate": "2026-11-28",
  "minutes": 90,
  "note": "Implemented validation"
}
```

`minutes`: JSON integer 1–1440, required. `workDate`: ISO `YYYY-MM-DD` calendar date, no time. Defaults to server today if omitted/null on create; frontend should always send the user's local date. `note`: optional plain string, max 500 characters; absent/null means no note. Do not send a formatted duration to the API.

201 response (also the shape of PUT and list items):

```json
{
  "id": 73,
  "taskId": 42,
  "workDate": "2026-11-28",
  "minutes": 90,
  "note": "Implemented validation",
  "createdAt": "2026-11-28T08:00:00Z",
  "updatedAt": "2026-11-28T08:00:00Z"
}
```

### 1.2 Edit

`PUT /api/v1/time-entries/73` replaces all editable fields. Send both required fields even if only the note changes. Missing/null `note` clears the note; work date is required on edit. An unchanged save returns the current entry and writes no history.

```json
{"workDate":"2026-11-27","minutes":120,"note":"Updated note"}
```

### 1.3 Delete

`DELETE /api/v1/time-entries/73` returns 204. No response body. Soft deletion retains the database row but excludes it from lists, task totals and daily limits. A repeated delete returns 404. There is no restore-entry endpoint in this story.

### 1.4 My entries

`GET /api/v1/tasks/42/time-entries?mine=true` returns a plain JSON array of entry objects (not a PageResponse). An empty list is `[]`. Sorted by work date descending, then id descending. Omitting `mine` defaults to true; `mine=false` returns 400. Parent lists only contain entries directly on the parent, not entries on subtasks. Viewers can read their own former entries but cannot edit/delete them. Nobody can fetch another person's entry list in this story.

### 1.5 Summary

`GET /api/v1/tasks/42/time-summary`

```json
{
  "ownMinutes": 120,
  "subtasksMinutes": 90,
  "totalMinutes": 210,
  "canLog": true,
  "today": "2026-11-28"
}
```

All totals include all authors, even departed members, but exclude deleted entries and deleted tasks. Subtask summary has `subtasksMinutes: 0`. All zeros for an empty task. Totals are numbers in minutes, not formatted strings, and are derived on each request. `canLog` is true only when the caller has `LOG_TIME`, is a project member, and the project and workspace are writable. `today` is the server's calendar date, useful for explaining server validation. It is not the user's local date.

## 2. Permissions and lifecycle

The existing permission enum is `LOG_TIME`, not a new `TIME_LOG` permission. Existing role matrix remains unchanged. All project members including Viewers may see the summary and their own entries. Only non-Viewers with `LOG_TIME` can create. Edit/delete requires the same permission **and** ownership, including Project Managers and workspace admins. Decision: a workspace admin without project membership gets 404, deliberately stricter than the ordinary task endpoints (403), following PMP-49’s non-member rule. Non-members and missing/deleted tasks also get 404; missing/deleted/inaccessible entry IDs give the same entry 404.

COMPLETED or ARCHIVED project and archived workspace: read-only. No create/edit/delete, but summary and own list remain readable. A DONE task, backlog task, active sprint task or ended sprint task in an active project may receive time logs. Scrum and Kanban both supported. Reassigning a task never changes authorship. Membership removal does not delete entries.

## 3. Validation and error handling

The UI converts supported strings to minutes: `1h 30m`, `90m`, `2h`, `1.5h`. Reject malformed, negative, zero, >1440 or fractional-minute results rather than silently rounding. Examples: `1.5h` → 90; `0.01h` → invalid (0.6 minutes); `25h` → invalid. Canonical display: `90` → `1h 30m`, `60` → `1h`, `20` → `20m`, zero total → `0m`.

Use the user's local calendar date to initialize and constrain date picker; don't use `toISOString().slice(0,10)` as a local date. The backend follows the technical notes: accepts server today, past dates and server tomorrow (time-zone grace), refuses later dates. Work date and duration are independent of task scheduling dates or sprint dates.

The daily cap is 1440 minutes per author, per work date, **per workspace**, across all its projects. The edited entry is excluded from the destination date sum. A soft-deleted task's non-deleted entries still count toward the cap, so deletion/restoration cannot bypass it. Deleted entries do not count. There is no separate daily-balance endpoint; handle the 409 returned by mutation.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | Malformed JSON / non-integer minutes / malformed date |
| 400 | `INVALID_TIME_ENTRY` | Missing/out-of-range duration, overlong note, invalid work date (including missing edit date), unsupported `mine=false` |
| 409 | `TIME_DAILY_LIMIT_EXCEEDED` | `Total for this day would exceed 24 hours.` |
| 401 | `AUTHENTICATION_ERROR` | Unauthenticated |
| 403 | `ACCESS_DENIED` | Role or ownership does not permit mutation |
| 404 | `RESOURCE_NOT_FOUND` | Missing/deleted/inaccessible task or entry |
| 409 | `CONFLICT` | Completed/archived project |
| 409 | `WORKSPACE_ARCHIVED` | Archived workspace |

Daily-limit example:

```json
{
  "timestamp": "2026-11-28T08:00:00Z",
  "status": 409,
  "code": "TIME_DAILY_LIMIT_EXCEEDED",
  "message": "Total for this day would exceed 24 hours.",
  "path": "/api/v1/tasks/42/time-entries"
}
```

TimeEntryRules is the single source for business input validation; malformed JSON or wrong numeric types are rejected during decoding. Show the backend message in the dialog. Preserve the user's unsaved input on failure. Do not optimistically increase totals when a request fails.

## 4. Frontend integration

- Task/subtask Time section: load summary and own entries; show `Logged: 3h 30m` and breakdown for parent (`own 2h, subtasks 1h 30m`). No individual other-author entries.
- `canLog` controls the Log time button and edit/delete actions in the list. Board/backlog menu may use existing `LOG_TIME` permission and lifecycle state, or fetch summary when opening the dialog. Backend always checks again.
- Reuse one dialog for task page, subtask page, board card and backlog row. Edit uses PUT, create uses POST. Confirm delete; disable duplicate submission while awaiting response. Render note as ordinary React text; **never** use `dangerouslySetInnerHTML`, Markdown or TipTap for work-log notes.
- After a successful write, reload the own list and summary; if it was a subtask, invalidate the parent's summary too. Refresh history if visible. No frontend polling required.
- Date picker local today, and client duration parser are frontend responsibilities. No automatic scheduling, timer, time entry approval, comparison with estimates, other-author lists or reports in this story.

## 5. Task history

Existing task history endpoint returns three new types. `field` is always null; `refId` is the time entry ID, not a task ID. `actor` remains the existing actor snapshot. Show these events as text, not links to private entries:

| Type | oldValue | newValue | Suggested display |
| --- | --- | --- | --- |
| `TIME_ENTRY_ADDED` | null | `1h 30m on 2026-11-28` | Ahmed logged 1h 30m on 2026-11-28 |
| `TIME_ENTRY_CHANGED` | `1h 30m on 2026-11-28` | `2h on 2026-11-27 (note changed)` | Ahmed changed logged time: old → new |
| `TIME_ENTRY_DELETED` | `2h on 2026-11-27` | null | Ahmed deleted time entry: 2h on 2026-11-27 |

If only a note changes the duration/date stays the same, with the ` (note changed)` suffix on newValue. No note contents are copied into public history. The action directly calls `TaskStatusHistoryService` in the same transaction, following CLAUDE.md rather than adding history event listeners. On a subtask the entry goes into the subtask's own history. No history on refused or unchanged mutations.

## 6. Backend details / PMP-50 handoff

`time_entry` stores id, workspace_id, project_id, task_id, user_id, work_date, minutes, note, created_at, updated_at, created_by, updated_by, deleted_at. The four audit columns are supplied by `BaseEntity`; the API still exposes timestamps as UTC instants and does not expose auditor fields. All ids are derived server-side. V23 creates the table with a database range check, live indexes for the daily cap and task totals, project/workspace indexes, and extends the existing history type constraint without discarding prior values. Physical project/workspace/task deletion cascades; normal task deletion is soft. Author ID deliberately has no foreign key so attribution survives account deletion; report code must handle missing user records. The feature does not store derived totals or counters.

Concurrency: project row lock coordinates writes with task deletion/restoration. Then a PostgreSQL transaction-scoped advisory lock keyed by workspace and author serializes time-entry writes across all dates/projects for that author. Under READ COMMITTED, the sum after waiting observes the prior committed mutation. The lock releases automatically at commit/rollback; different authors/workspaces use different keys (hash collisions only reduce concurrency). All future write paths for this table must acquire the same lock and validate the cap; raw SQL imports would otherwise bypass it. The sum excludes the edited entry by id, and the total breakdown is one aggregate query over live tasks/entries. Read endpoints use a repeatable-read transaction for a consistent authorization/data snapshot. Access is checked before and after the project lock; the lifecycle is read from the database after locking to avoid relying on a cached project entity.

## 7. Verification and migration order

The review of the original implementation merged with current main reported `clean verify` passing all 1,151 tests, including nine time-entry integration tests and three rules tests. The review follow-up updates those tests to assert precise error codes, HTTP 409 for the daily cap, and audit fields. Re-run verification on the revised branch before merge. Browser rendering and duration parsing belong to the frontend story.

V22 is merged. Apply V23 after V22; do not enable Flyway out-of-order migrations. V23 was revised before merge to add the standard audit columns. If an earlier V23 was applied to a disposable development database, recreate that database before testing; do not repair or modify a shared/production schema blindly.
