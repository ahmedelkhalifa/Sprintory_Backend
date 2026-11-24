# Sprintory — Sprint retrospectives API contract (R31, PMP-44)

**Status:** contract agreed, backend not started (PMP-147, branch `features/retrospectives-pmp-44`, 24 Nov 2026). Frontend (PMP-148, Mobeen) can build against this file now. Acceptance testing: PMP-149 (Amir). See [§11 Implementation status](#11-implementation-status).
**Applies to:** backend `/api/v1/sprints/{sprintId}/retro`, `/api/v1/retros/**`, `/api/v1/retro-items/**`, the `hasRetro` / `retroId` fields of `SprintResponse`, and the `sprint` part of notifications.
**Related:** [Sprints](./sprints-docs.md), [Tasks](./tasks-docs.md), [Mentions and rich text](./mentions-docs.md), [Notifications](./notifications-docs.md), [Audit log](./audit-log-docs.md), [Roles and permissions](./roles-docs.md), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R31.

This document is the single reference for sprint retrospectives. The backend implements it, the frontend builds the retro pages against it, and acceptance testing checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a Scrum Master, I want to record a retrospective for each sprint, so that the team captures lessons learned and improvements."*

- **Scrum only.** Kanban projects have no sprints, so they have no retrospectives. Hide the feature there.
- **One retrospective per sprint**, only after the sprint has **ended** (`COMPLETED` or `CANCELLED`). `PLANNED` and `ACTIVE` sprints cannot have one. A cancelled sprint can: the team still learns from why its goal became obsolete.
- **No deadline.** A retro can be created any time after the sprint ended, also while the next sprint is running.
- **States:** `OPEN` (collecting cards, voting, actions) → `CLOSED` (read-only). A retro is `OPEN` as soon as it is created. A closed retro is never reopened.
- **Three fixed columns:** `WENT_WELL` ("Went well"), `TO_IMPROVE` ("To improve"), `IDEAS` ("Ideas").
- **Cards** have a named author (not anonymous) and rich text, the same TipTap format as comments, with @mentions ([mentions-docs.md §2](./mentions-docs.md#2-rich-text-format)).
- **Voting:** every member has **3 votes per retro**, at most **1 per card**. Removing a vote gives it back.
- **Action items** become real tasks (type `TASK`, label `retro-action`) in the backlog or in a planned sprint. The retro shows each action's **live** status, read from its task.
- **A new retro shows the unfinished actions of the previous retro**, so improvements are followed up.
- **Closing** freezes cards, votes and the action list. The action tasks carry on normally.
- **Not real-time.** Other people's cards and votes appear after a refresh or a poll ([§7.3](#73-retro-board)).
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Someone who cannot see the project gets `404`.
- **Read-only states:** an archived workspace (`409 WORKSPACE_ARCHIVED`) and a `COMPLETED` or `ARCHIVED` project (`409 CONFLICT`) allow reading only.

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Member/Developer | Scrum Master, Product Owner, Project Manager |
| --- | --- | --- | --- | --- | --- |
| Read a retro | 404 | 403 | allowed | allowed | allowed |
| Create (open) a retro | 404 | 403 | 403 | 403 | allowed |
| Add a card, vote, remove own vote | 404 | 403 | 403 | allowed | allowed |
| Edit **own** card | 404 | 403 | 403 | allowed | allowed |
| Edit someone else's card | 404 | 403 | 403 | 403 | 403 |
| Delete **own** card | 404 | 403 | 403 | allowed | allowed |
| Delete someone else's card | 404 | 403 | 403 | 403 | allowed |
| Create an action item | 404 | 403 | 403 | 403 | allowed |
| Close a retro | 404 | 403 | 403 | 403 | allowed |

- Two new permissions ([roles-docs.md §2](./roles-docs.md#2-permission-matrix)):
  - **`RETRO_MANAGE`**: Scrum Master, Product Owner, Project Manager. Create, close, create action items, delete anyone's card.
  - **`RETRO_CONTRIBUTE`**: every role except Viewer. Add cards, edit and delete own cards, vote.
- Reading needs `VIEW_WORK` (every project role).
- The project response's `permissions` list contains these names, so the frontend can show or hide buttons from it. The `can…` flags in the retro response ([§2](#bodies)) say the same per card. The backend checks again on every request.
- Workspace admins do **not** get these permissions through their workspace role: they need a project role.

---

## 2. Endpoints at a glance

All paths are under `/api/v1`. Bodies are JSON in `camelCase`. Every endpoint needs the access-token cookie.

| # | Method | Path | Request | Success | Permission |
| --- | --- | --- | --- | --- | --- |
| 3.1 | POST | `/sprints/{sprintId}/retro` | none | `201` (created) or `200` (already existed) + `RetroResponse` | `RETRO_MANAGE` |
| 3.2 | GET | `/retros/{retroId}` | none | `200` + `RetroResponse` | `VIEW_WORK` |
| 3.3 | POST | `/retros/{retroId}/items` | `RetroItemRequest` | `201` + `RetroItemResponse` | `RETRO_CONTRIBUTE` |
| 3.4 | PATCH | `/retro-items/{itemId}` | `RetroItemUpdateRequest` | `200` + `RetroItemResponse` | `RETRO_CONTRIBUTE`, author only |
| 3.5 | DELETE | `/retro-items/{itemId}` | none | `204` | author with `RETRO_CONTRIBUTE`, or `RETRO_MANAGE` |
| 3.6 | POST | `/retro-items/{itemId}/votes` | none | `200` + `RetroVoteResponse` | `RETRO_CONTRIBUTE` |
| 3.7 | DELETE | `/retro-items/{itemId}/votes` | none | `200` + `RetroVoteResponse` | `RETRO_CONTRIBUTE` |
| 3.8 | POST | `/retros/{retroId}/actions` | `RetroActionRequest` | `201` + `RetroActionResponse` | `RETRO_MANAGE` |
| 3.9 | POST | `/retros/{retroId}/close` | none | `200` + `RetroResponse` | `RETRO_MANAGE` |

Also changed:

| Endpoint | Change |
| --- | --- |
| `SprintResponse` (every sprint endpoint, incl. `GET /projects/{id}/sprints/history`) | new `hasRetro` (boolean) and `retroId` (number or `null`) |
| `NotificationResponse` | new `sprint` part `{ id, name }`, `null` unless the notification is about a sprint |
| `NotificationTargetResponse` (`POST /notifications/{id}/open`) | new `kind: "SPRINT"` with `sprintId` |

There is no endpoint to list a project's retros: the sprint history (`GET /projects/{projectId}/sprints/history`) carries `retroId` for each ended sprint. There is no rename, reopen or delete of a retro.

### Bodies

```jsonc
// RetroItemRequest: "body" is a string holding a TipTap document (mentions-docs.md §2)
{ "column": "TO_IMPROVE", "body": "{\"type\":\"doc\",\"content\":[...]}" }

// RetroItemUpdateRequest: the full new body; the column cannot change
{ "body": "{\"type\":\"doc\",\"content\":[...]}" }

// RetroActionRequest
{
  "title": "Time-box stand-ups to 15 minutes",  // required, 1–255 characters, becomes the task title
  "ownerId": 31,                                 // required, a project member; becomes the task's assignee
  "itemId": 512,                                 // optional: the card it came from (a live card of this retro)
  "sprintId": 44                                 // optional: a PLANNED sprint of this project; omitted = the backlog
}

// RetroResponse
{
  "id": 7,
  "projectId": 15,
  "title": "MOB Sprint 3 Retrospective",          // fixed at creation: "<sprint name> Retrospective"
  "status": "OPEN",                               // OPEN | CLOSED
  "createdAt": "2026-11-26T10:02:11Z",
  "createdBy": { "id": 9, "name": "Linus Torvalds" },   // null if the account no longer exists
  "closedAt": null,
  "closedBy": null,
  "sprint": {
    "id": 42, "name": "MOB Sprint 3", "status": "CANCELLED",   // COMPLETED | CANCELLED
    "goal": "Ship the login screen",
    "startDate": "2026-11-09", "endDate": "2026-11-22",
    "completedAt": "2026-11-20T16:40:00Z",
    "goalAchieved": null,                         // YES | PARTIALLY | NO for a closed sprint
    "goalNote": null,
    "cancelReason": "The client dropped the login feature."   // only for a cancelled sprint
  },
  "items": [ /* RetroItemResponse, oldest first; deleted cards are not listed */ ],
  "votesPerMember": 3,
  "votesLeft": 2,                                 // the caller's remaining votes in this retro
  "actions": [ /* RetroActionResponse, oldest first */ ],
  "previousActions": {                            // null when no earlier retro has unfinished actions
    "retroId": 6,
    "sprintName": "MOB Sprint 2",
    "actions": [ /* RetroActionResponse: only unfinished ones */ ]
  },
  "canContribute": true,                          // the caller may add cards and vote now (OPEN, has the permission, writable project)
  "canManage": true                               // the caller may create actions and close now
}

// RetroItemResponse
{
  "id": 512,
  "column": "TO_IMPROVE",
  "author": { "id": 31, "name": "Damir Gabaidullin" },   // null if the account no longer exists
  "body": "{\"type\":\"doc\",...}",              // stored, normalized TipTap JSON
  "mentions": [ { "kind": "user", "id": 7, "name": "Ada Lovelace" } ],  // current names
  "createdAt": "2026-11-26T10:05:40Z",
  "editedAt": null,                               // set when the text changed; show "(edited)"
  "voteCount": 4,
  "votedByMe": true,
  "canEdit": true,                                // own card, retro OPEN, has RETRO_CONTRIBUTE, writable project
  "canDelete": true                               // as above, or RETRO_MANAGE
}

// RetroVoteResponse: the card's new state and the caller's remaining votes
{ "itemId": 512, "voteCount": 5, "votedByMe": true, "votesLeft": 1 }

// RetroActionResponse
{
  "id": 3,
  "itemId": 512,                                  // null when created directly, or when the card was deleted later
  "createdAt": "2026-11-26T10:30:00Z",
  "task": {
    "id": 260, "key": "MOB-61",
    "title": "Time-box stand-ups to 15 minutes",  // live, from the task; null when deleted
    "statusName": "In Progress",                  // live column name; null when deleted
    "statusCategory": "IN_PROGRESS",              // TODO | IN_PROGRESS | DONE; null when deleted
    "assignee": { "id": 31, "name": "Damir Gabaidullin" },  // live; null when unassigned or deleted
    "sprintId": 44, "sprintName": "MOB Sprint 4", // live; null when in the backlog or deleted
    "deleted": false
  }
}
```

- `body` is a **string** holding the TipTap JSON, as for comments. Send `JSON.stringify(editor.getJSON())`, and pass `JSON.parse(body)` to the editor. The returned `body` is the normalized copy; render mentions with `mentions[].name`.
- **Done** means `statusCategory: "DONE"`, never a column name.
- Card ids are looked up inside the retro's project: an id from a project the caller cannot see gives the same `404` as a missing one.

---

## 3. Endpoints in detail

### 3.1 Create (open) a retro

`POST /api/v1/sprints/{sprintId}/retro`

- **Who:** `RETRO_MANAGE` (Scrum Master, Product Owner, Project Manager). Workspace active, project `ACTIVE`.
- **Idempotent.** Every entry point ([§7.1](#71-entry-points)) calls this same endpoint:
  - the sprint has no retro yet → it is created, `OPEN`, with the title `"<sprint name> Retrospective"`: **`201`**;
  - the sprint already has one → it is returned unchanged (also when it is `CLOSED`): **`200`**.
  - Two managers clicking at the same moment both end up in the same retro. The backend serializes it with the project row lock, and the database allows one retro per sprint.
- **Rules:** the sprint must be `COMPLETED` or `CANCELLED`; otherwise `409 RETRO_SPRINT_NOT_ENDED` "The sprint must be completed or cancelled first."
- **Side effects (only when created, `201`):** every project member except the creator gets `RETRO_OPENED` ([§6](#6-notifications)).
- **Response:** `RetroResponse`.
- **Errors:** `401`; `403` (no `RETRO_MANAGE`); `404` "Sprint not found." (missing, or a project the caller cannot see); `409 RETRO_SPRINT_NOT_ENDED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT` (project completed or archived, even when a retro exists: open existing retros through `retroId` and 3.2).

### 3.2 Read a retro

`GET /api/v1/retros/{retroId}`

- **Who:** `VIEW_WORK`. Allowed in archived workspaces and completed or archived projects.
- **Contents:** the whole board in one response, see `RetroResponse` in [§2](#bodies).
  - `items`: live cards, oldest first. Deleted cards are not listed. Sort by `voteCount` in the frontend for "sort by votes".
  - `votesLeft`: `3 −` the caller's votes in this retro. A Viewer always gets `0`.
  - `actions`: every action of this retro with its task's live state. A deleted task shows `deleted: true` and only `id` and `key`.
  - `previousActions`: the **unfinished** actions (task not deleted and not in a `DONE` column) of the **most recent earlier retro** of this project (by sprint number), even if a sprint in between had no retro. `null` when there is none or all its actions are done. A closed retro still shows them, as they are now.
- **Errors:** `401`; `403` (a workspace admin not in the project); `404` "Retrospective not found." (missing, or a project the caller cannot see).

### 3.3 Add a card

`POST /api/v1/retros/{retroId}/items`

- **Who:** `RETRO_CONTRIBUTE` (everyone but Viewers). Retro `OPEN`, workspace active, project `ACTIVE`.
- **Rules:**
  - `column` is `WENT_WELL`, `TO_IMPROVE` or `IDEAS`.
  - `body` is a valid TipTap document with some text or a mention, at most **1,000 characters** of text (a mention counts as its name). Script, HTML and unsafe links are refused, as for comments.
  - Every mentioned person must be a member of the project, and every mentioned team must have a member in it.
- **Side effects:** newly mentioned people get `MENTIONED` ([§6](#6-notifications)).
- **Response `201`:** `RetroItemResponse`.
- **Errors:** `400 VALIDATION_ERROR` (`column` or `body` missing); `400 INVALID_RICH_TEXT`; `400 INVALID_MENTION`; `401`; `403`; `404` "Retrospective not found."; `409 RETRO_CLOSED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT` (project completed or archived).

### 3.4 Edit a card

`PATCH /api/v1/retro-items/{itemId}`

- **Who:** the card's **author**, who must still have `RETRO_CONTRIBUTE`. Nobody else, not even a manager. Retro `OPEN`.
- **Rules:** as in 3.3. The column cannot change (delete and re-add instead). Sending the same text again changes nothing: `editedAt` stays and nobody is notified. Only **newly added** mentions are checked and notified.
- **Response `200`:** the updated `RetroItemResponse`.
- **Errors:** as in 3.3, plus `403` (not the author) and `404` "Card not found." (missing or deleted).

### 3.5 Delete a card

`DELETE /api/v1/retro-items/{itemId}`

- **Who:** the author with `RETRO_CONTRIBUTE`, or anyone with `RETRO_MANAGE`. Retro `OPEN`.
- **Effect:** the card disappears from the board. **Its votes are removed**, so everyone who voted on it gets that vote back. An action created from it keeps its task; its `itemId` becomes `null`.
- **Response:** `204`. Deleting it again gives `404` "Card not found.".
- **Errors:** `401`; `403`; `404`; `409 RETRO_CLOSED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

### 3.6 Vote on a card

`POST /api/v1/retro-items/{itemId}/votes`

- **Who:** `RETRO_CONTRIBUTE`. Retro `OPEN`. Voting on your own card is allowed.
- **Rules:**
  - at most **1 vote per card**: a second vote gives `409 ALREADY_VOTED` "You already voted on this card.";
  - at most **3 votes per retro**: a 4th gives `409 NO_VOTES_LEFT` "No votes left.".
  - The check runs under a lock on the retro, so two quick clicks can never spend a 4th vote.
- **Response `200`:** `RetroVoteResponse`.
- **Errors:** `401`; `403`; `404` "Card not found."; `409 ALREADY_VOTED`; `409 NO_VOTES_LEFT`; `409 RETRO_CLOSED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

### 3.7 Remove a vote

`DELETE /api/v1/retro-items/{itemId}/votes`

- **Who:** `RETRO_CONTRIBUTE`. Retro `OPEN`.
- Removes the **caller's own** vote and gives it back. Removing a vote you do not have is not an error: the response simply shows `votedByMe: false`.
- **Response `200`:** `RetroVoteResponse`.
- **Errors:** as in 3.6, without `ALREADY_VOTED` and `NO_VOTES_LEFT`.

### 3.8 Create an action item

`POST /api/v1/retros/{retroId}/actions`

- **Who:** `RETRO_MANAGE`. Retro `OPEN`.
- **Rules:**
  - `title` 1–255 characters (trimmed), required.
  - `ownerId` required, a **member of this project**; otherwise `400` "The owner must be a member of this project.".
  - `itemId` optional; if given, a live card of **this** retro, otherwise `404` "Card not found.".
  - `sprintId` optional; if given, a **`PLANNED`** sprint of this project, otherwise `400` "Actions can only go to the backlog or a planned sprint.". Omitted: the task goes to the end of the backlog.
- **Effect, in one transaction:** a task is created exactly like `POST /projects/{projectId}/tasks` ([tasks-docs.md](./tasks-docs.md)): next key, first board column, type `TASK`, priority `MEDIUM`, label **`retro-action`**, assigned to the owner. With `sprintId` it is placed at the end of that sprint. The task gets its normal `CREATED` history entry, and the owner gets the normal `TASK_ASSIGNED` notification (unless they created it themselves).
- **Response `201`:** `RetroActionResponse`.
- After that the task is an ordinary task: edit, move or delete it through the task endpoints. The retro only shows its live state.
- **Errors:** `400 VALIDATION_ERROR`; `400 BAD_REQUEST` (owner or sprint rule); `401`; `403`; `404` "Retrospective not found." / "Card not found." / "Sprint not found."; `409 RETRO_CLOSED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

### 3.9 Close a retro

`POST /api/v1/retros/{retroId}/close`

- **Who:** `RETRO_MANAGE`. Workspace active, project `ACTIVE`.
- **Effect:** status `CLOSED`, `closedAt`, `closedBy`. From then on, cards, votes and actions cannot change (`409 RETRO_CLOSED`). The action **tasks** continue normally and their live status keeps showing.
- **Side effects:** an audit entry `RETRO_CLOSED` ([audit-log-docs.md §4](./audit-log-docs.md)), and every project member except the closer gets `RETRO_CLOSED` ([§6](#6-notifications)).
- **Response `200`:** the closed `RetroResponse`. Closing an already closed retro gives `409 RETRO_CLOSED`.
- **Errors:** `401`; `403`; `404`; `409 RETRO_CLOSED`; `409 WORKSPACE_ARCHIVED`; `409 CONFLICT`.

---

## 4. Changes to sprints

`SprintResponse` ([sprints-docs.md §2](./sprints-docs.md#2-sprint-endpoints)) gains two fields on every sprint endpoint:

| Field | Meaning |
| --- | --- |
| `hasRetro` | `true` when the sprint has a retrospective (open or closed) |
| `retroId` | its id, or `null` |

Use them to switch between "Create retro" and "Open retro" without another call. `PLANNED` and `ACTIVE` sprints always have `hasRetro: false`.

---

## 5. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses). Branch on `code`, not on `message`.

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Request validation failed. | A required field missing or too long; `fieldErrors` names it |
| `BAD_REQUEST` | 400 | e.g. "The owner must be a member of this project.", "Actions can only go to the backlog or a planned sprint." | Action rules (3.8); malformed JSON |
| `INVALID_RICH_TEXT` | 400 | e.g. "A card cannot be empty.", "A card can have at most 1000 characters." | Card body breaks [mentions-docs.md §2](./mentions-docs.md#2-rich-text-format) |
| `INVALID_MENTION` | 400 | "Only members of this project can be mentioned." | A newly added mention outside the project |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | Missing permission; editing or deleting someone else's card without the right |
| `RESOURCE_NOT_FOUND` | 404 | Sprint not found. / Retrospective not found. / Card not found. | Not visible, missing, deleted, or in another project |
| `RETRO_SPRINT_NOT_ENDED` **(new)** | 409 | The sprint must be completed or cancelled first. | 3.1 on a `PLANNED` or `ACTIVE` sprint |
| `RETRO_CLOSED` **(new)** | 409 | This retrospective is closed. | Any change to a closed retro |
| `ALREADY_VOTED` **(new)** | 409 | You already voted on this card. | 3.6, second vote on the same card |
| `NO_VOTES_LEFT` **(new)** | 409 | No votes left. | 3.6, a 4th vote in the retro |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Any change in an archived workspace |
| `CONFLICT` | 409 | Retrospectives are read-only when the project is completed or archived. | Any change in a completed or archived project |

---

## 6. Notifications

Three new types, all in the existing **`SPRINT`** preference category (in-app on, email off by default; [notifications-docs.md §6](./notifications-docs.md#6-preferences)). Each carries the new `sprint` part, and opening it answers `kind: "SPRINT"` with `projectId` and `sprintId`: navigate to the ended sprint's page, which offers "Open retro" through `retroId`.

| Type | When | Who is told | Example message |
| --- | --- | --- | --- |
| `RETRO_REMINDER` | A sprint is closed or cancelled | the **other** `RETRO_MANAGE` holders (Scrum Master, Product Owner, Project Manager), never the person who ended it. Once, not repeated. | *"MOB Sprint 3" in "Mobile App" ended. Start the retrospective.* |
| `RETRO_OPENED` | A retro is created (3.1, `201` only) | every project member except the creator | *Linus Torvalds opened the retrospective of "MOB Sprint 3" in "Mobile App"* |
| `RETRO_CLOSED` | A retro is closed (3.9) | every project member except the closer | *Linus Torvalds closed the retrospective of "MOB Sprint 3" in "Mobile App"* |
| `MENTIONED` (existing) | Someone is newly mentioned in a card (3.3, 3.4) | the mentioned people (an `@team` expands to its members in the project) | *Damir Gabaidullin mentioned you in the retrospective of "MOB Sprint 3"*, with a short excerpt |

- The existing sprint notifications (`SPRINT_COMPLETED`, `SPRINT_CANCELLED`) still go to every member, and now also carry the `sprint` part.
- Votes, card edits and card deletes notify nobody.
- Like every notification: the actor is never told, only current project members are told, and the text never contains a card's full body.

---

## 7. Frontend flows (PMP-148)

Show retro features only in **Scrum** projects. Use the project's `permissions` list: `RETRO_MANAGE` for create, actions and close; `RETRO_CONTRIBUTE` for cards and votes.

### 7.1 Entry points

All three call `POST /sprints/{sprintId}/retro`, then navigate to the retro with the returned `id` (whether the answer was `201` or `200`).

1. **Prompt after ending a sprint.** When `POST /sprints/{id}/complete` or `/cancel` succeeds **and** the caller has `RETRO_MANAGE`, the success dialog offers "Start retrospective" and "Not now". "Not now" just closes the dialog; nothing is lost. Only the person who ended the sprint sees this prompt; the others get the `RETRO_REMINDER` notification.
2. **Ended sprint page** (`GET /sprints/{id}`): "Create retro" while `hasRetro` is `false` (managers only), "Open retro" when `true` (everyone; it links to `/retros/{retroId}` without calling POST).
3. **Sprint history list** (`GET /projects/{projectId}/sprints/history`): the same per row.

Members without `RETRO_MANAGE` never see "Create retro" or the prompt.

### 7.2 Notification clicks

`POST /notifications/{id}/open` answers `kind: "SPRINT"` → go to the ended sprint's page (`projectId`, `sprintId`); from there "Open retro".

### 7.3 Retro board

1. Load `GET /retros/{retroId}`. Header: `title`, sprint dates, status badge (`OPEN` / `CLOSED`). For a cancelled sprint, show `sprint.cancelReason`; for a closed sprint, `goalAchieved` and `goalNote`.
2. **Previous actions** (when `previousActions` is not `null`): a panel "Unfinished actions from {sprintName}" listing the tasks with their status and owner.
3. **Three columns** with the cards, each rendered with a read-only TipTap editor from `JSON.parse(body)`, the author's name, and "(edited)" when `editedAt` is set. A "Sort by votes" toggle sorts by `voteCount` (ties: oldest first).
4. **Add a card** (when `canContribute`): an editor per column; `@` suggestions come from `GET /projects/{projectId}/mentionables?q=` ([mentions-docs.md §3](./mentions-docs.md#3-endpoint-mention-suggestions)). Max 1,000 characters; show a counter.
5. **Edit / Delete** buttons from `canEdit` / `canDelete`. Confirm before deleting ("Its votes will be returned.").
6. **Votes:** a vote button per card showing `voteCount`, highlighted when `votedByMe`. Show "Votes left: {votesLeft} / {votesPerMember}". When `votesLeft` is `0`, disable the vote button on every card you have not voted on (you can still remove a vote). After a vote call, update the card and `votesLeft` from `RetroVoteResponse`.
7. **Actions** list with each task's key (link to the task), title, owner and status chip from `statusCategory`. "Create action" (when `canManage`), also offered on a card ("Make action"): title (prefill with the card's plain text, max 255), owner (project members), destination "Backlog" or a planned sprint (`GET /projects/{projectId}/sprints`, `status: PLANNED` only).
8. **Close** (when `canManage` and `OPEN`): confirm "Closing freezes cards, votes and actions." Then reload.
9. **Not real-time:** a "Refresh" button, and optionally a poll of `GET /retros/{id}` every 15–30 seconds while the page is open and the retro is `OPEN`.
10. **Closed retro:** no inputs, no vote buttons, no "Create action"; everything stays readable, and action statuses stay live.
11. On `409 RETRO_CLOSED`, reload the retro (someone closed it meanwhile). On `409 NO_VOTES_LEFT` / `ALREADY_VOTED`, reload to resync. On `400 INVALID_MENTION` or `INVALID_RICH_TEXT`, keep the draft and show the message.

---

## 8. Data model

Migration `V18__retrospectives.sql`.

| Table | Columns | Constraints |
| --- | --- | --- |
| `retrospectives` | `id`, `project_id`, `sprint_id`, `title`, `status`, `created_by_id`, `created_at`, `closed_at`, `closed_by_id` | `UNIQUE(sprint_id)`; `status in ('OPEN','CLOSED')` |
| `retro_items` | `id`, `retro_id`, `column_name`, `body` (`text`, normalized TipTap JSON), `author_id`, `created_at`, `edited_at`, `deleted_at` | `column_name in ('WENT_WELL','TO_IMPROVE','IDEAS')`; index on (`retro_id`, `created_at`) |
| `retro_votes` | `id`, `retro_id`, `item_id`, `user_id` | `UNIQUE(item_id, user_id)`; index on (`retro_id`, `user_id`) for the 3-vote count |
| `retro_actions` | `id`, `retro_id`, `item_id` (nullable), `task_id`, `task_key` (snapshot), `created_by_id`, `created_at` | `UNIQUE(task_id)` |

- **Foreign keys:** `project_id`, `sprint_id`, `retro_id` and `task_id` cascade on delete (deleting a project removes everything). `item_id` on actions is `SET NULL`. `author_id` / `created_by_id` / `closed_by_id` are `SET NULL` so retros survive a deleted account; `user_id` on votes cascades.
- **The task holds the action's title, status and owner** (its assignee); the retro stores only the link and the key snapshot, so a deleted task can still be shown by key.
- **Cards are soft-deleted** (`deleted_at`) but never listed again; their votes are deleted with the soft delete.
- **Leaving the project** does not touch cards, votes or actions: a former member's cards stay under their name.
- Also in V18: `notifications.sprint_id` + `sprint_name` (snapshot, no foreign key), the three new values in `ck_notifications_type`, and `RETRO_CLOSED` in `ck_audit_log_entries_action`.
- **Code:** `retro/{models,repositories,services,controllers,dto,exceptions}`. `RetroService` holds the rules.

---

## 9. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| Every retro endpoint | Nothing about projects the caller cannot see: `404`, as for a missing one |
| `/retro-items/{itemId}` paths | Not whether a card exists in another project: the same `404` as a missing card |
| A card | Its author: retros are not anonymous (story decision) |
| Votes | Only counts and the caller's own votes, never who else voted |

---

## 10. Known limitations

- **No real-time updates.** Refresh or poll.
- **No anonymous mode, templates (Start-Stop-Continue, 4Ls), mood ratings or retro analytics.**
- **No project-level retros**: one per sprint only.
- **No reopening, renaming or deleting a retro**, and no restoring a deleted card.
- **No editing an action** from the retro: change its task instead.

---

## 11. Implementation status

As of 24 Nov 2026:

| Part | Status |
| --- | --- |
| This contract | Written, agreed with the backend owner; frontend can start |
| Permissions `RETRO_MANAGE`, `RETRO_CONTRIBUTE` | Not started |
| `V18__retrospectives.sql`, entities, repositories | Not started |
| Open / read (3.1, 3.2), `hasRetro` / `retroId` | Not started |
| Cards and mentions (3.3–3.5) | Not started |
| Voting (3.6, 3.7) | Not started |
| Action items (3.8) | Not started |
| Close and audit (3.9) | Not started |
| Notifications (§6) and the `sprint` part | Not started |
| Integration test on PostgreSQL | Not started |
| Frontend (PMP-148) and acceptance test (PMP-149) | Not started |

---

## 12. Decisions

| Date | Decision |
| --- | --- |
| 24 Nov 2026 | Two permissions: `RETRO_MANAGE` (SM, PO, PM) and `RETRO_CONTRIBUTE` (all but Viewer). Workspace admins get neither through their workspace role. |
| 24 Nov 2026 | Any `RETRO_MANAGE` holder can delete anyone's card, not only the Scrum Master. |
| 24 Nov 2026 | Deleting a card returns its votes. Deleted cards are hidden, not shown as placeholders. |
| 24 Nov 2026 | Cards are rich text like comments, max 1,000 characters; @mentions are allowed and notified (`MENTIONED`). |
| 24 Nov 2026 | Any `RETRO_MANAGE` holder can put an action into a `PLANNED` sprint, not only the Scrum Master. Never into an active or ended sprint. |
| 24 Nov 2026 | The action owner is the task's assignee, so there is one source of truth. |
| 24 Nov 2026 | "Previous actions" come from the most recent earlier retro, even if a sprint in between had none. |
| 24 Nov 2026 | The title is fixed ("<sprint name> Retrospective"); no rename. |
| 24 Nov 2026 | Opening is idempotent and serialized by the project row lock; `UNIQUE(sprint_id)` is the backstop. |
| 24 Nov 2026 | Retro notifications use the existing `SPRINT` category. Notifications gain a `sprint` reference and a `SPRINT` open target, so the bell can lead to the sprint and its retro. |
| 24 Nov 2026 | Paths are `/retro-items/…` instead of the story's `/items/…`, which is too generic under `/api/v1`. |
