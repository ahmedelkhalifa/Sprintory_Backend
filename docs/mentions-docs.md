# Sprintory — @mentions and rich text contract (R26 part A, PMP-31)

**Status:** backend implemented and tested, including against PostgreSQL (PMP-136, branch `features/comments_history`, 5 Nov 2026). Frontend (PMP-138) and acceptance testing (PMP-140) open. See [§10 Implementation status](#10-implementation-status).
**Applies to:**
- the rich-text format of task comments, task and subtask descriptions, and project chat messages (PMP-137);
- backend `GET /api/v1/projects/{projectId}/mentionables`;
- the mention rules of `…/comments` and of task create and edit.

**Related:** [Comments](./comments-docs.md), [Tasks](./tasks-docs.md) (description), [Notifications](./notifications-docs.md) (`MENTIONED`), [Teams](./teams-docs.md), [Projects](./projects-docs.md) (members), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Requirements](./requirements.md) R26.

This document is the single reference for @mentions and for the rich-text format. The backend implements it, the frontend (PMP-138, Amir) builds the editor and the suggestion list against it, and acceptance testing (PMP-140, Mobeen) checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a team member, I want to comment, mention colleagues, and chat within the project, so that I can get answers without leaving the platform."* This file covers part A, mentions. Chat (part B, PMP-137) has its own contract and reuses the rich-text format of §2.

- **Where:** typing `@` in a **comment** or a **task description** opens a suggestion list. Subtasks (PMP-132) work the same way: their comments and their description take mentions, through the subtask endpoints ([tasks-docs.md §11.2](./tasks-docs.md#112-subtask-endpoints)).
- **Who can be mentioned:** members of this project only, Viewers included. People outside the project cannot.
- **`@team`:** mentions the members of a team **who are in this project**.
- **Storage:** a mention is a TipTap `mention` node holding the **id** of the person or team, never their name. The current name is shown, so renaming a user or a team does not break old mentions.
- **Notifications:** the mentioned person gets `MENTIONED` ([notifications-docs.md §5](./notifications-docs.md#5-event-catalogue)). Editing notifies **only newly added** mentions. Mentioning yourself sends nothing.
- **The backend checks every rich text it stores** (§2). No HTML is ever stored, so a stored text cannot carry a script.

---

## 2. Rich-text format

Comments, task descriptions and subtask descriptions are [TipTap](https://tiptap.dev) documents, sent and returned as a **string** holding the editor's JSON (`JSON.stringify(editor.getJSON())`).

```json
{
  "type": "doc",
  "content": [
    { "type": "paragraph", "content": [
      { "type": "text", "text": "Ready for review, " },
      { "type": "mention", "attrs": { "kind": "user", "id": "31", "label": "Damir Gabaidullin" } },
      { "type": "text", "text": " and " },
      { "type": "mention", "attrs": { "kind": "team", "id": "5", "label": "Backend" } },
      { "type": "text", "text": ". See " },
      { "type": "text", "text": "the spec", "marks": [ { "type": "link", "attrs": { "href": "https://example.com/spec" } } ] }
    ] }
  ]
}
```

### 2.1 Allowed nodes

| Node | Contains | Attributes kept |
| --- | --- | --- |
| `doc` | block nodes; only at the top | none |
| `paragraph` | inline nodes | none |
| `heading` | inline nodes | `level`: 1, 2 or 3 |
| `bulletList` | `listItem` | none |
| `orderedList` | `listItem` | `start`: a whole number ≥ 1 (default 1) |
| `listItem` | block nodes | none |
| `blockquote` | block nodes | none |
| `codeBlock` | `text` without marks | `language`: at most 30 characters of `a-z 0-9 + # -`, or `null` |
| `horizontalRule` | nothing | none |
| `text` | (leaf) | `text` (required, not empty) and `marks` |
| `hardBreak` | nothing | none |
| `mention` | nothing | `kind`, `id`, `label` (see 2.3) |

### 2.2 Allowed marks

`bold`, `italic`, `underline`, `strike`, `code`, and `link`.

- `link` must have `href`, at most 2,000 characters, using `http:`, `https:` or `mailto:`. Anything else, such as `javascript:` or `data:`, is refused.
- The frontend always renders links with `rel="noopener noreferrer nofollow"` and `target="_blank"`. These are not stored.

### 2.3 The mention node

| Attribute | Rules |
| --- | --- |
| `kind` | `"user"` or `"team"`. Missing means `"user"`. |
| `id` | the user's or team's id, a positive whole number, as a number or a string (TipTap stores strings) |
| `label` | optional; a longer one is cut to 100 characters, not refused. Only a fallback for display: **show the current name from the response's `mentions` list**, never `label`. |

The frontend extends TipTap's `Mention` extension with the `kind` attribute (default `"user"`).

### 2.4 What the backend does with a text

1. **Parses** the string as JSON. It must be a `doc` node.
2. **Refuses** an unknown node or mark type, a node in the wrong place (such as text directly in a list), an unsafe link, a bad mention, or nesting deeper than 20 levels: `400 INVALID_RICH_TEXT`, and the message says which.
3. **Normalizes:** it keeps only the attributes listed above and drops the rest (for example `class` or `style`), then stores the result as compact JSON. The response returns this normalized string, which can differ from what was sent.
4. **Builds the plain text** used for limits and notification excerpts: the text of every node, block nodes separated by a line break, and each mention as `@` + current name.
5. **Empty text** means no characters other than spaces, and no mention:
   - a **comment** cannot be empty: `400 INVALID_RICH_TEXT` "A comment cannot be empty.";
   - an empty **description** is stored as "no description" (`null`), as a blank description is today.

### 2.5 Limits

| Text | Limit |
| --- | --- |
| Comment | at most 10,000 characters of plain text (2.4 step 4) |
| Task description | no length limit (tasks decision of 26 Oct 2026); the structure rules apply |
| Both | nesting depth at most 20; at most 50 different mentions |

### 2.6 Task descriptions

- `description` in `CreateTaskRequest` and `UpdateTaskRequest` must follow this format. A plain-text string such as `"Steps to reproduce: ..."` is refused with `400 INVALID_RICH_TEXT`.
- `TaskResponse.description` returns the normalized JSON string.
- **Descriptions saved before 4 Nov 2026** may still be plain text. When `JSON.parse` fails, the frontend shows the text as one paragraph. Saving it through the editor converts it.

---

## 3. Endpoint: mention suggestions

`GET /api/v1/projects/{projectId}/mentionables?q=`

- **Who:** members with `VIEW_WORK` (every project role). Archived workspace: allowed. A workspace admin who is not in the project gets `403`; someone who cannot see the project gets `404` "Project not found.".
- **Query:** `q` is optional and matched case-insensitively as "contains":
  - people: first name, last name, "first last", or email;
  - teams: the team name.
  A blank `q` returns the first entries in name order.
- **Results:**
  - **people:** members of this project in any role, including Viewers, **except the caller**. At most 10, sorted by first and last name.
  - **teams:** teams of the project's workspace with **at least one member in this project**. At most 5, sorted by name. `membersInProject` says how many of the team's members are in the project (the people a `@team` reaches, the caller included).
- **Response `200`:**

  ```json
  {
    "users": [
      { "id": 31, "name": "Damir Gabaidullin", "email": "damir@example.com" }
    ],
    "teams": [
      { "id": 5, "name": "Backend", "membersInProject": 3 }
    ]
  }
  ```

- Not paginated: it feeds a short dropdown. Narrow it with `q`.

---

## 4. Rules

Applied when a comment is posted or edited, and when a task is created or its description is changed.

1. **Only project members can be mentioned.** A person must be a member of the project, in any role. A team must belong to the project's workspace and have at least one member in the project.
2. **Only newly added mentions are checked.** The backend compares the mentions in the new text with those in the old text (by `kind` and `id`):
   - a **new** mention that breaks rule 1 is refused: `400 INVALID_MENTION`, and nothing is saved;
   - a mention that was **already** there is kept without a check, even if that person has left the project or the team was emptied. Editing an old comment never fails because of it.
   - On create, every mention is new.
3. **`@team` expands when the text is saved:** to the team's members who are in this project **at that moment**. People who join the team or the project later are not notified.
4. **Who is notified:** the newly mentioned people (teams expanded), minus the author, minus anyone who is not a project member. Each person is notified **once** per save, even when mentioned twice or both by name and through a team.
5. **Mentioning yourself** is allowed in the text and sends nothing.
6. **Removed mentions** send nothing.
7. **Mentions outrank watching.** A person mentioned in a comment on a task they watch gets only `MENTIONED`, not `TASK_COMMENTED`. A mention reaches people who unwatched the task. Delivery and preferences are in [notifications-docs.md](./notifications-docs.md).
8. **Descriptions:** creating a task notifies everyone mentioned in its description. Editing the description notifies only newly added mentions. `clearDescription` notifies nobody.

---

## 5. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses). Branch on `code`, not on `message`.

| Code | Status | Message (examples) | When |
| --- | --- | --- | --- |
| `INVALID_RICH_TEXT` | 400 | "The text is not a valid document." / "Node type 'image' is not allowed." / "Mark type 'textStyle' is not allowed." / "Links must use http, https or mailto." / "A mention needs a valid id." / "The text is nested too deeply." / "A comment cannot be empty." / "A comment can have at most 10000 characters." / "A text can mention at most 50 people and teams." | A comment `body` or task `description` breaks §2 |
| `INVALID_MENTION` | 400 | "Only members of this project can be mentioned." / "This team has no members in this project." | A newly added mention breaks §4 rule 1 |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | `/mentionables` for a workspace admin who is not in the project |
| `RESOURCE_NOT_FOUND` | 404 | Project not found. | `/mentionables` for a project the caller cannot see |

---

## 6. Frontend flows (PMP-138)

1. **One editor setup** for comments and descriptions: StarterKit limited to the nodes and marks of §2.1 and §2.2, plus `Link` and `Mention` extended with `kind`.
2. **Suggestions:** on `@` + text, call `GET /projects/{projectId}/mentionables?q=<text>`, with a short debounce. Show people first, then teams marked as teams (for example "Backend · team · 3 people").
3. **Inserting:** a person inserts `{ kind: "user", id, label: name }`; a team inserts `{ kind: "team", id, label: name }`.
4. **Showing:** draw mentions as highlighted links with the **current** name from the response's `mentions` list. A mention whose id is missing there shows its `label`.
5. **Errors:** on `400 INVALID_MENTION`, keep the draft and show the message (the person probably left the project while you typed).
6. **Old plain-text descriptions:** see §2.6.

---

## 7. Code and data

- **No new table.** Mentions live inside the stored JSON. Comment responses list the current names of their mentions.
- `common/richtext/RichTextParser` checks, normalizes and reads a text. It returns a `RichTextDocument` with the normalized JSON, the plain text, and the mentioned user and team ids. The project chat (PMP-137) uses the same parser.
- `mention/services/MentionService` applies §4. `newlyMentionedUserIds(project, text, previous)` compares old and new mentions, checks the new ones (`InvalidMentionException` → `INVALID_MENTION`), and expands teams to their members in the project. It returns the people to notify; the notification listener drops the author. `mentionables(...)` serves §3.
- `mention/controllers/MentionController` serves `/mentionables`.
- The notifications are published as the existing events: `TaskCommented` (comment posted, with its mentions) and `Mentioned` (comment edited, description created or changed). See [notifications-docs.md §15](./notifications-docs.md#15-notes-for-backend-developers).

---

## 8. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| `/mentionables`, people | Names and emails of this project's members, which members already see in the member list |
| `/mentionables`, teams | The names of the workspace's teams that have someone in this project, and how many of their members are in it. A plain member's team list shows only their own teams, so this is a little more. It was accepted (§11). |
| `INVALID_MENTION` | Only "not a member of this project" |
| A mention of someone who left | Their current name, as for any old comment author |

---

## 9. Known limitations

- **No `@all` or `@here`.**
- **No notification when a mention is removed**, and none for people who join a mentioned team later.
- **The stored `label` goes stale** after a rename. The responses' `mentions` lists always carry the current name.
- **Descriptions saved before 4 Nov 2026** can still be plain text (§2.6).
- **Images, tables, task links and text colours** are not part of the format yet. Adding one is a change to §2 and to `RichTextParser`.

---

## 10. Implementation status

As of 6 Nov 2026 (merged to `main`; the subtask and chat rows come from the refactoring branch `fix/refactoring`):

| Part | Status |
| --- | --- |
| This contract | Written, agreed with the backend owner |
| `RichTextParser` (format, normalizing, plain text), `INVALID_RICH_TEXT` | Done; `RichTextParserTest` (23), `ExceptionHandlerTest`. Used by comments, task and subtask descriptions, and chat messages |
| `MentionService` (rules of §4) and `/mentionables`, `INVALID_MENTION` | Done; `MentionServiceTest` (16), `MentionControllerTest`, `ExceptionHandlerTest`, and `MentionIntegrationTest` (7) on PostgreSQL for the suggestion queries and team expansion |
| Mentions in task descriptions (TipTap JSON required, new mentions publish `Mentioned`) | Done; `TaskApiServiceTest`, and `MentionIntegrationTest` end to end on PostgreSQL: one notification per person, editing notifies only new people, outsiders and plain text refused |
| Mentions in comments | Done; `CommentServiceTest`, `CommentIntegrationTest` on PostgreSQL |
| Mentions in subtask descriptions (PMP-132) | Done (6 Nov 2026); shared with tasks through `task/services/TaskContent`. `SubtaskServiceTest`, `TaskHistoryIntegrationTest` on PostgreSQL |
| Mentions in project chat (PMP-137) | Done (6 Nov 2026); `ChatService` publishes `ProjectChatMentioned`, which notifies about the project. `ChatServiceTest`, `NotificationListenerTest`, `MentionIntegrationTest` end to end on PostgreSQL |
| Notifications listener for `MENTIONED` | Done (PMP-113) |
| Frontend (PMP-138) and acceptance test (PMP-140) | Not started |

---

## 11. Decisions

| Date | Decision |
| --- | --- |
| 4 Nov 2026 | Rich text is TipTap JSON, checked and normalized by the backend with an allow-list. No HTML is stored. Comments, descriptions and chat share one parser. |
| 4 Nov 2026 | Task descriptions must be TipTap JSON too. Plain-text descriptions are refused from now on. |
| 4 Nov 2026 | A mention stores the id and a `kind` (`user` / `team`). The display name always comes from the server. |
| 4 Nov 2026 | `@team` is offered for any workspace team with at least one member in the project, and expands to its project members when the text is saved. |
| 4 Nov 2026 | Only newly added mentions are checked and notified. A new mention outside the project is refused (`400`); old ones are kept even after the person leaves. |
| 4 Nov 2026 | Suggestions list every project member, Viewers included, except the caller. |