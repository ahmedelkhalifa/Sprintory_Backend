# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Sprintory backend — a project management platform (Jira/Asana-like) for SFWE343 Software Analysis and Design, Fall 2026-27. Spring Boot 4.1.1 / Spring Security 7, Java 17, Postgres, Maven.

**The frontend is a separate repository and a separate application.** Never add frontend assets here, and assume the browser talks to this API cross-origin via CORS.

`docs/requirements.md` holds all 41 requirements (R01–R41) with their sprint assignments; use those IDs in branches, tickets and PR descriptions. `docs/api-conventions.md` is the team's API and Git contract — read it before adding endpoints.

## Commands

```bash
./mvnw.cmd -B compile                 # compile
./mvnw.cmd -B test                    # run tests
./mvnw.cmd -B -Dtest=SomeTest test         # one test class
./mvnw.cmd -B -Dtest=SomeTest#someMethod test   # one test method
./mvnw.cmd -B clean verify            # what CI runs
./mvnw.cmd spring-boot:run            # run (needs the env vars below)
```

If the wrapper reports `JAVA_HOME is not defined correctly`, it is not inherited from the IDE — set it to a JDK 17 install for the command, e.g. `JAVA_HOME=/c/path/to/jdk-17 ./mvnw.cmd -B compile`.

There is no linter or formatter configured.

## Configuration and environment

`application.properties` resolves everything from environment variables. **`.env` is gitignored and is loaded only by the IntelliJ EnvFile plugin** (wired in `.idea/workspace.xml`, itself gitignored). There is no `spring.config.import`, so Maven runs, plain terminal runs and CI see none of it. `.env.example` lists the key names.

Two traps that cost real time:

- An **empty** variable defeats its default. `COOKIE_SECURE=` makes `${COOKIE_SECURE:false}` resolve to `""`, which then fails to convert to `boolean` at startup. Give every key in `.env` a value.
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` have `:dummy` defaults so the context still starts without them. Google sign-in then fails at Google rather than at boot.

## Known broken

`./mvnw.cmd verify` **fails**. `SprintoryBackendApplicationTests.contextLoads()` is a bare `@SpringBootTest` with no database and no secrets, so it dies with `'url' must start with "jdbc"`. Fixing it needs Testcontainers (`spring-boot-testcontainers`, `testcontainers:postgresql`, `junit-jupiter`), a shared `AbstractIntegrationTest` base with `@ServiceConnection`, and a test profile. Do not open a PR to `main` until that lands — CI runs only on PRs to `main` and pushes to `main`.

## Authentication architecture

This is the part that needs several files to understand.

**Stateless.** `SessionCreationPolicy.STATELESS`, no server-side session, nothing is persisted between requests except the refresh-token rows. "Logging someone in" *is* setting the cookie — there is no `saveContext` call to make.

**Two tokens, two different mechanisms:**

| | Access token | Refresh token |
|---|---|---|
| Form | HS256 JWT, `sub` = email | opaque 32 random bytes |
| Storage | nowhere | `refresh_tokens` row, SHA-256 hash only |
| Cookie | `access_token`, path `/` | `refresh_token`, path `/api/v1/auth` |
| Revocable | no, TTL only (15 min) | yes — the row *is* the validity |

Because refresh tokens are not JWTs, they cannot be replayed through `JwtAuthenticationFilter`, which is why no `type` claim is needed. `verifyAndConsume` is single-use; `revokeAll(user)` is what logout calls. Expired rows are deliberately *not* deleted on the failure path (the throw would roll the delete back) — a nightly `@Scheduled` job clears them, which is why the application class carries `@EnableScheduling`.

**Request path:** `JwtAuthenticationFilter` reads the `access_token` cookie, validates it, loads the user through `AppUserDetailsService`, checks `isEnabled()`, and populates the context. Any failure clears the context silently so `AppAuthenticationEntryPoint` produces the normal 401 JSON instead of a 500. Note that `isEnabled()` is only consulted when the token is presented — disabling a user does not invalidate a token already issued; revoking their refresh tokens bounds it to the access TTL.

**CSRF is disabled on purpose** (decided 21 Oct 2026). Consequence: `SameSite=Lax` on the auth cookies is the only CSRF protection left. **Do not move a cookie to `SameSite=None` without re-enabling CSRF**, and keep state-changing auth endpoints on POST.

**Google sign-in** (`security/oauth2/`):
- `CookieAuthorizationRequestRepository` exists because the only implementation Spring ships is session-backed and this app is stateless. Without it the callback fails with `authorization_request_not_found`. The cookie must be `SameSite=Lax` — `Strict` withholds it on exactly the cross-site navigation back from Google. It is not signed; someone able to set it could pin `state`.
- `AppOidcUserService extends OidcUserService`, **not** `DefaultOAuth2UserService` — the registration requests the `openid` scope, so the OIDC path runs and a plain `userService` would never be called. Wire it with `.oidcUserService(...)`.
- An email already registered with a password gets `USE_PASSWORD_LOGIN`; it never silently links accounts. Both handlers redirect to the separate frontend and never put tokens in the URL.
- Enabling `oauth2Login` also switches on Spring's generated `/login` page. Harmless, suppressible with `.loginPage(...)`.

## Error response contract

One JSON shape (`docs/api-conventions.md` §4), produced from three places that must stay in sync: `exceptionHandler/ExceptionHandler` for anything thrown inside MVC, `AppAuthenticationEntryPoint` for 401, `AppAccessDeniedHandler` for 403. `ErrorCode` is the stable machine-readable vocabulary — add to the enum rather than inventing strings.

The advice class is literally named `ExceptionHandler`, which shadows the annotation, so every handler method uses the fully-qualified `@org.springframework.web.bind.annotation.ExceptionHandler`. Keep that; do not rename the class to work around it.

Bad credentials and unknown email return an **identical** body on purpose — the response must never reveal whether an address is registered. Same principle in `docs/api-conventions.md` §5: a project that does not exist and one the user cannot see both return 404.

Services should let their exceptions propagate; the advice maps them.

## Conventions in this codebase

- Packages are grouped by feature, then by kind: `user/{models,repositories}`, `security/auth/{models,repositories,services}`, `security/{config,handlers,jwt,oauth2,userdetails,exceptions}`. Put a new class in the matching subfolder rather than at the package root.
- **Edit the existing class rather than adding a parallel one.** Do not create `GlobalExceptionHandler` next to `ExceptionHandler`, or a `*V2`.
- Entities extend `audit/BaseEntity` for the four audit columns. `AuditorProvider` stamps `system` for anonymous and unauthenticated writes.
- `@Value` on a **`final`** field combined with `@RequiredArgsConstructor` silently produces a constructor with no parameter annotation and fails at startup. Use a non-final field or write the constructor explicitly.
- **Jackson 3**: `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind`. Annotations are still `com.fasterxml.jackson.annotation`. `JacksonException` is unchecked, so no `throws` clause is needed. `jjwt-jackson` drags in Jackson 2 as well; the two coexist in different packages.
- `User.role` defaults to `WORKSPACE_ADMIN` while `AppOidcUserService` provisions `WORKSPACE_MEMBER` — the two sign-up paths disagree. **Unresolved; settle it before writing `AuthService`.**
- Emails are stored lower-cased. `AppOidcUserService` does this; the password registration path must too.
- `Role` is `{WORKSPACE_MEMBER, WORKSPACE_ADMIN}`, `Provider` is `{LOCAL, GOOGLE}`. Authorities are `ROLE_` + the enum name.
- The 8-character password minimum has **no backstop in the service layer** — it belongs on the request DTOs as `@Size(min = 8)`.