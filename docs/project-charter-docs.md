# PMP-116 — Project charter backend (R08)

**Status:** Implemented in branch patch, Maven/test run required before merge. Frontend PMP-117 is separate. This contract is written against the implementation in `project/charter/**`.

## Concept and location

A lightweight charter appears as a **project tab**: goals, in-scope and out-of-scope rich text, stakeholders, and goal/scope history. It is safe to leave empty. `ProjectService.create` creates an empty `project_charters` row; migration V13 also backfills an empty row for every existing project. No new project is required to use the tab.

### Access and state

- **Read:** project **members only**, including Viewers, via `ProjectAccess`. Members not in the project (even workspace admins) receive **404**. This intentionally protects stakeholder contact details (R04).
- **Edit:** `EDIT_CHARTER`, held by `PROJECT_MANAGER` and Scrum `PRODUCT_OWNER` only. Other project members receive **403**. Workspace admin rights **without project membership do not bypass** the contact privacy rule or editing restriction.
- **Read-only:** project lifecycle `COMPLETED` or `ARCHIVED` ⇒ writes return **409 CHARTER_READ_ONLY**. Archived workspace ⇒ **409 WORKSPACE_ARCHIVED**. Viewing stays available.
- PMP-63 is not yet implemented; V13 introduces `projects.lifecycle_status` (`ACTIVE`, `COMPLETED`, `ARCHIVED`, default `ACTIVE`) as a minimal foundation. **No state transition endpoints are added by PMP-116**. PMP-63 must reuse/integrate this field rather than create an independent status. The frontend can rely on the charter `canEdit`/`readOnly` flags.
- All mutations use a transaction and a pessimistic project-row lock, including changes to goal order; history writes commit/roll back with the change.

### JSON conventions

All paths below have the prefix `/api/v1`. All require normal JWT-authenticated requests (cookies/header according to the existing auth client), and project id is numeric. Successful `DELETE` returns **204 No Content**. An inaccessible project or resource returns **404 RESOURCE_NOT_FOUND**. Validation failures return **400 VALIDATION_ERROR** with `fieldErrors`. A business-rule violation returns **400 BAD_REQUEST**. Unsafe TipTap returns **400 INVALID_RICH_TEXT**. Response field names are camelCase.

## Endpoints

| Method | Path | Purpose | Permission |
| --- | --- | --- | --- |
| GET | `/projects/{projectId}/charter` | Full charter, including goals and stakeholder register | Project member |
| GET | `/projects/{projectId}/charter/history?page=0&size=20` | Goal/scope changes, **newest first** | Project member |
| POST | `/projects/{projectId}/charter/goals` | Add goal (201) | `EDIT_CHARTER` |
| PUT | `/projects/{projectId}/charter/goals/{goalId}` | Replace goal title and success measure | `EDIT_CHARTER` |
| DELETE | `/projects/{projectId}/charter/goals/{goalId}` | Delete goal and compact ordering | `EDIT_CHARTER` |
| PUT | `/projects/{projectId}/charter/goals/order` | Reorder **all** goals | `EDIT_CHARTER` |
| PUT | `/projects/{projectId}/charter/scope` | Replace **both** scope documents | `EDIT_CHARTER` |
| POST | `/projects/{projectId}/charter/stakeholders` | Add stakeholder (201) | `EDIT_CHARTER` |
| PUT | `/projects/{projectId}/charter/stakeholders/{stakeholderId}` | Replace stakeholder | `EDIT_CHARTER` |
| DELETE | `/projects/{projectId}/charter/stakeholders/{stakeholderId}` | Remove stakeholder | `EDIT_CHARTER` |

### GET charter response

```json
{
  "projectId": 9,
  "canEdit": true,
  "readOnly": false,
  "inScope": "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Web app\"}]}]}",
  "outOfScope": null,
  "goals": [ { "id": 12, "title": "Reduce onboarding time", "successMeasure": "Under 1 day", "order": 0 } ],
  "stakeholders": [ {
    "id": 30, "name": "Acme Corp", "organization": "Acme", "projectRole": "Sponsor",
    "interest": "MEDIUM", "influence": "HIGH", "contactEmail": "sponsor@acme.test",
    "linkedUserId": null, "linkedUserName": null
  } ]
}
```

Empty charter returns `goals: []`, `stakeholders: []`, `inScope: null`, `outOfScope: null`. `readOnly` covers both lack of edit rights and locked project/workspace status; `canEdit` is the precise edit affordance.

### Goals

`POST`/`PUT` request:

```json
{"title":"Reduce onboarding time","successMeasure":"Under 1 day"}
```

`title`: required, trimmed, not blank, maximum **200** characters. `successMeasure`: optional, maximum **1000**; blank is stored as `null`. The response is `{ "id":12, "title":"...", "successMeasure":"...", "order":0 }`. `order` is a **zero-based**, contiguous manual position, automatically assigned to newly added goals. A missing goal id **or a goal belonging to another project** returns 404.

Reorder request is a **complete** list in display order:

```json
{"goalIds":[19,12,13]}
```

Every existing goal must occur **exactly once**; duplicates, missing ids and foreign-project ids give 400 `BAD_REQUEST`. The response is the updated ordered list of goal objects. A no-op reorder does not generate history. Deletion reindexes remaining goals to 0..N−1.

### Scope (rich text)

`PUT /projects/{id}/charter/scope` replaces **both** values:

```json
{
  "inScope": "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Web app\",\"marks\":[{\"type\":\"bold\"}]}]}]}",
  "outOfScope": null
}
```

Each value is **a serialized TipTap JSON document string, not a JSON object**, using `common/richtext/RichTextParser` (see `docs/mentions-docs.md` §2). Parser permits supported formatting and strips unrecognized attributes; unsupported/unsafe nodes and URL schemes are rejected with `INVALID_RICH_TEXT`. `null` and blank clear a field. The *normalized JSON string* is stored. The API returns the entire charter after saving. **Send both fields every time**: a missing field is treated as `null` (clear). Frontend must render TipTap JSON with safe editor/viewer components; do not use untrusted `innerHTML`. Scope text is not a notification source.

### Stakeholders

`POST`/`PUT` request:

```json
{
  "name":"Acme Corp", "organization":"Acme", "projectRole":"Sponsor",
  "interest":"MEDIUM", "influence":"HIGH", "contactEmail":"sponsor@acme.test",
  "linkedUserId":null
}
```

`name` is mandatory, trimmed, max **200**; `organization` optional max **200**; `projectRole` optional max **100**; `interest` and `influence`: `LOW`, `MEDIUM`, `HIGH`, or `null`; `contactEmail`: optional, valid email, max **254**; `linkedUserId`: optional **workspace member** user id (need not be a project member). A user from another workspace is rejected with 400. Stakeholder `name` is independent of linked account name, so a sponsor without an account works. Response adds `id`, `linkedUserName`; the frontend owns linking `linkedUserId` to its **existing user-profile route** (do not invent URLs from this backend contract).

**Privacy:** Contact emails are provided only to project members via the authenticated charter endpoint. They are **not** included in charter history and **no notifications or email events** are emitted for stakeholder changes. Do not place stakeholder contact details into the frontend global store, telemetry or notification payloads.

### History

`GET /projects/{id}/charter/history?page=0&size=20` uses the standard `PageResponse` (`content`, `page`, `size`, `totalElements`, `totalPages`). Each item has `id`, `actorId`, `actorName` (snapshot), `occurredAt` (ISO UTC), `changeType`, `oldValue`, `newValue` (each **string or null**). `changeType` one of:

- `GOAL_ADDED`, `GOAL_UPDATED`, `GOAL_DELETED` — old/new are **serialized JSON goal snapshots** `{id,title,successMeasure,order}` or null.
- `GOALS_REORDERED` — old/new are **serialized JSON arrays of goal ids**.
- `IN_SCOPE_CHANGED`, `OUT_OF_SCOPE_CHANGED` — old/new are **serialized TipTap JSON document strings** or null.

The old/new history is **immutable**. No history row is written for unchanged goals, scope fields or order. Stakeholder changes are intentionally outside the requested goal/scope history.

## Backend artifacts and tests

- Migration: `src/main/resources/db/migration/V13__project_charter.sql` (V11 task history and V12 chat already exist).
- Entities: `project/charter/models`; repository, service and controller under `project/charter/`.
- Permission: `ProjectPermission.EDIT_CHARTER` added to PM and PO only.
- Project state: `Project.lifecycleStatus`, default ACTIVE.
- Tests: `ProjectCharterIntegrationTest` (Testcontainers PostgreSQL/Flyway), `ProjectCharterRequestValidationTest`, updated `ProjectRoleTest` and `ProjectServiceTest`.

Run from the **backend repo root** with Docker available:

```bash
bash ./mvnw clean verify
```

## Integration responsibility

- **PMP-116** implements charter data, validation, history and permissions; it does **not** implement PMP-63 completion/archive actions.
- **PMP-117** (Aliaksandr) builds the project Charter tab, TipTap scope editors, ordered goals, stakeholder table and history display, and respects `canEdit` and `readOnly`.
- **PMP-118** (Mobeen) runs acceptance tests, including direct API attempts as a Viewer and outsider.
