# R01 — Authentication HTTP contract (service handoff)

Implementation status: **Controller and DTO contract prepared. Business logic pending Ahmed.**

All routes are under `/api/v1/auth`. JSON is `camelCase`; request bodies use Jakarta Bean Validation.

| Method | Path | Request | Success | Notes |
| --- | --- | --- | --- | --- |
| POST | `/register` | `{firstName, lastName, email, password}` | `201` public user DTO | Account creation/verification pending. `password` ≥ 8. |
| POST | `/login` | `{email, password, rememberMe}` | `200` public user DTO + cookies | Keep credentials errors indistinguishable. `rememberMe` accepted; duration policy undecided. |
| POST | `/refresh` | HttpOnly `refresh_token` cookie | `200` public user DTO + new cookies | Must atomically consume and rotate token; missing cookie → `401`. |
| POST | `/logout` | Optional `refresh_token` cookie | `204` + cleared cookies | With no cookie, still clears local cookies. Token revocation strategy undecided. |
| GET | `/me` | HttpOnly `access_token` cookie | `200` public user DTO | Unauthenticated → `401`. Used by existing frontend. |
| POST | `/forgot-password` | `{email}` | `202` generic message | Identical success response for known/unknown emails. |
| POST | `/reset-password` | `{token, newPassword}` | `204` | `newPassword` ≥ 8; reset-token validation pending. |
| POST | `/verify-email` | `{token}` | `204` | Activation part of registration; currently a service skeleton. |

Success cookies use Ahmed's `AuthCookieFactory`: access cookie path `/`, refresh cookie path `/api/v1/auth`, both HttpOnly and SameSite=Lax. Raw tokens must never be sent as JSON fields. `AuthUserResponse` only exposes `id`, `firstName`, `lastName`, and `email`.

### Pending service methods

`security/auth/services/AuthService` is a temporary, injectable Spring bean. All unfinished methods explicitly return `501 NOT_IMPLEMENTED` and a structured `NOT_IMPLEMENTED` error. This keeps controller contracts testable and does **not** fake authentication or password resets.

Ahmed can replace each method body with the agreed implementation, using the existing `AuthenticationManager`, `JwtService`, `RefreshTokenService`, `PasswordEncoder`, `UserRepository`, and email delivery. `AuthSession` is an **internal result**, not a response DTO.

Before completing services, confirm:

- Password-registration default role (the current `User` default is admin; Google sign-up sets member). Do not grant admin to an arbitrary new registrant.
- `rememberMe` token expiry/cookie persistence semantics; the existing cookie factory uses fixed TTLs.
- Logout policy: revoke the presented session versus all user sessions.
- Email verification/reset token storage, expiration, single-use behavior, and email delivery ownership.
- Password reset must invalidate relevant existing sessions, with a clearly agreed scope.

The frontend already calls `POST /auth/login` and then `GET /auth/me`. The browser API client must send credentials (`withCredentials: true`) to receive and return HttpOnly cookies.

The existing `SecurityConfig` permits `/api/v1/auth/**`; the `/me` controller explicitly returns 401 without a principal. Ahmed should review security rules and CSRF protection before production.

### Tests

- `AuthControllerTest`: fast mocked-service tests of cookie delivery/clearing, generic recovery response and safe user DTO.
- `AuthRequestValidationTest`: field validation and minimum password length.
- Full Spring context test currently requires PostgreSQL configuration or test infrastructure; not solved by this patch.
