# PMP-137 — Project chat backend

## Scope

One persisted channel per project. Spring Boot 4.x, STOMP over native WebSocket, PostgreSQL V11. No frontend implementation, DM, threads, attachments, typing indicators or read receipts. Task comments and mention notification delivery belong to PMP-136; coordinate rich-text schema when that branch merges. Chat currently validates project-member mention nodes but **does not create mention notifications**.

## Authentication

The existing HTTP access JWT is in an **HttpOnly cookie** (not readable by JavaScript). A signed-in client first sends `POST /api/v1/chat/socket-token` with credentials and receives `{ "token": "...", "expiresInSeconds": 120 }`. This short-lived purpose-limited JWT is **only** accepted by STOMP CONNECT, and cannot authenticate REST. Do not store this token in localStorage.

Native WebSocket: `ws://localhost:8080/ws/chat` (use `wss://` under HTTPS). STOMP `CONNECT` header: `Authorization: Bearer <token>`. Only the configured `app.frontend-url` is allowed as a browser WebSocket origin.

## STOMP

| Action | Destination | JSON request |
| --- | --- | --- |
| Subscribe | `/topic/projects/{projectId}/chat` | none |
| Send | `/app/projects/{projectId}/chat.send` | `{ "content": { "type":"doc", "content":[...] } }` |
| Edit | `/app/projects/{projectId}/chat.edit` | `{ "messageId": 123, "content": { "type":"doc", "content":[...] } }` |
| Delete | `/app/projects/{projectId}/chat.delete` | `{ "messageId": 123 }` |

Outbound JSON events delivered on topic **only after commit**:

```json
{
  "type": "CREATED",
  "projectId": 42,
  "message": {
    "id": 123,
    "projectId": 42,
    "authorId": 7,
    "authorName": "Jane Doe",
    "content": {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Hello"}]}]},
    "createdAt": "2026-11-05T12:00:00Z",
    "editedAt": null,
    "deleted": false
  }
}
```

`type`: `CREATED`, `EDITED`, `DELETED`; a deletion has `content: null` and `deleted: true`. Frontend displays “Message deleted” for tombstones; edit timestamp indicates “(edited)”.

## History

`GET /api/v1/projects/{projectId}/chat/messages?limit=50&before={oldestReceivedMessageId}`

Uses the existing HttpOnly access cookie. `limit` 1..100 (default 50). `before` is **exclusive**. Returns `{ "messages":[...], "nextBefore": 123, "hasMore": true }`. Messages are **oldest-to-newest** within each page, including deletion tombstones. `nextBefore` is null when no older page exists.

## Permissions and revocation

- Subscribe/history: current project members only (including Viewer).
- Send: active-project member with COMMENT permission (Viewer denied).
- Edit/delete: author or PROJECT_MANAGER (both require current send permission).
- STOMP SEND and SUBSCRIBE destinations are strictly allowlisted. Unknown `/topic` publishing is rejected.
- **Every outbound MESSAGE delivery rechecks project membership**, so even an already-subscribed former member receives no subsequent project-chat events after removal. This requires one indexed DB membership lookup per delivery; appropriate for the single-node simple-broker setup. Before scaling to multiple instances or a broker relay, re-evaluate transport-level access and invalidation.
- Archived workspace remains readable but not writable (consistent with `ProjectAccess`).

## Content

The body is a TipTap `doc` JSON tree. An allowlist strips **nothing silently**: unknown node types/attrs/marks or unsafe link schemes are rejected (400 in REST service; STOMP ERROR on SEND). HTML nodes are never accepted. Literal `<script>` in a text node stays harmless JSON text; frontend must render the JSON as TipTap nodes, **never** via `dangerouslySetInnerHTML`.

Allowed nodes: doc, paragraph, heading, bulletList, orderedList, listItem, blockquote, codeBlock, text, hardBreak, mention. Allowed marks: bold, italic, underline, strike, code, http(s)/mailto link. Max 400 nodes / 10,000 characters. A person mention uses `{ "type":"mention", "attrs": {"id":"7", "label":"Jane Doe"} }`. It is rejected if ID 7 is not a member of the project; label is replaced with the database's current display name. Team mention expansion and notification delivery are in PMP-136 and require a follow-up contract integration.

## Tests

With Docker running for Testcontainers:

```bash
cd ~/Desktop/coding_no_venv/python/real_projects/0-advanced/sprintory/Sprintory_Backend
bash ./mvnw clean verify
```

The integration test `ChatStompIntegrationTest` boots Spring on a random port with disposable PostgreSQL, connects **two real STOMP clients** with separate JWTs, sends an event and verifies live delivery, then removes one member and verifies delivery stops. Unit tests check content filtering, moderation, pagination, token separation, subscription checks and revocation.

`application-test.properties` enables Flyway and sets Hibernate to `validate` so V11 schema changes are checked against the entity.
