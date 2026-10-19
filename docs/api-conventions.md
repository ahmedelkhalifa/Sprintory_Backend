# Sprintory — API and Git Conventions

**Status:** Draft for team review  
**Applies to:** Spring Boot REST backend and both GitHub repositories

This document defines a small, consistent set of conventions for our university project. Keep the implementation simple; only add rules when we actually need them.

## 1. API URLs and HTTP methods

- All backend endpoints start with `/api/v1` so future breaking changes can use another version.
- Use plural resource names and lowercase, hyphenated URLs: `/api/v1/projects`, `/api/v1/projects/{projectId}/tasks`.
- Use database-generated numeric IDs. Clients must not choose resource IDs.
- Use HTTP methods according to their purpose:

| Method | Purpose | Example |
| --- | --- | --- |
| `GET` | Read resources | `GET /api/v1/projects/12` |
| `POST` | Create a resource | `POST /api/v1/projects` |
| `PUT` | Replace an entire resource (only when needed) | `PUT /api/v1/projects/12` |
| `PATCH` | Update selected fields | `PATCH /api/v1/projects/12` |
| `DELETE` | Delete a resource | `DELETE /api/v1/projects/12` |

Typical success responses: `200 OK` for a read/update, `201 Created` for creation, and `204 No Content` for successful deletion. Do not return `200 OK` for a resource that does not exist.

## 2. DTOs and JSON

- Use separate DTOs for request and response bodies, for example `CreateProjectRequest`, `UpdateProjectRequest`, and `ProjectResponse`.
- Controllers accept request DTOs, call services, and return response DTOs. Business rules belong in services.
- Do not expose database entities directly through the public API.
- Use `camelCase` JSON property names, for example `projectId` and `createdAt`.
- Return timestamps in ISO 8601 format with a timezone (prefer UTC), for example `2026-10-19T12:30:00Z`.
- Do not return passwords, password hashes, tokens, or internal-only fields in response DTOs.

## 3. Input validation

- Validate incoming request DTOs using Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@NotNull`, etc.).
- Reject invalid input with `400 Bad Request` and a useful, consistent error response.
- Validate business rules in services as well; for example, reject assigning a task to someone without the necessary project access.
- Never rely on frontend validation alone. The backend is responsible for enforcing the rules.

## 4. Standard error responses

Use the same JSON shape across endpoints:

```json
{
  "timestamp": "2026-10-19T12:30:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/api/v1/projects",
  "fieldErrors": {
    "name": "must not be blank"
  }
}
```

`fieldErrors` is optional and appears only for field-specific validation failures. The rest of the fields should be present for every API error. Use stable machine-readable `code` values and safe human-readable messages. Do not expose stack traces or sensitive internal details to clients.

| Status | When to use |
| --- | --- |
| `400 Bad Request` | Invalid JSON, invalid values, or validation failure |
| `401 Unauthorized` | Request requires authentication and user is not authenticated |
| `403 Forbidden` | Authenticated project member lacks permission for this operation |
| `404 Not Found` | Resource does not exist **or is private and not visible to this user** |
| `409 Conflict` | Request conflicts with existing data or state, such as a duplicate unique value |
| `500 Internal Server Error` | Unexpected backend error (generic public message; details go to server logs) |

## 5. Private projects and access control

**All projects are private by default. There are no public/shared projects in the initial scope.**

- Unauthenticated request to a protected endpoint → `401`.
- Authenticated user requests a project they **do not belong to** → `404`, even if that project exists.
- Authenticated project member tries an action prohibited by their role → `403`.
- Missing project and inaccessible project must receive the same generic `404` response (for example, `RESOURCE_NOT_FOUND`, `Project not found`).
- Enforce this consistently for project details, tasks, sprints, files, reports, and other nested resources. Collection/search endpoints must only return items the user may see.
- Access checks must happen in the backend; hiding buttons or routes in React is not authorization.

Example:

```http
GET /api/v1/projects/42
```

If project `42` does not exist **or** the authenticated user is not a member, return `404` in either case. Do not reveal in the error message whether the project exists.

## 6. Git branches, Pull Requests, and scope

Keep the frontend and backend in their **existing separate repositories**. `main` is the shared stable branch; do not develop directly on it.

Before starting a task, update local `main` and create a new branch from it:

```bash
git checkout main
git pull --ff-only origin main
git switch -c pages/login_page  # frontend page example
```

Use the team's current naming scheme:

| Work | Example branch |
| --- | --- |
| Frontend page | `pages/login_page` |
| Backend feature | `features/auth_login` |
| Bug fix | `fix/login_error` |
| Documentation | `docs/api-conventions` |
| CI configuration | `ci/backend-actions` |

- One branch/PR per focused task where practical; reference the related Jira ticket in the PR description.
- Push the branch and open a Pull Request against `main`.
- Wait for GitHub Actions checks and the required teammate review before merging.
- Do **not** fix unrelated bugs inside an assigned feature branch. Report them separately to avoid unrelated changes and merge conflicts.
- Update documentation and tests when the change requires it.

**Review note:** URL patterns, error JSON shape, additional branch prefixes, and DTO naming above are proposed conventions to confirm with team before treating them as final team standards.
