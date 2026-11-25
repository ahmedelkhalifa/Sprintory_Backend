# Sprintory — One-Time Jira CSV Migration (V17)

## Purpose and safety

One-time import of **current Jira Cloud issue state** into a Sprintory Scrum project. This is **not** a complete historical migration, a backup restore, a Jira sync, or a Jira export tool.

The importer is **disabled by default**. It may be enabled temporarily for an approved migration window by setting `SPRINTORY_JIRA_IMPORT_ENABLED=true` on the backend and building the frontend with `VITE_ENABLE_JIRA_IMPORT=true`. The server independently enforces authorization and the backend flag; hiding the UI is not a security boundary. Both flags default to false. Rebuild/redeploy the frontend when changing its build-time flag. Disable the backend flag again after migration. No live Jira API tokens are required.

**No automatic source fetch:** the browser uploads a Jira Cloud **CSV (all fields)** and optionally sprint metadata JSON exported from `/rest/agile/1.0/board/{boardId}/sprint?maxResults=50`. Jira board configuration is *not required* for the PMP pilot: accepted statuses are `To Do`, `In Progress`, `In Review`, `Done`, with In Review added before Done if missing.

**Required migration sequence:** use the normal Sprintory UI to create a fresh, empty **Scrum** project (for example, a new production project key). Open **Project → Settings → Import from Jira**, upload files, preview, map assignees to members of the target project (optional), type `IMPORT`, then import.

If `sprints` is omitted, no sprints are created and all imported top-level work items go to the backlog; their statuses and subtask relationships are preserved. Sprint assignments cannot be added later by re-importing into the migrated project. If a file is supplied, invalid JSON, known incomplete pages (`isLast: false`, nonzero `startAt`, or `total` different from `values.length`), and missing referenced sprint names are rejected. Legacy `values`-only exports remain supported; their pagination completeness cannot be proven. To recover, supply a complete export or remove the optional file and migrate without sprints.

## Endpoints

All endpoints require authenticated access, active workspace, **active Scrum project**, and the intersection of `EDIT_SETTINGS`, `EDIT_TASKS`, `CONFIGURE_BOARD`, `VIEW_WORK`. Only a Project Manager who is also a project member normally satisfies these permissions. Unauthenticated users are rejected by the existing security layer; nonmembers receive 404 via `ProjectAccess`, insufficient permission 403, malformed input 400.

### `POST /api/v1/projects/{projectId}/jira-import/preview`

`multipart/form-data`:

| Part | Required | Meaning |
|---|---|---|
| `csv` | Yes | Jira Cloud CSV (all fields), max 10 MB, UTF-8, RFC-4180 quoting |
| `sprints` | No | Jira Agile `board/{id}/sprint` JSON, max 1 MB |

Does **not write to the database**. Response includes `sourceIssues`, `epicsSkipped`, `orphanSubtasksSkipped`, `alreadyImported`, `newTopLevelItems`, `newSubtasks`, `newSprints`, `assignees: [{ jiraAccountId, displayName, issueCount }]`, `warnings`, `blockers`, and `ready`.

If `ready=false`, execution is refused. Preview reflects database state at preview time; execution always validates again under the project lock.

### `POST /api/v1/projects/{projectId}/jira-import`

`multipart/form-data`: same `csv` and `sprints`, plus:

| Part | Required | Meaning |
|---|---|---|
| `confirmation` | Yes | Exact string `IMPORT` |
| `userMappings` | No | JSON object `{ "jira-account-id": 123 }`, destination values are **Sprintory user IDs** |

`201 Created` with `createdTopLevel`, `createdSubtasks`, `createdSprints`, `skippedExisting`, `skippedEpics`, `skippedOrphanSubtasks`, `commentsImported`, `warnings`. The entire write is one Spring database transaction. Any validation or SQL failure rolls it back.

Mapped accounts must already be members of the destination project; unmapped assignees are left **unassigned**. Add people to the project in its Members tab first. No users are created by the import. Bad account mappings return 400.

## Scope and rules

- Import top-level **Stories, Tasks, Bugs**, and their **Subtasks**. Skip **Epics** per Ahmed's decision. Skip orphan or unsupported subtasks (`PMP-4` in the sample), with warnings. Preserve a story/task's original Epic key in `jira_imported_issues.jira_epic_key` for possible future Epic support.
- Import current status (create `In Review` if missing; no lossily mapping it to In Progress), priority, estimate, all label columns, description, assignee (if mapped), comments (if usable), sprint and rank. Original per-project keys are **new Sprintory keys**, not copied Jira keys; `jira_imported_issues` records Jira issue ID/key and original timestamps.
- Sprint metadata: preserve sprint **name, goal, start/end dates, state**, and completion instant when closed. Dates are interpreted from the absolute timestamps supplied by Jira (UTC date). An undated future sprint remains PLANNED with no dates.
- **Do not invent** sprint commitments, committed points, completed velocity, outcomes, burndown or historical task changes. These stay absent. Capacity suggestions ignore completed sprints without measured velocity (rather than treating them as zero).
- Description/Comment conversion creates safe TipTap **paragraph and text nodes**: Jira wiki-markup formatting is not fully converted, but text/newlines survive and script content cannot execute.
- Imported comments are attributed to the matching Sprintory user when mapped, otherwise to an unknown author. The imported body contains the original Jira comment timestamp/account ID. The Sprintory creation timestamp reflects the import, not the original.
- Attachment references in CSV are **not downloaded**. Attachment binaries, worklogs, reporters, watchers, historical timestamps and change logs are not imported. They may be part of a later migration iteration.
- Order is translated from Jira LexoRank into Sprintory's sparse numeric ranks. Each Sprint has its own item positions. A target with pre-existing tasks or sprints is rejected to avoid mixing data during the one-time cutover.
- Re-importing the **same** dataset into the migrated project is idempotent via `(project_id, jira_issue_id)` and `(project_id, jira_key)` keys in V17. A newer CSV that introduces issues or sprints is rejected for that already migrated destination. Nothing is overwritten. For the real cutover, take a **fresh Jira export and sprint metadata**, use a fresh empty Sprintory project, and make Sprintory the source of truth afterward. This is **not an incremental sync**.

## Dependencies and limits

- Flyway **V17__jira_import_provenance.sql** creates `jira_imported_issues` and `jira_imported_sprints` mapping tables; no changes to existing migration versions.
- Both tables cascade when the target project is deleted, so an abandoned local rehearsal can be removed by deleting its test project according to Sprintory's existing project lifecycle constraints.
- A single CSV may have up to 10,000 issues, with no more than 10 MB of input. Extremely large batches should be reviewed for transaction duration and frontend response latency before production use.
- Nonempty destinations and attempts to append a new dataset to an already migrated project are **blocked**, not merged. Existing issues are never overwritten. Reject an outdated export rather than relying on a later update.
- Perform dry runs with a separate local database and test project. For production, ensure a database backup and recovery plan, fresh Jira export, approved cutover, verified mappings, and project-manager access before enabling the feature flags. Do not use the old November 13 export for cutover.

## Expected PMP fixture (CSV exported Nov 13, 2026)

| Item | Count |
|---|---:|
| Source issues | 133 |
| Epics skipped | 9 |
| Orphan subtasks skipped | 1 |
| New top-level Stories/Tasks | 50 |
| New subtasks | 73 |
| New Sprints | 2 |
| Total expected new tasks | **123** |

The two sprints are `Sprint 1` (COMPLETED, Oct 18–Nov 1) and `Sprint 2` (ACTIVE, Nov 2–Nov 16). Their historical commitments are **not** populated.

**Acceptance check**: after import, verify two sprint statuses/dates/goals, 123 tasks (50 top-level + 73 children), original ordering, exactly one imported comment, missing assignee handling, an In Review column, skipped Epics and orphan, and that a second execution creates **zero** additional objects. The final cutover must use a refreshed CSV and refreshed sprint metadata (not the Nov 13 sample).

### `GET /api/v1/projects/{projectId}/jira-import/mappings`

Read-only mapping report (same access policy). Returns `[{jiraKey, sprintoryTaskId, sprintoryKey, jiraEpicKey}]`, including child issue mappings, for reconciliation after the import. This is *not* a second import or a synchronization API.

## Production go/no-go checklist

1. Confirm schema migration number `V17` is unused on the actual target `main` branch. If later migrations exist, renumber the new migration to the next unused version **before deploying**; never edit an already applied Flyway migration.
2. Verify no different importer PR or target project already exists, back up the production database, confirm application release/rollback procedure, and grant an authenticated project manager access to a **new empty Scrum project**.
3. Test the **latest** Jira CSV + sprint JSON in an isolated environment. Preview counts, check skipped types, map users, import, examine data and repeat the same import for zero additions.
4. In the approved production window, set backend `SPRINTORY_JIRA_IMPORT_ENABLED=true`, build frontend with `VITE_ENABLE_JIRA_IMPORT=true`, deploy through the normal team-reviewed CD workflow. Do not enable unauthenticated endpoints or disable permission checks.
5. Repeat the checked import in the new production project. Download and retain the Jira ↔ Sprintory issue-key mapping. Review backlog, task parent links, assignees, sprints, and statuses.
6. Turn off the backend flag after successful cutover; rebuild frontend without the UI flag in the next normal release. No ongoing Jira sync exists.

## Historical sprints

`GET /api/v1/projects/{projectId}/sprints/history` lists completed and cancelled sprints for callers with `VIEW_WORK`. This is read-only and independent of the planned/active sprint endpoint. Imported ended sprints appear in the Sprints tab with unavailable historical commitments/outcomes displayed as `–`, never as measured zeroes.
