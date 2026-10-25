# Chattywap

Messaging backend built with Spring Boot, PostgreSQL and RabbitMQ. See [ARCHITECTURE.md](ARCHITECTURE.md) for the package structure and design rules, [OPERATIONS.md](OPERATIONS.md) for running it in production (health, metrics, logs, limits, failure behavior, runbook), and [loadtest/README.md](loadtest/README.md) for load tests and measured performance.

## Requirements

- JDK 21
- Docker with Compose v2 (for example, Docker Desktop). The integration tests also need Docker.
- Maven isn't required: use the included wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Configure the local environment

Copy the example file and replace the placeholder passwords:

```bash
cp .env.example .env
```

`.env` is ignored by Git. Never commit it. Use plain `KEY=value` lines, without quotes.

The same file is read by Docker Compose (to create the containers) and by the application, which imports `.env` from its working directory. Real environment variables take precedence over `.env`, so in other environments you set `POSTGRES_*`, `RABBITMQ_*` and `JWT_*` as environment variables instead.

| Variable | Used for | Default |
|---|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB` | Database location | `localhost`, `5432`, `chattywap` |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database credentials | none, required |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | Broker location | `localhost`, `5672` |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | Broker credentials | none, required |
| `RABBITMQ_MANAGEMENT_PORT` | Local RabbitMQ management UI (Compose only) | `15672` |
| `JWT_SECRET` | Key that signs access tokens: Base64, at least 32 bytes | none, required |
| `JWT_ISSUER`, `JWT_AUDIENCE` | `iss` and `aud` claims written into and required from access tokens | `chattywap`, `chattywap-api` |
| `JWT_ACCESS_TOKEN_TTL` | Access-token lifetime | `15m` |
| `REFRESH_TOKEN_TTL` | Refresh-token lifetime | `30d` |
| `WEBSOCKET_ALLOWED_ORIGINS` | Comma-separated origins of web pages that may open WebSocket connections, besides pages served by the application. Patterns such as `https://*.example.com` work. Clients that send no `Origin` header, such as Android apps, don't need it. | none |
| `MANAGEMENT_PORT`, `MANAGEMENT_ADDRESS` | Port and interface of the operational endpoints (health details, metrics). Keep them unreachable from outside. | `8081`, `127.0.0.1` |

Connection pool, timeouts, logging and limits have further settings with safe defaults: see [OPERATIONS.md](OPERATIONS.md#configuration).

Generate `JWT_SECRET` and paste the output into `.env` (Git Bash, macOS and Linux all have `openssl`):

```bash
openssl rand -base64 32
```

The placeholder in `.env.example` is deliberately invalid, so the application refuses to start until you replace it. Anyone who knows the secret can create valid access tokens for any user: keep it out of Git and use a different one in every environment. Changing it invalidates all access tokens already issued; refresh tokens keep working.

PostgreSQL and RabbitMQ apply the credentials only when their data volumes are first created. To change credentials later, recreate the volumes with `docker compose down -v`. This deletes all local data.

## Start PostgreSQL and RabbitMQ

```bash
docker compose up -d --wait
```

`--wait` returns once both services pass their health checks. Check their status with `docker compose ps`.

- PostgreSQL: `localhost:5432`
- RabbitMQ (AMQP): `localhost:5672`
- RabbitMQ management UI: <http://localhost:15672>. Log in with `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` from `.env`.

All ports are bound to `127.0.0.1`, so the services can't be reached from other machines. Data is kept in the `chattywap_postgres-data` and `chattywap_rabbitmq-data` volumes.

To stop the services:

- `docker compose stop` stops the containers and keeps the data.
- `docker compose down` removes the containers and keeps the data.
- `docker compose down -v` removes the containers **and deletes the data**.

## Run the application

```bash
./mvnw spring-boot:run
```

Or run `ChattywapApplication` from your IDE, with the working directory set to this project directory so that `.env` is found. The application listens on <http://localhost:8080>.

Every endpoint requires an access token except registration, login, refresh, logout and the probes `/livez` and `/readyz`. See [Authentication](#authentication). The WebSocket endpoint `ws://localhost:8080/ws` takes the access token in its STOMP `CONNECT` frame instead; see [Real-time messaging](#real-time-messaging-websocket-and-stomp).

Every response carries an `X-Request-Id` header. Quote it when reporting a problem: the server's log lines for that request, and for the asynchronous processing it caused, carry the same ID ([OPERATIONS.md](OPERATIONS.md#logs-and-tracing-an-operation)).

## Check health

The application port answers liveness and readiness probes with a status only:

```bash
curl http://localhost:8080/readyz
```

```json
{"status":"UP"}
```

Each dependency's status, with details, is on the management port (`MANAGEMENT_PORT`, 8081), which accepts connections from this machine only by default:

```bash
curl http://localhost:8081/actuator/health
```

```json
{"components":{"db":{"details":{"database":"PostgreSQL","validationQuery":"isValid()"},"status":"UP"},"diskSpace":{...},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"rabbit":{"details":{"version":"4.3.7"},"status":"UP"},"readinessState":{"status":"UP"},"ssl":{...}},"groups":["liveness","readiness"],"status":"UP"}
```

- `/livez` (liveness) and `/readyz` (readiness) depend on neither PostgreSQL nor RabbitMQ: both stay `UP` while a dependency is down, by design (see [OPERATIONS.md](OPERATIONS.md#health-and-readiness)). `/readyz` turns `OUT_OF_SERVICE` (503) while the instance shuts down.
- `db` is PostgreSQL, `rabbit` is RabbitMQ; `/actuator/health/db` shows one component. If a dependency is unavailable, its component and the overall status become `DOWN` and the endpoint answers 503. Without RabbitMQ, the application still stores and delivers messages; only publishing their events waits (see [Asynchronous events](#asynchronous-events-rabbitmq-and-the-transactional-outbox)).
- The management port also serves metrics: `/actuator/prometheus` for Prometheus and `/actuator/metrics/{name}` for one metric. On the application port, `/actuator` paths are refused.

## Authentication

Clients authenticate with two tokens:

- **Access token**: a signed JWT, valid for 15 minutes. Send it with every request to a protected endpoint as `Authorization: Bearer <accessToken>`. The server checks its signature, expiry, issuer (`iss`) and audience (`aud`) without a database lookup. Its subject (`sub`) is the user ID.
- **Refresh token**: a random 43-character string, valid for 30 days and usable **once**. Exchange it at `/api/v1/auth/refresh` for a new access token and a new refresh token. The database stores only its SHA-256 hash.

| Method and path | Access token | Body | Success |
|---|---|---|---|
| `POST /api/v1/auth/register` | no | `email`, `password`, `displayName` | `201` with the user's profile |
| `POST /api/v1/auth/login` | no | `email`, `password` | `200` with tokens |
| `POST /api/v1/auth/refresh` | no | `refreshToken` | `200` with new tokens |
| `POST /api/v1/auth/logout` | no | `refreshToken` | `204`, no body |
| `GET /api/v1/users/me` | yes | none | `200` with the user's profile |

The `/api/v1/auth/*` endpoints ignore the `Authorization` header. A client that attaches its expired access token to every request can therefore still refresh, log in and log out.

### Examples

Register. Emails are trimmed and lower-cased, so `Alice@Example.com` and `alice@example.com` are the same account. Passwords need 8 to 72 characters (at most 72 bytes in UTF-8, BCrypt's limit); there are no composition rules.

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' -d '{"email": "alice@example.com", "password": "correct horse battery staple", "displayName": "Alice"}'
```

```json
{"id":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","email":"alice@example.com","displayName":"Alice","createdAt":"2026-10-24T10:15:30.123456Z"}
```

Log in:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email": "alice@example.com", "password": "correct horse battery staple"}'
```

```json
{"accessToken":"eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...","tokenType":"Bearer","expiresIn":900,"refreshToken":"q3J9...43 characters","refreshTokenExpiresIn":2592000}
```

Call a protected endpoint with the access token:

```bash
curl http://localhost:8080/api/v1/users/me -H 'Authorization: Bearer <accessToken>'
```

When the access token expires, get a new pair. Store the new refresh token and discard the old one: it no longer works.

```bash
curl -X POST http://localhost:8080/api/v1/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken": "<refreshToken>"}'
```

Log out:

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/logout -H 'Content-Type: application/json' -d '{"refreshToken": "<refreshToken>"}'
```

### Errors

Every error uses the RFC 9457 problem-detail format (`Content-Type: application/problem+json`):

```json
{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Invalid email or password.","instance":"/api/v1/auth/login"}
```

| Situation | Status | Notes |
|---|---|---|
| Invalid request body | `400` | `errors` lists each invalid field and its message. Rejected values are never echoed. |
| Email already registered | `409` | |
| Wrong password or unknown email | `401` | Identical response in both cases, so login doesn't reveal which emails have accounts. |
| Missing, malformed, expired or wrongly signed access token | `401` | Also sets `WWW-Authenticate: Bearer ...` (RFC 6750). |
| Unknown, expired, already used or revoked refresh token | `401` | One message for all cases: the client should log in again. |
| Unexpected server error | `500` | Generic message. Details go to the server log only. |

### Sessions, rotation and reuse detection

Each login starts a session, stored as a *token family*. Every refresh token issued by refreshing within that session belongs to the same family.

- **Rotation**: a successful refresh revokes the presented refresh token and issues a new one, in one database transaction. A refresh token can therefore succeed only once.
- **Reuse detection**: presenting a refresh token that was already rotated means either the client sent it twice or someone copied it. The server can't tell which, so it revokes the whole family: the newest refresh token stops working too, and the user must log in again. Other sessions of the same user (other devices) aren't affected.
- **Concurrency**: refreshes of the same session are serialized with a row lock on the family. If a client sends two refreshes with the same token at the same time, one succeeds and the other counts as reuse, which ends the session. Clients must not refresh in parallel; share one in-flight refresh instead.
- **Expiry**: a refresh token expires 30 days after it was issued. Refreshing issues a new token with a fresh 30 days, so a session stays alive as long as it's used at least once a month. An expired token is rejected but doesn't revoke the session.

### Logout

`POST /api/v1/auth/logout` revokes the refresh token's whole session, so neither that token nor any token of the session can be used again. It doesn't need an access token: holding the refresh token is proof enough.

- Missing or blank `refreshToken`: `400`.
- Unknown, expired or already revoked refresh token: `204`, the same as a successful logout. Logging out twice is harmless, and the response doesn't reveal whether a token exists.

Logout ends **refresh** capability only. Access tokens are self-contained JWTs that the server never looks up, so an access token issued before logout keeps working until it expires (at most 15 minutes). Clients should discard both tokens on logout. If immediate revocation is ever required, the options are a shorter access-token lifetime or a server-side denylist, which this project doesn't have.

## Conversations

Every conversation endpoint requires an access token (`Authorization: Bearer <accessToken>`). The caller is always the user in the token. User IDs in the path or body only name *other* users (a recipient, members to add or remove), never who is making the request.

| Method and path | Body | Success |
|---|---|---|
| `POST /api/v1/conversations/direct` | `recipientId` | `201` with the new conversation, or `200` with the existing one |
| `POST /api/v1/conversations/group` | `name`, `memberIds` | `201` with the new conversation |
| `GET /api/v1/conversations?page=0&size=20` or `?size=20&cursor=...` | none | `200` with one page of the caller's conversations |
| `GET /api/v1/conversations/{conversationId}` | none | `200` with the conversation and its members |
| `PUT /api/v1/conversations/{conversationId}/name` | `name` | `200` with the renamed conversation |
| `DELETE /api/v1/conversations/{conversationId}` | none | `204`, no body |
| `POST /api/v1/conversations/{conversationId}/members` | `userIds` | `200` with the updated conversation |
| `DELETE /api/v1/conversations/{conversationId}/members/{userId}` | none | `204`, no body |
| `POST /api/v1/conversations/{conversationId}/members/{userId}/promote` | none | `200` with the updated conversation |
| `POST /api/v1/conversations/{conversationId}/members/{userId}/demote` | none | `200` with the updated conversation |
| `POST /api/v1/conversations/{conversationId}/leave` | none | `204`, no body |

### Conversation types and roles

- **Direct**: exactly two members and no name. Two users have at most one direct conversation: asking for it again, from either side, returns the existing one with `200`. Nobody can be added to, removed from or leave a direct conversation, and it can't be renamed or deleted. Both members have the role `MEMBER`.
- **Group**: a name that isn't blank and has at most 100 characters as sent (surrounding whitespace counts; characters outside the Basic Multilingual Plane, such as most emoji, count as two). It is stored without surrounding whitespace. At most **100 members**, the creator included. The creator is always a member, with the role `ADMIN`. Everyone added at creation or later joins as `MEMBER`; administrators can promote members to `ADMIN` and demote them again.

Members see each other's user ID, display name, role and join time. Email addresses are never included.

### Authorization rules

| Endpoint | Allowed for | Otherwise |
|---|---|---|
| Create direct | Any user. The recipient must exist and must not be the caller. | `400` |
| Create group | Any user. All `memberIds` must exist. | `400`; `409` if the group would have more than 100 members |
| List | Any user. The list contains only conversations the caller is a member of. | |
| Details | Members of the conversation | `404` |
| Rename, delete | Administrators of a group | `404` for non-members, `409` for direct conversations, `403` for members who aren't administrators |
| Add members | Administrators of a group | `404` for non-members, `409` for direct conversations, `403` for members who aren't administrators, `409` past the member limit |
| Remove member | Yourself: any member of a group (the same as leaving). Someone else: administrators of the group. | `404` for non-members, `409` for direct conversations, `403` for members who aren't administrators, `404` if the user to remove isn't a member |
| Promote, demote | Administrators of a group | `404` for non-members, `409` for direct conversations, `403` for members who aren't administrators, `404` if the user isn't a member, `409` for an invalid role change |
| Leave | Members of a group | `404` for non-members, `409` for direct conversations |

- **Non-members can't tell a conversation exists.** A conversation the caller doesn't belong to gets exactly the same `404` as an ID that doesn't exist, on every endpoint. Guessing IDs reveals nothing. A deleted group has no members, so it gets that `404` too.
- Checks run in this order: membership, then conversation type, then role, then the request's user IDs, then the state of the group (member limit, role changes). Only members ever get a `403` or `409`, and they already know the conversation.
- Membership is checked in the database on every request, so a removed member loses access immediately.

### Membership rules

- **Adding** users who are already members does nothing for them; the others are added. Repeating a request is therefore harmless and returns `200`. If any of the IDs doesn't exist, the request fails with `400` and nobody is added.
- **Member limit**: a group has at most 100 members, its creator included. Creating a group with the creator and more than 99 others, or adding users who would take a group past 100, fails with `409` and adds nobody: a request is never partly applied. Duplicate IDs and users who are already members don't count. Adding only existing members still succeeds in a full group, because it changes nothing. A place freed by someone leaving can be taken again.
- **Removing**: administrators can remove any other member, including other administrators.
- **Roles**: administrators can promote any member to `ADMIN` and demote any administrator, themselves included, to `MEMBER`. A group can have several administrators, and they all have the same rights. Promoting someone who is already an administrator, or demoting someone who isn't, is a `409`: the request asks for a change that doesn't apply.
- **A group always has an administrator.** The last administrator can't demote themselves (`409`); they promote someone else first. Administrators who demote or remove *others* stay administrators themselves, so they can't remove the last one.
- **Leaving** (or removing yourself) is open to every member. To choose a successor, the last administrator promotes them before leaving. Otherwise the longest-standing remaining member (earliest `joinedAt`, ties broken by user ID) becomes `ADMIN` automatically when the last administrator leaves.
- Adding, removing, leaving, promoting, demoting and renaming update the conversation's `updatedAt`, which moves it to the top of its members' lists. So does sending a message (see [Messages](#messages)). Requests that change nothing (adding existing members, renaming to the current name) don't, and neither do rejected ones.

### Group lifecycle

A group is **active** from its creation until it is **deleted**. Active groups always have at least one member and at least one administrator.

A group is deleted in one of two ways:

- An administrator deletes it: `DELETE /api/v1/conversations/{conversationId}`.
- Its last member leaves (or removes themselves).

Deletion is the same in both cases. In one transaction, all memberships are removed and the conversation is marked deleted (`deleted_at`). For its former members, the group is gone: it disappears from their lists, and every request about it gets `404`, the same as for a conversation that never existed. Nobody can rejoin, rename or restore it; there is no endpoint to undo a deletion.

The conversation row itself is kept, with its name, creator and timestamps. Nothing in the API returns it again. It is kept so that records that refer to the conversation don't have to be deleted with it, or left pointing at nothing. Messages are such records: a deleted group's messages stay in the database, and nobody can read them through the API. Memberships are removed because membership is what grants access, and because the schema already treats them as disposable: leaving has always deleted the membership row. No other data is removed.

Groups whose last member left before this behavior existed were stored without members. Migration `V3` marks them deleted, with the time the last member left.

### Examples

Start a direct conversation:

```bash
curl -i -X POST http://localhost:8080/api/v1/conversations/direct -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"recipientId": "9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c"}'
```

```json
{"id":"3c1e5a7b-2d4f-4a6c-8e0b-1f2a3b4c5d6e","type":"DIRECT","name":null,"createdBy":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","createdAt":"2026-10-24T10:20:00.123456Z","updatedAt":"2026-10-24T10:20:00.123456Z","members":[{"userId":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","displayName":"Alice","role":"MEMBER","joinedAt":"2026-10-24T10:20:00.123456Z"},{"userId":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","displayName":"Bob","role":"MEMBER","joinedAt":"2026-10-24T10:20:00.123456Z"}]}
```

Create a group. `memberIds` is required but may be empty; duplicate IDs and the caller's own ID are ignored. At most 100 IDs per request.

```bash
curl -i -X POST http://localhost:8080/api/v1/conversations/group -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"name": "Weekend Plans", "memberIds": ["9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c", "c4d5e6f7-8a9b-4c0d-9e1f-2a3b4c5d6e7f"]}'
```

```json
{"id":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","type":"GROUP","name":"Weekend Plans","createdBy":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","createdAt":"2026-10-24T10:25:00.654321Z","updatedAt":"2026-10-24T10:25:00.654321Z","members":[{"userId":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","displayName":"Alice","role":"ADMIN","joinedAt":"2026-10-24T10:25:00.654321Z"},{"userId":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","displayName":"Bob","role":"MEMBER","joinedAt":"2026-10-24T10:25:00.654321Z"},{"userId":"c4d5e6f7-8a9b-4c0d-9e1f-2a3b4c5d6e7f","displayName":"Carol","role":"MEMBER","joinedAt":"2026-10-24T10:25:00.654321Z"}]}
```

`members` is ordered by `joinedAt`, oldest first; members who joined at the same moment are ordered by user ID.

List your conversations. `role` is the caller's role; `otherUser` is the other participant of a direct conversation and `null` for groups.

```bash
curl 'http://localhost:8080/api/v1/conversations?page=0&size=20' -H 'Authorization: Bearer <accessToken>'
```

```json
{"content":[{"id":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","type":"GROUP","name":"Weekend Plans","role":"ADMIN","memberCount":3,"unreadCount":2,"otherUser":null,"createdAt":"2026-10-24T10:25:00.654321Z","updatedAt":"2026-10-24T10:25:00.654321Z"},{"id":"3c1e5a7b-2d4f-4a6c-8e0b-1f2a3b4c5d6e","type":"DIRECT","name":null,"role":"MEMBER","memberCount":2,"unreadCount":0,"otherUser":{"id":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","displayName":"Bob"},"createdAt":"2026-10-24T10:20:00.123456Z","updatedAt":"2026-10-24T10:20:00.123456Z"}],"page":{"size":20,"number":0,"totalElements":2,"totalPages":1},"nextCursor":null}
```

`nextCursor` is `null` here because the list has no more entries. See [Pagination](#pagination). `unreadCount` is how many messages others sent that the caller hasn't read, the same number `GET /api/v1/conversations/{conversationId}/unread-count` reports (see [Receipts and unread counts](#delivery-and-read-receipts-unread-counts)).

Get one conversation (same body as the creation responses):

```bash
curl http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d -H 'Authorization: Bearer <accessToken>'
```

Add members (administrators only). Returns the conversation with its updated members.

```bash
curl -X POST http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/members -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"userIds": ["d1e2f3a4-b5c6-4d7e-8f9a-0b1c2d3e4f5a"]}'
```

Remove a member (administrators, or anyone removing themselves):

```bash
curl -i -X DELETE http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/members/c4d5e6f7-8a9b-4c0d-9e1f-2a3b4c5d6e7f -H 'Authorization: Bearer <accessToken>'
```

Leave a group:

```bash
curl -i -X POST http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/leave -H 'Authorization: Bearer <accessToken>'
```

Promote a member to administrator, or demote an administrator (administrators only). Both return the conversation with its updated members:

```bash
curl -X POST http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/members/9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c/promote -H 'Authorization: Bearer <accessToken>'
```

```bash
curl -X POST http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/members/9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c/demote -H 'Authorization: Bearer <accessToken>'
```

Rename a group (administrators only). The name follows the same rules as at creation. Returns the conversation:

```bash
curl -X PUT http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/name -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"name": "Long Weekend Plans"}'
```

Delete a group (administrators only). See [Group lifecycle](#group-lifecycle):

```bash
curl -i -X DELETE http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d -H 'Authorization: Bearer <accessToken>'
```

### Pagination

`GET /api/v1/conversations` returns the caller's conversations in a fixed order: most recent activity first (`updatedAt` descending), then by `id` descending. `updatedAt` is the time of the conversation's latest activity: its newest message, or the last change to it (members, roles, name), whichever came last. The ID is unique, so conversations updated at the same moment always come in the same order, on every request and in both pagination modes. A `sort` parameter is ignored. Deleted groups are never listed or counted.

There are two ways to page through the list. Cursors are the better choice for scrolling; page numbers remain for existing clients and for jumping to a page.

**By cursor** (recommended):

1. Request the first page without a cursor: `?size=20`.
2. The response's `nextCursor` marks where that page ended. Request the next page with `?size=20&cursor=<nextCursor>`.
3. Repeat until `nextCursor` is `null`: there are no more entries.

- A cursor is an opaque string. Don't build or change one; pass back what the server returned. An invalid cursor is a `400`.
- With a cursor, `page` is ignored, and the response's `page` is `null`: page numbers and totals don't apply. `content` and `nextCursor` are as usual.
- An empty `cursor=` counts as no cursor, so it returns the first page.

**By page number**:

- `page`: zero-based page number, default `0`.
- The response has the page metadata in `page`: `size`, `number`, `totalElements`, `totalPages`. A page past the end has an empty `content`.
- `nextCursor` is set too, so a client can start by page number and continue by cursor.

**In both modes**, `size` is the number of entries per page: default `20`, at most `100` (larger values are reduced to 100). Invalid values fall back to the defaults.

**When conversations change while a client pages through the list:**

- **Page numbers** are offsets into the current order. When a conversation from a later page moves to the top, a new one appears, or one disappears, later pages shift by one: an entry can appear twice or be skipped.
- **Cursors** are a position in the order, not an offset, so these changes don't shift the following entries. No entry appears twice, and no entry is skipped because others moved. Conversations that are deleted or left before the client reaches them simply don't appear.
- A conversation that is created or changes during the pass sorts at the top of the list, before the cursor. It doesn't appear on the following pages. The client sees it when it reloads the first page.
- A cursor doesn't freeze the list in time. Each page shows the conversations as they are when that page is requested, so all pages together aren't a snapshot of a single moment.

### Errors

Errors use the same problem-detail format as the rest of the API (see [Errors](#errors)).

| Situation | Status | Notes |
|---|---|---|
| Invalid body: missing `recipientId`, blank or too long `name`, missing `memberIds`, empty `userIds`, more than 100 IDs, a `null` ID | `400` | `errors` lists each invalid field, e.g. `memberIds[1]` |
| Malformed UUID in the path or body | `400` | |
| Invalid `cursor` | `400` | |
| Direct conversation with yourself | `400` | |
| User IDs in the body that don't exist | `400` | `userIds` lists them. Nothing is created or added. |
| Missing, malformed, expired or wrongly signed access token | `401` | |
| Adding or removing others, changing roles, renaming or deleting without being an administrator | `403` | |
| Conversation doesn't exist, was deleted, or the caller isn't a member | `404` | Identical response in all cases |
| The user to remove, promote or demote isn't a member | `404` | |
| Adding, removing, leaving, changing roles, renaming or deleting in a direct conversation | `409` | |
| The group would have more than 100 members | `409` | `maxMembers` is the limit. Nobody is added. |
| Promoting an administrator, demoting a member, or demoting the last administrator | `409` | |

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"Some users do not exist.","instance":"/api/v1/conversations/group","userIds":["0e8f6a1c-55d4-4c3a-9b7e-2f1d0c9b8a7e"]}
```

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"A group can have at most 100 members.","instance":"/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/members","maxMembers":100}
```

Unknown users in the body are a `400`, not a `404`: the endpoint exists, the request refers to something that doesn't. The member limit is a `409` both when creating a group and when adding members: the request is well-formed, but the group it would produce isn't allowed.

### Design decisions

- **One direct conversation per pair, also under concurrency.** The two participants are stored on the conversation row (`direct_user1_id`, `direct_user2_id`), in a fixed order, under a unique constraint. Two simultaneous requests for the same pair can both find no conversation, but only one insert succeeds; the other request then reads the conversation the first one created and returns it with `200`.
- **Changes to a group are serialized per conversation.** Adding, removing, leaving, promoting, demoting, renaming and deleting lock the conversation row first (`SELECT ... FOR NO KEY UPDATE`), after checking that the caller is a member, so outsiders can't hold the lock. Sending a message takes the same lock, in groups and direct conversations (see [Messages](#messages)). Concurrent changes to the same group therefore run one after the other, and each one reads the members the previous one left. This is what keeps the rules true under concurrency:
  - The same user can't be added twice. The unique constraint on `(conversation_id, user_id)` backs this up in the database.
  - The member limit holds. The member count is read after the lock is taken, so two requests that would each fit on their own can't together exceed 100. A plain count followed by an insert, without the lock, could.
  - A group keeps an administrator. Two administrators demoting themselves at once, or the last administrator leaving while the member who would replace them leaves too, run one after the other, and the second one sees the result of the first.
  - Members added at the same moment as the group is deleted don't survive the deletion: either the addition runs first and the deletion removes them, or the deletion runs first and the addition gets `404`.
  - Creating a group needs no lock. Nobody else can see the group, or add members to it, before the creating transaction commits.
- **Transactions.** Creating a conversation and its memberships, each membership or role change with the `updatedAt` it touches, and a deletion (removing the memberships and marking the conversation deleted) each happen in one transaction: they are stored completely or not at all.
- **Delete behavior.** Groups are deleted by marking them (see [Group lifecycle](#group-lifecycle)), not by removing the row, so nothing that refers to a conversation has to be removed with it. Users who created or belong to a conversation can't be deleted (foreign keys with `on delete restrict`): there is no account deletion yet, and when there is, it has to decide what happens to the user's conversations explicitly. Memberships would be deleted together with their conversation row (`on delete cascade`), but nothing in the application deletes conversation rows.
- **Stable pagination.** The list order ends with the conversation ID, a unique tie-breaker, so the order is total and the same on every request. A cursor stores the last entry's `updatedAt` and `id`, and the next page selects the entries that sort after that pair (`updatedAt < t or (updatedAt = t and id < i)`, in PostgreSQL's UUID order). Cursors were added alongside page numbers rather than replacing them, so existing clients keep working: the response only gained the `nextCursor` field.
- **Indexes.** `conversation_members (user_id, conversation_id)` serves the conversation list and its count; the unique constraint on `(conversation_id, user_id)` serves every lookup by conversation (members, member counts, membership checks); the unique constraint on the direct pair serves the duplicate check. The query plans were checked against seeded data; no other index is needed for these queries. A page by cursor reads and sorts the same rows as a page by number (the caller's memberships, joined to their conversations), so it adds no index either.

### Known limitations

- Deleted groups, and their messages, stay in the database indefinitely. There is no retention period and no job that purges them.
- Groups that had more than 100 members before the limit existed keep them. Nobody can be added to them until they are below the limit.
- A conversation that changes while a client pages through the list by cursor moves before the cursor and isn't on the following pages (see [Pagination](#pagination)). Clients see it when they reload the first page.
- Page numbers can still shift when conversations change. Use cursors to avoid that.
- Conversation details and membership changes load all members of the conversation. That's cheap with at most 100 members.

## Messages

Members of a conversation can post messages to it and read its history. Both endpoints require an access token (`Authorization: Bearer <accessToken>`). The sender is always the user in the token: a sender ID in the body or the query string is ignored.

| Method and path | Body | Success |
|---|---|---|
| `POST /api/v1/conversations/{conversationId}/messages` | `content` | `201` with the stored message |
| `GET /api/v1/conversations/{conversationId}/messages?page=0&size=20` or `?size=20&cursor=...` | none | `200` with one page of the history, newest message first |

Clients connected over WebSocket also receive new messages as they are sent, and can send them that way too; see [Real-time messaging](#real-time-messaging-websocket-and-stomp). These endpoints stay the way to read the history. Delivery and read receipts and unread counts have endpoints of their own (see [Delivery and read receipts, unread counts](#delivery-and-read-receipts-unread-counts)). Editing, deleting, reactions, attachments and search are not part of this API yet.

### Content

- `content` is required and must contain at least one character that isn't whitespace.
- At most **4000 characters** as sent. Characters outside the Basic Multilingual Plane, such as most emoji, count as two.
- It is stored and returned exactly as sent, including surrounding whitespace and line breaks.
- It can't contain the NUL character (`\u0000`), which PostgreSQL can't store, or half of a UTF-16 surrogate pair (for example `\uD83D` without its second half), which isn't valid Unicode text.

### Who can post and read

| Endpoint | Allowed for | Otherwise |
|---|---|---|
| Send | Members of the conversation | `404` |
| History | Members of the conversation | `404` |

- The same rule as for the conversation endpoints: a conversation the caller doesn't belong to, an ID that doesn't exist and a deleted group all get exactly the same `404`. Knowing a conversation's ID, or holding a cursor from its history, grants nothing.
- Membership is checked in the database on every request. Members who leave or are removed lose access to the whole history at once, their own messages included. Their messages stay for the remaining members, shown with their name.
- Nobody can post to or read a deleted group. Its messages stay in the database with the conversation row (see [Group lifecycle](#group-lifecycle)).
- **A message is never stored after its sender stopped being a member.** Sending locks the conversation, like every change to a group, until the message is stored. Leaving, being removed and deleting the group wait for a message that is being sent; a message sent while one of them is in progress waits for it and then gets `404`.

### Examples

Send a message:

```bash
curl -i -X POST http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"content": "Who is bringing snacks?"}'
```

```json
{"id":"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","sequenceNumber":1,"sender":{"id":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","displayName":"Alice"},"content":"Who is bringing snacks?","createdAt":"2026-10-24T10:30:00.123456Z"}
```

Read the history:

```bash
curl 'http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages?size=20' -H 'Authorization: Bearer <accessToken>'
```

```json
{"content":[{"id":"1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","sequenceNumber":2,"sender":{"id":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","displayName":"Bob"},"content":"Me!","createdAt":"2026-10-24T10:31:12.654321Z"},{"id":"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","sequenceNumber":1,"sender":{"id":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","displayName":"Alice"},"content":"Who is bringing snacks?","createdAt":"2026-10-24T10:30:00.123456Z"}],"page":{"size":20,"number":0,"totalElements":2,"totalPages":1},"nextCursor":null}
```

- `sequenceNumber` is the message's position in its conversation: `1` for the first message, one more for each following one, without gaps, in the order the messages were stored. Receipts refer to messages by it.
- `sender` is the sender's user ID and display name. Email addresses are never included.
- `createdAt` is when the server stored the message: UTC, ISO 8601, in microseconds. A history entry is identical to the response that sending it returned.

### History order and pagination

The history is ordered **newest first**: `createdAt` descending, then `id` descending. Clients that show the newest message at the bottom reverse each page.

Pagination works exactly like the conversation list (see [Pagination](#pagination)), with the same parameters, defaults and response shape:

- **By cursor** (recommended): request the first page without a cursor, then pass each response's `nextCursor` as `cursor` to get the next, older messages, until `nextCursor` is `null`. With a cursor, `page` is ignored and the response's `page` is `null`. An empty `cursor=` is the first page; an invalid cursor is a `400`.
- **By page number**: `page` is zero-based, and the response's `page` holds `size`, `number`, `totalElements` and `totalPages`.
- `size`: default `20`, at most `100` (larger values are reduced to 100). Invalid values fall back to the defaults. A `sort` parameter is ignored.

**When messages arrive while a client pages back through the history:**

- A cursor is a position in the history, so new messages don't shift the following pages: no message appears twice, and none is skipped because others arrived. New messages sort before the cursor; the client gets them by loading the first page again.
- Messages to one conversation are stored one after the other, and each takes its time once the previous one has committed. On a single server, the history order is therefore the order in which messages became visible. With several application instances whose clocks differ (or a server clock that is set back), it isn't guaranteed: a message that commits later can sort below one that was already visible, possibly in a page the client has already loaded. Reloading the first page shows it; a client that reloads only until it reaches a message it already has can miss it.
- Page numbers count from the newest message. Every new message shifts all pages by one, so paging back by number shows a message again for each message that arrived. Use cursors to scroll back.

Messages stored in the same microsecond are ordered by ID. That order is the same on every request and in both modes, but among those messages it isn't necessarily the order in which they were sent.

### Errors

Errors use the same problem-detail format as the rest of the API (see [Errors](#errors)).

| Situation | Status | Notes |
|---|---|---|
| Missing, blank or too long `content`, or `content` with a NUL character or an unpaired surrogate | `400` | `errors` lists `content`. Rejected values are never echoed. |
| Malformed JSON, or a malformed conversation ID | `400` | |
| Invalid `cursor` | `400` | |
| Missing, malformed, expired or wrongly signed access token | `401` | |
| Conversation doesn't exist, was deleted, or the caller isn't (or is no longer) a member | `404` | Identical response in all cases |
| Request body larger than 64 KB | `413` | `maxBytes` states the limit. Nothing is read beyond it. |
| So many messages are being sent to the conversation at once that this one would wait more than 5 seconds | `503` | `Too many messages are being sent to this conversation. Try again later.` Nothing is stored. |
| The database is unavailable or doesn't answer | `503` | `The service is temporarily unavailable. Try again later.` Nothing is stored; sending again later is safe. |
| Unexpected server error, including a failure to store the message | `500` | Generic message. Details go to the server log only. Nothing is stored. |

### Design decisions

- **Order by time, then ID.** `createdAt` is the server's clock, in microseconds, and the ID breaks ties, so the order is total and the same on every request. It is a timestamp, not a sequence number. Because sends to one conversation are serialized and read the clock after taking the conversation lock, a message that commits later gets a later time on one server: the history order is the commit order. With several application instances, clock differences between them can break that, and messages stored in the same microsecond are ordered by their random IDs. The history keeps this order (see [Order and delivery](#order-and-delivery) and `ARCHITECTURE.md`, unresolved decision 9).
- **Sequence numbers.** Every message also gets the next number of its conversation, read under the same conversation lock, so the numbers follow the order in which messages were stored on any number of instances, whatever their clocks say. A send that fails uses up no number, and messages are never deleted, so a conversation's numbers have no gaps. Receipts are positions in this order. A unique constraint on `(conversation_id, sequence_number)` catches a number given twice; its index, which includes the sender, also answers "the highest number" and unread counts. Existing messages were numbered in history order by migration V6.
- **Membership checked under the conversation lock.** Sending takes the conversation lock that every group change takes, after checking that the sender is a member, checks the membership again under the lock, stores the message and moves the conversation's `updatedAt` to the message's time, all in one transaction. Leaving, removal and group deletion wait for a message that is being sent, and a message sent while one of them is in progress waits for it and is then rejected. Taking the conversation lock first, like every group change, also means sends and group changes can't deadlock. Reading the history takes no lock: a read at the same moment as a removal sees the membership from before or after it.
- **Conversation activity.** Sending a message moves the conversation's `updatedAt`, its activity time, to the message's `createdAt`, so the conversation moves to the top of its members' lists. The update is part of the message's transaction: if storing the message fails, `updatedAt` keeps its old value. `updatedAt` is reused rather than adding a separate field, so the conversation list's order, cursors and JSON stay as they were; `updatedAt` simply also counts messages as activity now. It has no index, so updating it on every message touches no index.
- **Stored before reporting success.** The transaction commits before the `201` is sent. If it fails, the client gets a `500` and the message isn't stored. Only after the commit is the message delivered to connected members (see [Real-time messaging](#real-time-messaging-websocket-and-stomp)), so a message that couldn't be stored is never delivered.
- **Senders without N+1 queries.** Messages refer to their conversation and sender by ID (no JPA association across features). A page loads all its senders in one query, reading only their ID and display name. Sending takes ten statements (membership check before the send gate, membership check before the lock, conversation lock, membership check under the lock, the members who will receive the message, the conversation's highest sequence number, sender, insert, its outbox event, activity time) and a page at most four (membership, messages, total, senders), however many senders or members there are.
- **Totals without counting.** A page by number reports `totalElements`, the conversation's number of messages. It is read as the highest sequence number, one index entry, instead of counting every message: numbers start at 1 and have no gaps, messages are never deleted, and a number is assigned only after the message before it committed, so the two are always equal. Counting took 23 ms per request for a conversation of 200,000 messages; the highest number takes 0.02 ms at any size.
- **Busy conversations don't hold up others.** On each instance, sends to the same conversation wait for each other before they take a database connection (`ARCHITECTURE.md`, decision 23); only the two in front hold connections, one storing its message and one waiting for the conversation lock. Without that, a conversation receiving many messages at once tied up every connection, and every other request on the instance waited ([loadtest/README.md](loadtest/README.md)). A send that would wait more than 5 seconds gets `503` instead.
- **Pagination in the database.** Pages use `FETCH FIRST`, and `OFFSET` for page numbers. A page by cursor selects the messages that sort after the cursor with a row comparison, `(created_at, id) < (t, i)`, which PostgreSQL uses as the start of the index scan. The equivalent `created_at < t or (created_at = t and id < i)` filters every newer message instead.
- **Indexes.** One new index, `messages (conversation_id, created_at, id)`. PostgreSQL reads it backwards for the newest-first order, so pages need no sort. Checked against 1,000,000 messages, 200,000 of them in one conversation: the first page and a cursor page 100,000 messages deep each read a handful of index pages in under 0.1 ms. Membership checks use the existing membership indexes. There is no index on `sender_id`: nothing looks messages up by sender, and users can't be deleted, so it would only slow down inserts.
- **Database constraints.** Foreign keys to the conversation and the sender (`on delete restrict`: a conversation or a user with messages can't be deleted; nothing in the application deletes either); `content` is `varchar(4000)` and can't be empty or only ASCII whitespace. The API's checks are stricter, so the database constraints only catch bugs.
- **Shared pagination code.** The cursor encoding and the page metadata moved to `common`, now that two lists use them. The cursor format didn't change, so cursors issued before keep working.

### Known limitations

- Page numbers get slower the further back they go: a page reads and skips every newer message. With 2.1 million messages and a cold cache, an offset of 100,000 in a conversation of 200,000 took 130 ms (PostgreSQL scanned and sorted the table instead of using the index). Pages by cursor take under 1 ms at any depth: scroll back with `nextCursor`.
- Messages to one conversation are stored one after the other, under the conversation lock, and wait for group changes in progress. Each holds the lock until it commits (a few milliseconds), which limits one conversation to roughly 55 messages per second per instance in the load test (80 before the send gate, which keeps a busy conversation from occupying every database connection); different conversations don't affect each other.
- With several application instances, the history order isn't guaranteed to be the order in which messages became visible (see [History order and pagination](#history-order-and-pagination)).
- `updatedAt` now also moves when a message is sent. A client that used it to tell whether a conversation's members or name changed will see it move more often.

## Real-time messaging (WebSocket and STOMP)

Clients that keep a WebSocket connection open receive a conversation's new messages as they are sent, and can send messages over the same connection. The connection speaks [STOMP 1.2](https://stomp.github.io/stomp-specification-1.2.html), which most STOMP client libraries for Android support.

The WebSocket only delivers messages sent while a client is connected. The history endpoint stays the record of what was sent: a client that was offline catches up from it (see [Missed messages](#missed-messages-and-reconnecting)).

| What | How |
|---|---|
| Endpoint | `ws://localhost:8080/ws` (plain WebSocket, no SockJS) |
| Authenticate | `Authorization: Bearer <accessToken>` header in the STOMP `CONNECT` frame |
| Send a message | `SEND` to `/app/conversations/{conversationId}/messages`, body `{"content": "..."}` |
| Receive a conversation's messages | `SUBSCRIBE` to `/topic/conversations/{conversationId}/messages` |
| Receive changes of a conversation's delivery and read receipts | `SUBSCRIBE` to `/topic/conversations/{conversationId}/receipts` (see [Receipts](#delivery-and-read-receipts-unread-counts)) |
| Receive the errors of your own messages | `SUBSCRIBE` to `/user/queue/errors` |

In the frame examples below, `^@` stands for the NUL byte that ends every STOMP frame.

### Connecting

1. Open a WebSocket to `/ws`. The handshake needs no access token, and an `Authorization` header on it is ignored.
2. Send `CONNECT` with the access token:

   ```
   CONNECT
   accept-version:1.2
   host:localhost
   Authorization:Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...

   ^@
   ```

3. The server answers `CONNECTED`, with the user ID from the token as `user-name`:

   ```
   CONNECTED
   version:1.2
   heart-beat:10000,10000
   user-name:5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11

   ^@
   ```

- The token is the same access token as for REST, checked the same way: signature, expiry, issuer and audience. The header name is case-sensitive, as all STOMP headers are; the `Bearer ` prefix isn't.
- The connection's user is the token's user, for every later frame. User IDs or logins in headers (`login`, `user-name`, ...) or in message bodies are never used.
- A missing, malformed, expired or wrongly signed token is answered with an `ERROR` frame (`401`), and the server closes the connection. So is every frame sent before `CONNECT`, with `400`.
- **Token expiry**: every later `SUBSCRIBE` and `SEND` checks that the connection's token is still valid, like every REST request does. After it has expired, the next `SUBSCRIBE` or `SEND` is rejected with `401` and the connection is closed, and the frame has no effect. A connection that only receives isn't interrupted. Clients should refresh their token before it expires (`expiresIn` in the login and refresh responses) and reconnect with the new one; messages sent in between are in the history.
- Web pages may only connect from the application's own origin or an origin in `WEBSOCKET_ALLOWED_ORIGINS`; others get `403` on the handshake. Clients that send no `Origin` header, such as Android apps, aren't affected.
- **Heartbeats**: the server offers STOMP heartbeats every 10 seconds in both directions (`heart-beat:10000,10000` in `CONNECTED`); a client gets them only if its `CONNECT` asks for them, with the `heart-beat` header as in STOMP 1.2. A client that offers to send heartbeats (a first value above 0) must then send a frame or a heartbeat (a single newline) at least every agreed interval: after three intervals without anything, the server closes the connection with an `ERROR` frame. A client that asks for none (`heart-beat:0,0`, or no header) is never closed for being silent; it can notice a dead connection with WebSocket pings (for example OkHttp's `pingInterval`), which the server answers.

### Subscribing

Subscribe to each conversation the client shows, with any subscription `id`:

```
SUBSCRIBE
id:sub-0
destination:/topic/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages

^@
```

Every message stored in the conversation then arrives as a `MESSAGE` frame. Its body is the stored message, the same JSON as the REST response and the history entry, with the server's `id` and `createdAt`:

```
MESSAGE
destination:/topic/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages
content-type:application/json
subscription:sub-0
message-id:a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d-0
content-length:275

{"id":"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","sequenceNumber":1,"sender":{"id":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","displayName":"Alice"},"content":"Who is bringing snacks?","createdAt":"2026-10-24T10:30:00.123456Z"}^@
```

Messages sent through REST and through STOMP arrive the same way, once each. The sender receives its own messages too, if it is subscribed.

- **Only members can subscribe**, decided in the database at the moment of subscribing. A conversation the user isn't a member of, an unknown ID and a deleted group all get the same `ERROR` frame (`404`, `Conversation not found.`), and the server closes the connection. STOMP has no way to reject one subscription and keep the connection. Clients should subscribe only to conversations from their current list; to tell which subscription failed, add a `receipt` header to each `SUBSCRIBE`, which the `ERROR` frame repeats as `receipt-id`.
- **Losing membership ends delivery.** A member who leaves or is removed keeps the subscription, but receives none of the messages sent after that, and subscribing again is rejected. Nobody is told; the client sees the change in the conversation list.
- **Only these destinations** can be subscribed to, and a conversation's receipts topic under the same rules (see [Real-time receipt changes](#real-time-receipt-changes)). Patterns (`/topic/conversations/*/messages`), other users' queues and conversation IDs not written exactly as the API returns them (lower case, with hyphens) are rejected with `404` (`Unknown destination.`), and the connection is closed.
- New conversations aren't announced over the WebSocket. A client subscribes to a conversation after creating it, or after it appears in the conversation list.

### Sending

```
SEND
destination:/app/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages
content-type:application/json

{"content": "Who is bringing snacks?"}^@
```

- The body and its rules are those of `POST /api/v1/conversations/{conversationId}/messages`: see [Content](#content). The sender is always the connection's user, and only members can send.
- There is no direct reply. Once the message is stored, it is delivered to the conversation's subscribers, the sender included: that `MESSAGE` frame is the confirmation, with the stored `id` and `createdAt`. The server doesn't send `RECEIPT` frames for `SEND`.
- A message that isn't stored is answered on `/user/queue/errors`, to the sending connection only, as a problem detail with the same status and `detail` as the REST endpoint would give, and the destination as `instance`. The connection stays open. Nothing is stored or delivered.

  ```
  MESSAGE
  destination:/user/queue/errors
  content-type:application/json
  subscription:errors
  message-id:a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d-1
  content-length:168

  {"type":"about:blank","title":"Not Found","status":404,"detail":"Conversation not found.","instance":"/app/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages"}^@
  ```

- Only `/app/conversations/{conversationId}/messages` accepts `SEND`. Sending anywhere else, in particular straight to a `/topic/...` destination, which would reach subscribers without being stored, is rejected with an `ERROR` frame (`404`, `Unknown destination.`), and the connection is closed.

### Errors

Errors are problem details, like REST errors. Problems with a frame itself end the connection with an `ERROR` frame; problems with a message sent on a valid connection are answered on `/user/queue/errors`, and the connection stays open.

| Situation | Answer | Status, `detail` |
|---|---|---|
| `CONNECT` without a valid access token; `SUBSCRIBE` or `SEND` after the token expired | `ERROR`, connection closed | `401`, `Missing, invalid or expired access token.` |
| A frame before `CONNECT`, a second `CONNECT`, a malformed frame | `ERROR`, connection closed | `400`, `Invalid STOMP frame.` |
| `ACK`, `NACK`, `BEGIN`, `COMMIT`, `ABORT` | `ERROR`, connection closed | `400`, `Unsupported STOMP frame.` |
| `SUBSCRIBE` or `SEND` to any other destination | `ERROR`, connection closed | `404`, `Unknown destination.` |
| `SUBSCRIBE` to a conversation the user isn't a member of, doesn't exist or was deleted | `ERROR`, connection closed | `404`, `Conversation not found.` |
| `SEND` with blank, too long or otherwise invalid `content` | `/user/queue/errors` | `400`, `Request validation failed.`, with `errors` as in REST |
| `SEND` with an empty body, malformed JSON or JSON that isn't an object | `/user/queue/errors` | `400`, `Failed to read message payload.` |
| `SEND` to a conversation the user isn't (or is no longer) a member of, doesn't exist or was deleted | `/user/queue/errors` | `404`, `Conversation not found.` |
| `SEND` while too many messages are being sent to the conversation (see [Messages](#errors-2)) | `/user/queue/errors` | `503`, `Too many messages are being sent to this conversation. Try again later.` |
| The database is unavailable or doesn't answer | `/user/queue/errors` (`ERROR` and closed, if it happened while checking a frame) | `503`, `The service is temporarily unavailable. Try again later.` |
| No frame or heartbeat for three intervals from a client that offered heartbeats | `ERROR`, connection closed | `Session closed.` |
| A frame larger than 64 KB | Connection closed | |
| The message couldn't be stored, or another unexpected server error | `/user/queue/errors` (`ERROR` and closed, if it happened while checking a frame) | `500`, `An unexpected error occurred.` Details go to the server log only. |

An `ERROR` frame carries the `detail` as its `message` header and the problem detail as its body. Rejected values, exception messages and other internals are never included.

```
ERROR
message:Conversation not found.
content-type:application/problem+json
content-length:170

{"type":"about:blank","title":"Not Found","status":404,"detail":"Conversation not found.","instance":"/topic/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages"}^@
```

### Missed messages and reconnecting

The server keeps nothing for clients that aren't connected. Messages sent while a client was offline are only in the history, which can be read whether or not the client has a WebSocket connection. After reconnecting:

1. Connect with a valid access token and subscribe to the conversations again, **before** reading the history. Messages stored from then on are delivered live.
2. For each conversation, load the first page of the history (`GET /api/v1/conversations/{conversationId}/messages?size=50`) and take the messages newer than the last one the client has: stop at that message's `id`. If the page ends before it, follow `nextCursor`.
3. Merge them with the messages that arrived live meanwhile, dropping duplicates by `id`: a message stored between steps 1 and 2 arrives both ways.

The conversation list (`GET /api/v1/conversations`) shows which conversations had activity: they are ordered by `updatedAt`, which moves with every message.

Stopping at a known `id` relies on history order matching the order in which messages became visible. That holds on one application instance, and on several while their clocks agree; see [History order and pagination](#history-order-and-pagination) for when it doesn't. Sequence numbers show a client whether it has every message up to one: a gap means a message it missed, for example one that couldn't be passed on to its instance while RabbitMQ was down.

### Order and delivery

- **Stored, then delivered.** A message is delivered only after the transaction that stores it has committed. A message that couldn't be stored is never delivered, and the `MESSAGE` frame always carries the stored `id` and `createdAt`.
- **Once, at most.** Each stored message is delivered at most once to each subscribed connection, whether it was sent through REST or STOMP. Delivery isn't retried: a connection that drops at that moment misses it, and catches up from the history.
- **One connection's messages keep their order.** Frames from one connection are processed one after the other, so messages a client sends in a row are stored, and delivered, in the order it sent them, also to clients connected to other instances.
- **Across senders, use the history order.** Messages of one conversation are stored one after the other, and on one instance the history order (`createdAt`, then `id`) is the order in which they were stored. Delivery happens right after each commit (a RabbitMQ hop later for clients on other instances), so messages from different senders sent at the same moment can arrive in a different order than the history shows. Clients place each message by `createdAt` and `id`, not by arrival. `createdAt` is a timestamp, not a sequence number, so the caveats in [History order and pagination](#history-order-and-pagination) apply.
- **No duplicate protection.** A client that sends again because it didn't see its message (for example after the connection dropped) can store it twice. Check the history first.
- **Any number of application instances.** Clients can connect to any instance. Each instance delivers the messages stored through it to its own clients directly, and passes them through RabbitMQ to the other instances, which deliver them to theirs: a client receives the messages of its conversations whichever instances it and the sender are connected to, under the rules above, and never twice. While RabbitMQ is down, a client receives only the messages stored through its own instance; the others are in the history, and aren't delivered later (see [OPERATIONS.md](OPERATIONS.md#running-several-instances)).

### Design decisions

- **Authentication in the CONNECT frame, not the handshake.** STOMP clients can set `CONNECT` headers everywhere, also where they can't set HTTP headers on the WebSocket handshake (browsers). The handshake (`GET /ws`) is therefore public; every other HTTP rule is unchanged, so all other paths, including other methods on `/ws`, still need an access token. A connection can do nothing before its `CONNECT` is accepted, and Spring closes connections that send no frame within a minute. The token is validated by Spring Security's JWT authentication provider with the application's `JwtDecoder`, the same as for REST, and removed from the `CONNECT` frame afterwards so it isn't passed on.
- **Deny by default.** An interceptor (`realtime.StompSecurityInterceptor`) checks every frame before any handler or the broker sees it: which commands, which destinations, whose token. The decisions themselves stay with the services: `ConversationService.requireMembership` decides subscriptions, and `MessageService` decides sends, exactly as for REST.
- **Recipients are fixed when the message is stored.** Sending reads the conversation's members under the conversation lock that every send and membership change takes, and publishes them with the message. Delivery goes only to their connections: a subscription that outlived its user's membership receives nothing. A member removed after the message was stored still receives it, as they could still read it in the history at that moment. Reading the members adds one statement to every send (eight in all, with the outbox event).
- **Delivered after the commit, from one place.** `MessageService` publishes a `MessageSentEvent` for every stored message. `realtime.MessageBroadcaster` delivers it once the transaction has committed (`@TransactionalEventListener`), so REST and STOMP sends share one path and nothing is delivered twice or before it exists. If delivery fails, the message stays stored and the send still succeeds; the failure is logged. WebSocket delivery deliberately doesn't go through the transactional outbox (see [Asynchronous events](#asynchronous-events-rabbitmq-and-the-transactional-outbox)): to the instance's own clients it is immediate and works while RabbitMQ is down, and a message whose delivery is lost (for example because the application stops right after the commit) is only in the history. Its `MessageCreated` event, which is durable, isn't delivered over WebSocket again.
- **Across instances: a broker per instance, connected through RabbitMQ.** Each instance keeps Spring's in-memory broker, its connections and subscriptions. After the commit, `messaging.RealtimeFanoutPublisher` also publishes the message, with its recipients, to the fanout exchange `chattywap.realtime`, from one thread and channel per instance, so it never holds up the send and keeps the order; every instance has its own queue there and delivers what the others published to its own recipients, through the same recipient check, skipping its own. Like the delivery itself, this is best effort: unconfirmed, transient, never retried. The alternative, RabbitMQ's STOMP plugin as the broker for every instance (a broker relay), would make RabbitMQ hold a connection per client and a queue per subscription, and stop all WebSocket messaging while it is down (`ARCHITECTURE.md`, decision 26).
- **Order kept per connection.** Frames from one connection are processed in order, and messages to one connection are sent in the order they were published (Spring's `preserveReceiveOrder` and `preservePublishOrder`). Without that, Spring processes a connection's frames in parallel, and two messages sent in a row could be stored in the opposite order. The cost: one connection's frames wait for each other. Because Spring's ordering only logs exceptions thrown while checking a frame, the interceptor sends its `ERROR` frames itself.
- **A connection can't send faster than its frames are handled.** Frames are handled on the server thread that read them from the connection, and the next frame is read only after that: a client sending faster is slowed down by TCP, as a client sending REST requests is. Handed to a separate thread pool instead, as Spring does by default, a connection's frames queued in server memory without a limit (20,000 messages read in a second, then minutes to work through; `ARCHITECTURE.md`, decision 24).
- **Errors as problem details.** `ERROR` frames and error-queue messages use the same format, statuses and messages as REST errors, so clients handle both alike.

### Known limitations

- Between instances, live delivery needs RabbitMQ: while it is down, clients receive only the messages stored through their own instance, and the others aren't delivered later (see [Order and delivery](#order-and-delivery)).
- Every instance receives every message, to deliver it to whichever of its clients are recipients: fine for a handful of instances, wasteful for many.
- An expired token is noticed at the connection's next `SUBSCRIBE` or `SEND`, not when it expires: a connection that only receives keeps receiving, also after its user logged out, until it disconnects.
- No confirmation per sent message other than its delivery, no `RECEIPT` for `SEND`, and no idempotency key against duplicates.
- A rejected subscription closes the whole connection.
- New conversations, membership changes and deleted groups aren't announced over the WebSocket.
- Delivery order across senders can differ from the history order (see [Order and delivery](#order-and-delivery)).
- No rate limit per connection or user besides the backpressure above: a member can store messages as fast as the server handles them.

## Delivery and read receipts, unread counts

Members tell the server which messages their client has received and which they have read. Other members see it, live over the WebSocket and through REST, and every member can ask how many messages they haven't read. All endpoints require an access token; the member who acknowledges is always the user in the token.

### What sent, delivered and read mean

| Status of a message, for one member | Meaning | Recorded by |
|---|---|---|
| **Sent** | The message is stored in PostgreSQL: `POST .../messages` answered `201`, or it arrived over the WebSocket, which only happens after it was stored. | The server, when it stores the message |
| **Delivered** | The member's client acknowledged that it received the message. | `POST .../receipts/delivered` from that member |
| **Read** | The member reported reading the message. | `POST .../receipts/read` from that member |

- Nothing else makes a message delivered: not a WebSocket broadcast, an open connection, a subscription, or the message's event reaching RabbitMQ. A client that received a message but never acknowledged it hasn't had it delivered, as far as the server knows.
- Delivered never counts as read. Read does count as delivered: a member can only read what their client received, so reporting a read also acknowledges delivery up to the same message.
- **Per member.** In a group, each member has their own positions. One member's acknowledgement says nothing about the others: "read by Bob" isn't "read by everyone".
- The sender's own messages are never unread for the sender.

### Positions

Each member has two positions in a conversation: `lastDeliveredSequenceNumber` and `lastReadSequenceNumber`. They are message sequence numbers (`sequenceNumber` in every message), `0` if the member hasn't acknowledged anything. An acknowledgement names one message and covers it **and every earlier message** of the conversation, the member's own included, the way a read marker does.

So for a message with sequence number `n` and a member `m`:

- read by `m` if `n <= m.lastReadSequenceNumber`,
- otherwise delivered to `m` if `n <= m.lastDeliveredSequenceNumber`,
- otherwise only sent.

Positions only move forward. Acknowledging the same message again, or an earlier one, changes nothing: the answer is `200` with the position as it is, and nobody is notified. Retries, duplicates and requests that arrive out of order are therefore harmless, also when they run at the same moment.

### Endpoints

| Method and path | Body | Success |
|---|---|---|
| `POST /api/v1/conversations/{conversationId}/receipts/delivered` | `messageId` | `200` with the caller's position |
| `POST /api/v1/conversations/{conversationId}/receipts/read` | `messageId` | `200` with the caller's position |
| `GET /api/v1/conversations/{conversationId}/receipts` | none | `200` with every member's position |
| `GET /api/v1/conversations/{conversationId}/unread-count` | none | `200` with the caller's unread count |

The conversation list (`GET /api/v1/conversations`) also has each entry's `unreadCount`.

Acknowledge receiving everything up to a message:

```bash
curl -X POST http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/receipts/delivered -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"messageId": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"}'
```

```json
{"conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","userId":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","lastDeliveredSequenceNumber":2,"deliveredAt":"2026-10-24T10:31:13.000123Z","lastReadSequenceNumber":0,"readAt":null}
```

Report reading up to a message (`POST .../receipts/read`, same body): the answer has `lastReadSequenceNumber` `2` and `lastDeliveredSequenceNumber` at least `2`.

Every member's positions:

```bash
curl http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/receipts -H 'Authorization: Bearer <accessToken>'
```

```json
{"members":[{"conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","userId":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","lastDeliveredSequenceNumber":2,"deliveredAt":"2026-10-24T10:31:20.000456Z","lastReadSequenceNumber":2,"readAt":"2026-10-24T10:31:20.000456Z"},{"conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","userId":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","lastDeliveredSequenceNumber":0,"deliveredAt":null,"lastReadSequenceNumber":0,"readAt":null}]}
```

The caller's unread count:

```bash
curl http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/unread-count -H 'Authorization: Bearer <accessToken>'
```

```json
{"conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","unreadCount":3}
```

| Field | Meaning |
|---|---|
| `lastDeliveredSequenceNumber`, `lastReadSequenceNumber` | The last message the member acknowledged as received or read, with every message before it; `0` for none. The read position is never ahead of the delivered one. |
| `deliveredAt`, `readAt` | When that position last moved forward, in microseconds; `null` if it never did. Never earlier than the time before, and `readAt` never earlier than the delivered time when it moved (an acknowledgement that waited for a concurrent one gets at least that one's time). |
| `members` | Every current member, the caller included, ordered by user ID; members who haven't acknowledged anything have `0` and `null` |
| `unreadCount` | Messages others sent after the caller's read position (see [Unread counts](#unread-counts)) |

### When clients acknowledge

- **Delivered**: after receiving messages, live or from the history, acknowledge the newest message up to which the client has **every** message. Sequence numbers start at `1` and have no gaps, so the client can tell: holding `1` to `7` and `9`, it acknowledges `7` and loads `8` from the history. One acknowledgement covers all earlier messages, so acknowledge once per burst (for example at most once a second), not once per message.
- **Read**: when the user has seen messages, acknowledge the newest one on screen.
- On a network error, send the same acknowledgement again: it's safe.
- Only acknowledge what the client really has or the user really saw; the server can't check it.

### Who can do what

| Endpoint | Allowed for | Otherwise |
|---|---|---|
| Acknowledge delivered or read | Members of the conversation, for themselves | `404` (`Conversation not found.`) for non-members, former members, deleted groups and unknown conversations; `404` (`Message not found.`) if the message isn't one of the conversation's |
| List receipts | Members of the conversation | `404` (`Conversation not found.`) |
| Unread count | Members of the conversation | `404` (`Conversation not found.`) |

- The member is always the token's user. A `userId` in the body or the query string is ignored: nobody can acknowledge for someone else or move someone else's position.
- Membership is checked before the message, so non-members learn nothing about messages, not even whether an ID exists. A message of another conversation, even one the caller belongs to, gets the same `404` as an unknown ID.
- **Every member sees every member's positions**, in direct conversations and groups alike: in a direct conversation, the sender sees that the other participant read their messages; in a group, who has read up to where. Positions show only user IDs, sequence numbers and times. Non-members see nothing.
- A member who leaves or is removed loses their positions with the membership. Joining again starts without positions.

### Unread counts

A member's unread messages are the messages of the conversation that:

1. someone else sent (the member's own never count), and
2. come after the member's read position, by sequence number. A member who has never reported reading anything has no read position, so **every message others sent counts**, including messages sent before they joined.

Acknowledging delivery doesn't change the count; only reading does. Counts are computed from PostgreSQL on every request, never stored, so they can't drift from the messages and positions, whatever order messages and read updates arrive in. The conversation list computes them the same way, two statements for a whole page, so its `unreadCount`s always agree with `GET .../unread-count`.

Unread counts aren't pushed over the WebSocket. A client keeps its badge current from what it receives: a message from someone else with a sequence number after its read position adds one; a change of its own read position (from another device) is a reason to recompute or to call `GET .../unread-count`. After reconnecting, the REST counts are the truth.

### Real-time receipt changes

Subscribe to a conversation's receipts like to its messages, as a member:

```
SUBSCRIBE
id:sub-1
destination:/topic/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/receipts

^@
```

Whenever a member's delivered or read position moves forward, the connected members receive the member's new position, the same JSON as the REST answer:

```
MESSAGE
destination:/topic/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/receipts
content-type:application/json
subscription:sub-1
message-id:a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d-2
content-length:247

{"conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","userId":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","lastDeliveredSequenceNumber":2,"deliveredAt":"2026-10-24T10:32:00.000789Z","lastReadSequenceNumber":2,"readAt":"2026-10-24T10:32:00.000789Z"}^@
```

- **Stored, then delivered.** A change is delivered after its transaction committed. One that couldn't be stored is never delivered.
- **Only changes.** Repeated and outdated acknowledgements change nothing and deliver nothing. Each frame is the member's whole position, and positions only move forward: a client keeps the furthest it has seen per member, whatever order frames arrive in.
- **Only current members** receive changes, the acknowledging member's own connections included, so their other devices learn of it. Members who left or were removed keep their subscription but receive nothing more, and subscribing again is rejected. The one exception: a change that commits at the same moment as a member's removal can still reach that member, because acknowledgements don't take the conversation lock. Every message it covers was sent before the removal. Non-members, unknown conversations and deleted groups get the same `ERROR` frame (`404`) as for the messages topic; patterns and other spellings of the ID get `404` (`Unknown destination.`).
- **Best effort**, like messages: nothing is retried, and a client that wasn't connected catches up through REST.
- Acknowledgements are sent through REST only, not as STOMP `SEND`s.

### Reconnecting

After connecting again (see [Missed messages and reconnecting](#missed-messages-and-reconnecting) for messages):

1. Subscribe to each shown conversation's `messages` and `receipts` topics, then catch up the history.
2. Load `GET .../receipts` for the conversations on screen, and the conversation list (or `GET .../unread-count`) for unread counts. Changes that arrive live meanwhile are merged by keeping each member's furthest position.
3. Acknowledge delivery up to the newest message the client now has without gaps, and reading if the user sees the conversation.

### Errors

| Situation | Status | Notes |
|---|---|---|
| Missing or `null` `messageId` | `400` | `errors` lists `messageId` |
| Malformed JSON, a malformed message ID or conversation ID | `400` | Rejected values are never echoed |
| Missing, malformed, expired or wrongly signed access token | `401` | |
| Conversation doesn't exist, was deleted, or the caller isn't (or is no longer) a member | `404` | `Conversation not found.`, identical in all cases |
| The message doesn't exist or belongs to another conversation | `404` | `Message not found.`, identical in both cases |
| Acknowledging a message at or before the current position | `200` | Not an error: the position is returned unchanged |
| Unexpected server error, including a failure to store the position | `500` | Generic message. Nothing is stored or delivered. |

### Design decisions

- **Positions, not rows per message.** A member's receipts are two numbers per conversation, one row in `receipt_positions`, written only when they move. One row per message and recipient would mean up to 99 rows for every message in a full group, written whether or not anybody reads, and a cleanup. The price: a position can't say "received message 9 but not 8". Clients acknowledge only up to where they have everything, which sequence numbers without gaps make possible.
- **Sequence numbers, not times.** Positions compare sequence numbers, which follow the order in which messages were stored on any number of instances. A position by time could be passed by a message that is stored later with an earlier time (application instances with different clocks), which would then never count as unread.
- **Forward only, in one statement.** Each acknowledgement is one `insert ... on conflict do update ... where` statement that only moves the position forward. Concurrent acknowledgements of one member wait for each other's row lock, and the furthest wins whatever order they commit in. Acknowledgements take no conversation lock, so they never wait for messages being sent.
- **Synchronous, without RabbitMQ.** The position is stored before the request returns, in a short transaction. Nothing consumes receipt changes asynchronously, so they produce no outbox event: an event with no queue for it would be returned by RabbitMQ and retried by the relay forever. Real-time delivery uses the same after-commit path as messages.
- **Membership changes.** Membership is checked without a lock; a member removed at the same moment fails on the position's foreign key to the membership and gets the same `404` as any non-member. The same foreign key deletes positions with their membership.
- **Counted, not stored.** Unread counts are counted when asked, from the index on `(conversation_id, sequence_number)`, which includes the sender: PostgreSQL reads only the index, and with a read position only the entries after it. A stored counter would have to be kept right through concurrent messages, reads, retries and membership changes, and reconciled when it isn't.
- **Database constraints.** Positions must belong to a membership (cascade on delete) and name messages of the same conversation (foreign keys on the sequence number); the read position can't be ahead of the delivered one; a read position and its time come together.

### Known limitations

- **Without a read position, counting reads the whole history**: every message others sent counts, and PostgreSQL counts them in the index. Measured with 1,000,000 messages: with a read position, a count reads the unread entries only (0.1 ms); without one, about 35 ms for a conversation of 200,000 messages, and a conversation-list page of 100 conversations with 2,000 messages each and no read positions takes about 70–100 ms. Counts aren't capped.
- **After upgrading, nobody has a read position**: all existing messages from others count as unread until members report reading. The migration doesn't mark them read, because that would show read receipts for messages nobody read.
- **Members added to a group see its whole history as unread**, as they can read all of it.
- **Visible to all members, no opt-out.** Every member sees every member's positions; members can't turn read receipts off.
- **Fan-out in groups.** Every change goes to every connected member: in a group of 100, up to 100 frames per acknowledgement. Clients should batch delivery acknowledgements.
- **Between instances, live changes need RabbitMQ**, like messages: while it is down, members receive only the changes stored through their own instance (see [Order and delivery](#order-and-delivery)).
- **Unread counts aren't pushed**; clients derive them from what they receive (see [Unread counts](#unread-counts)).
- **The server trusts the client's acknowledgements**: it can't check that a client really has the messages it acknowledges.
- **Removal races.** A receipt change committing at the moment a member is removed can reach that member once; an acknowledgement in flight while a member is removed and added back lands in the new membership, which then doesn't start without a position. Both need the request and the membership change within milliseconds of each other.

## Asynchronous events (RabbitMQ and the transactional outbox)

Every stored message also produces a durable `MessageCreated` event, published to RabbitMQ for asynchronous consumers such as push notifications or analytics in later milestones. Unread counts don't need it: they are counted in PostgreSQL. Receipt changes produce no event (see [Design decisions](#design-decisions-3)). PostgreSQL stays the source of truth for messages and conversations; RabbitMQ carries events and is never read as message history.

The event is written to an outbox table in the same transaction as the message, and a relay publishes it to RabbitMQ after the commit. Sending a message never waits for RabbitMQ and works while it is down.

### WebSocket delivery and durable events

The two paths are independent. Both start from the same stored message, and neither repeats the other.

| | WebSocket delivery | `MessageCreated` event |
|---|---|---|
| Receivers | Connected members: this instance's directly, other instances' through the `chattywap.realtime` exchange | RabbitMQ consumers |
| When | Right after the commit, in the sending thread; a RabbitMQ hop later on other instances | When the relay has published it, normally within a second |
| Guarantee | At most once, best effort; not retried | At least once; retried until published |
| Needs RabbitMQ | Not for this instance's clients; for other instances' clients, yes (dropped while it is down) | Yes, but storing the message doesn't wait for it |
| Carries | The message as the API returns it, content included; transient | IDs only; persistent |
| If the application stops right after the commit | The live delivery is lost; the history has the message | Nothing is lost: the event is in the outbox |

The example consumer of `MessageCreated` events doesn't deliver anything over WebSocket, and the live deliveries passed between instances never go through the outbox.

### The `MessageCreated` event

Version 1, `messaging.dto.MessageCreatedEvent`, published to the exchange `chattywap.events` with routing key `message.created`. The body is JSON, serialized like the REST API's JSON:

```json
{"eventId":"4e1f2a3b-5c6d-4e7f-8a9b-0c1d2e3f4a5b","eventType":"MessageCreated","eventVersion":1,"occurredAt":"2026-10-24T10:30:00.123456Z","messageId":"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","senderId":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11"}
```

| Field | Meaning |
|---|---|
| `eventId` | Unique per event, the same in every delivery of it. Consumers deduplicate by it. |
| `eventType`, `eventVersion` | `MessageCreated`, `1` |
| `occurredAt` | When the message was stored: its `createdAt` |
| `messageId`, `conversationId`, `senderId` | The stored message, its conversation and its sender |

- The event never contains the message's content, the sender's name or email address, or tokens. Consumers that need more read it through the owning feature.
- AMQP properties: `message_id` is the `eventId`, `type` is `MessageCreated`, `content_type` is `application/json`, and the message is persistent. `correlation_id` is the request ID of the request that stored the message (its `X-Request-Id`), if there was one: for logs only, never for processing.
- Compatible changes (new optional fields) keep the version; consumers ignore fields they don't know. Removing, renaming or changing the meaning of a field is a new version. A consumer rejects versions and types it doesn't know, to its dead-letter queue.

### Exchanges and queues

The application declares these when it connects to RabbitMQ, and again after every reconnect.

| Name | Kind | Purpose |
|---|---|---|
| `chattywap.events` | Topic exchange, durable | All application events. The routing key names the event: `message.created`. |
| `chattywap.message-counts` | Quorum queue, durable | The example consumer's queue, bound to `message.created`. Rejected messages, and messages delivered 10 times without being acknowledged, go to `chattywap.events.dead-letter`. |
| `chattywap.events.dead-letter` | Direct exchange, durable | Receives the messages consumers rejected, with their queue's name as routing key. |
| `chattywap.message-counts.dead-letter` | Quorum queue, durable | Dead letters of the example consumer, kept for investigation. Nothing consumes it. |
| `chattywap.realtime` | Fanout exchange, durable | Live deliveries between application instances: every stored message and receipt change, as JSON (`type` `MessageSent` or `ReceiptPositionChanged`, the publishing instance in the `chattywap-origin` header). Not events: see [WebSocket delivery and durable events](#websocket-delivery-and-durable-events). |
| `chattywap.realtime.<instance ID>` | Classic queue, non-durable, exclusive, auto-delete, at most 10,000 messages (the oldest are dropped) | One per running instance, with a random ID per start, bound to `chattywap.realtime` and consumed by that instance only. RabbitMQ deletes it when the instance disconnects. |

Queue arguments can't change on an existing queue. Changing them needs a new queue name, or deleting the queue first (`docker compose down -v` locally).

### Outbox lifecycle

1. **Write.** `MessageService.sendMessage` stores the message. Still in its transaction, `messaging.OutboxWriter` inserts the event into `outbox_events`, pending: `published_at` empty, `attempts` 0, due at once. The message, its event and the conversation's activity time commit together. If any of them fails, all roll back, and no event exists for a message that wasn't stored.
2. **Claim.** Every `poll-interval` (1 second), `messaging.OutboxRelay` claims up to `batch-size` (100) due events in one short statement. The claim counts the attempt and reserves the events for the `lease` (60 seconds). Rows other relays are claiming at that moment are skipped (`for update skip locked`), so several application instances never claim the same event at the same time. No lock or transaction is held from here on.
3. **Publish.** The relay publishes each event and waits up to `confirm-timeout` (10 seconds) for RabbitMQ's publisher confirms.
4. **Record.** Events RabbitMQ acknowledged and didn't return get `published_at`. Every other outcome is a failed attempt: a negative acknowledgement, a returned message (no queue is bound to its routing key), no confirmation in time, or a broker that can't be reached. The event stays pending with the error in `last_error`, and is due again after a delay of 1 second, doubled after every further failure, up to 5 minutes. Events are retried until they are published; none is dropped.

A batch always ends before its lease, so no other relay claims its events while it still works on them: sending stops at the first failed send (the rest of the batch would wait and fail the same way) and once only the confirm timeout is left of the lease; the events not sent are failed attempts. A relay whose lease expired anyway (a long pause) can't record a failure for an event another relay has claimed since. A run continues with the next batch as long as batches are full. The relay publishes on a connection of its own, so a broker that blocks publishers (memory or disk alarm) doesn't block consumers. Published rows are kept; nothing deletes them yet.

### Consumers, retries and idempotency

The example consumer, `messaging.MessageCountConsumer`, counts the `MessageCreated` events of each conversation in `conversation_message_counts`. It's the smallest effect that shows whether processing is reliable: a duplicate applied twice would make a count too high. Nothing in the API reads it.

- **Acknowledgement.** The listener container acknowledges a message after the listener returned, so after the consumer's transaction committed (`acknowledge-mode: auto`). If the listener throws, the message is rejected and never requeued (`default-requeue-rejected: false`).
- **Retries.** Failures are retried inside the listener, with backoff: 4 attempts in all, 1, 2 and 4 seconds apart. Invalid events (unreadable JSON, missing fields, another type or version), data the database rejects (an unknown conversation) and other failures that would repeat aren't retried.
- **Dead-lettering.** After the last attempt, or at once for those failures, the message is rejected and RabbitMQ moves it to the consumer's dead-letter queue, at least once: the queue keeps it until the dead-letter queue has accepted it. The application logs the event ID and the cause, never the body. A message that crashes the application before it can be rejected is dead-lettered by RabbitMQ after 10 deliveries.
- **Idempotency.** In the transaction of its effect, the consumer inserts `(consumer_name, event_id)` into `processed_events` with `on conflict do nothing`. If the row already exists, the event was processed before, and nothing changes. The record and the effect commit together, so a crash between them leaves neither, and the redelivered event is processed normally.

**Why an event can arrive twice.** Publishing and recording the publication are two steps on two systems. If the relay stops, or PostgreSQL fails, after RabbitMQ confirmed an event but before `published_at` is set, the event stays pending and is published again once its lease expires. A confirmation that arrives after the timeout has the same result. RabbitMQ also redelivers a message whose consumer stopped before acknowledging it. Delivery is therefore **at least once**, not exactly once. Deduplicating by `eventId` in the consumer's own transaction makes the effect in PostgreSQL happen once per event however often it's delivered. Effects outside PostgreSQL (an email, a push notification) can't be made exactly once this way; such consumers must tolerate duplicates themselves.

### Failure behavior

| Situation | What happens |
|---|---|
| The message's transaction fails | The message and its event both roll back. Nothing is published. |
| RabbitMQ is down | Sending (REST and STOMP), the history and WebSocket delivery to the clients of the instance that stored the message keep working; clients on other instances don't receive it live, and find it in the history. Events stay pending; the relay retries with growing delays (at most 5 minutes apart) and publishes them once RabbitMQ is back. `/actuator/health` on the management port reports `rabbit` and the overall status `DOWN` (`503`) meanwhile; the probes (`/livez`, `/readyz`) don't include RabbitMQ, so the instance stays in the load balancer. |
| The application stops while publishing | Claimed events are due again when their lease expires, and every instance may publish them. Events RabbitMQ had already accepted are published a second time. |
| The consumer stops before acknowledging | RabbitMQ delivers the message again. The deduplication makes that harmless. |
| A transient failure while processing | Retried in the listener, then processed. |
| A poison message | Dead-lettered after its retries, or at once if it's invalid. The queue isn't blocked. |
| Shutdown | The relay starts no new batch and finishes the current one (waiting up to the lease), so it doesn't stop between a confirmation and recording it. The listener container finishes the message in progress; unacknowledged messages are redelivered after the restart. |

### Configuration

The connection uses the `RABBITMQ_*` variables from [Configure the local environment](#configure-the-local-environment). The relay's settings, in `application.yaml`, can be overridden like any Spring property, for example `CHATTYWAP_MESSAGING_OUTBOX_POLLINTERVAL=5s`:

| Property | Default | Meaning |
|---|---|---|
| `chattywap.messaging.outbox.relay-enabled` | `true` | Whether this instance publishes outbox events. Events are still written when it's off. |
| `chattywap.messaging.outbox.poll-interval` | `1s` | Pause between two runs of the relay |
| `chattywap.messaging.outbox.batch-size` | `100` | Events claimed and published together (1 to 1000) |
| `chattywap.messaging.outbox.confirm-timeout` | `10s` | How long to wait for RabbitMQ to confirm a batch |
| `chattywap.messaging.outbox.lease` | `60s` | How long a claimed event is reserved. Must be longer than `confirm-timeout`. |
| `chattywap.messaging.outbox.initial-retry-delay`, `max-retry-delay` | `1s`, `5m` | Delay after the first failed attempt, and its maximum |
| `spring.rabbitmq.listener.simple.retry.*` | 3 retries, 1s, ×2, at most 10s | Consumer retries |
| `spring.rabbitmq.connection-timeout` | `5s` | How long to wait for an unreachable broker |

Publisher confirms (`publisher-confirm-type: correlated`), returns and mandatory publishing are required by the relay; don't turn them off.

### Logs and metrics

The relay and the consumer log event IDs, never event bodies: the failed attempts of each relay batch in one `WARN` line per reason (how many, the first event with its attempt and error, the range of next attempts; each event's error is in its `last_error`, and each attempt is logged at `DEBUG`), each dead-lettered message at `ERROR`. Published and processed events are logged at `DEBUG`. While a consumer processes an event, its log lines carry the event ID and the ID of the request that stored the message (`eventId`, `requestId`), so `grep <requestId>` finds the request and its asynchronous processing together.

| Metric | Meaning |
|---|---|
| `chattywap.outbox.pending` | Events not published yet (read from the database) |
| `chattywap.outbox.oldest.pending.age` | Age in seconds of the oldest event not published yet; 0 if there is none |
| `chattywap.outbox.published` | Events RabbitMQ confirmed |
| `chattywap.outbox.failed` | Failed publication attempts, by `reason`: `nack`, `returned`, `timeout`, `unreachable`, `send-error`, `out-of-time` |
| `chattywap.outbox.retried` | Attempts for events that had been claimed before |
| `chattywap.events.consumed` | Events processed, by `outcome`: `processed`, or `duplicate` when skipped |
| `chattywap.events.consumer.failures` | Failed processing attempts, retries included |
| `chattywap.events.dead.lettered` | Messages this application dead-lettered, by `queue` |
| `chattywap.fanout.published`, `chattywap.fanout.dropped`, `chattywap.fanout.buffered`, `chattywap.fanout.received` | Live deliveries passed on to the other instances, dropped (by `reason`), waiting, and received from them (by `outcome`); see [OPERATIONS.md](OPERATIONS.md#metrics) |

Metrics are on the management port: `curl http://localhost:8081/actuator/metrics/chattywap.outbox.pending`, or all of them in Prometheus' format at `/actuator/prometheus`. [OPERATIONS.md](OPERATIONS.md#metrics) lists every metric and what it diagnoses.

### Troubleshooting

Events that aren't published yet, oldest first, with their last error:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select id, event_type, attempts, next_attempt_at, last_error, created_at from outbox_events where published_at is null order by created_at limit 50"'
```

The event of a message, and whether the example consumer processed it (`select * from processed_events where event_id = '<eventId>'`):

```sql
select id, published_at, attempts, last_error from outbox_events where payload::jsonb ->> 'messageId' = '<messageId>';
```

To retry a pending event now instead of waiting for its delay: `update outbox_events set next_attempt_at = now() where id = '<eventId>' and published_at is null;`

Queues and their depths:

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name type messages messages_unacknowledged
```

Dead letters: open the management UI (<http://localhost:15672>), *Queues and Streams* → `chattywap.message-counts.dead-letter` → *Get messages*. The message ID is the event ID; the `x-first-death-reason` header says why it was dead-lettered (`rejected`: by the consumer, `delivery_limit`: too many redeliveries) and `x-first-death-queue` where from. The consumer's log line for that event ID has the cause. After fixing the cause, the simplest replay goes through the outbox, which keeps every event: `update outbox_events set published_at = null, next_attempt_at = now() where id = '<eventId>';` republishes it, and consumers that processed it already skip it. Then purge the dead letter.

### Known limitations

- **At least once, not exactly once.** Consumers must deduplicate (see above). The example consumer does, in PostgreSQL.
- **No order.** Retries and several relays publish events in any order. Consumers that need order must use `occurredAt` or read the current state.
- **Tables grow without bound.** Published `outbox_events` and `processed_events` rows are never deleted. The relay's queries use a partial index of pending events, so the growth doesn't slow them down, but a cleanup is needed eventually. It must keep `processed_events` longer than an event can still be redelivered or replayed.
- **Unroutable events are retried indefinitely**, every 5 minutes at most, for example after a binding was removed by mistake. `chattywap.outbox.failed{reason=returned}` and `last_error` show it.
- **Retries hold the consumer.** Retry delays run inside the listener, so a failing message delays the messages behind it by up to 7 seconds before it is dead-lettered.
- **Dead letters aren't replayed automatically**, and nothing alerts on them besides the metric and the log.
- **One RabbitMQ node** in Compose: the quorum queues have no replicas there.

## Run tests

```bash
./mvnw test
```

Runs the unit tests (`*Test`, `*Tests`) with Surefire. These don't need Docker.

```bash
./mvnw verify
```

Runs the unit tests, then the integration tests (`*IT`) with Failsafe. Integration tests start their own PostgreSQL and RabbitMQ containers through Testcontainers, so Docker must be running. They don't use the Compose services. The container images are defined in `src/test/java/com/adonis/chattywap/TestcontainersConfiguration.java` and should stay in sync with `compose.yaml`.

Integration tests run with the `test` profile (`@ActiveProfiles("test")`), which takes a test-only `JWT_SECRET` from `src/test/resources/application-test.yaml`. No environment variables are needed.

To run only the authentication tests:

```bash
./mvnw verify -Dtest='AccessTokenServiceTest,PasswordHashingTest' -Dit.test='AuthControllerIT,RefreshTokenIT,UserControllerIT,DatabaseSchemaIT'
```

| Test class | Covers |
|---|---|
| `AccessTokenServiceTest` (unit) | JWT claims; rejection of wrong signatures, tampered, expired, wrong-issuer and wrong-audience tokens; secret validation |
| `PasswordHashingTest` (unit) | BCrypt hashing, verification and salting |
| `AuthControllerIT` | Registration, email normalization, duplicate emails (also concurrent), validation errors, login, identical errors for wrong password and unknown email |
| `RefreshTokenIT` | Rotation, hash-only storage, reuse detection, concurrent refreshes, expired and unknown tokens, logout behavior |
| `UserControllerIT` | `/api/v1/users/me` with valid, missing, malformed, expired, wrongly signed, wrong-issuer and wrong-audience tokens |
| `DatabaseSchemaIT` | Migration constraints: unique and normalized email, foreign keys, cascading deletes, token-hash format; conversation types and roles, group and direct shape, unique ordered direct pair, unique membership, conversation foreign keys and delete behavior, only groups can be deleted; upgrading a V2 database to V3; message foreign keys, blank and too long content, conversations and users with messages can't be deleted; positive sequence numbers unique per conversation; upgrading a V5 database to V6 numbers existing messages in history order; receipt positions belong to a membership (removed with it) and name messages of their own conversation, read never ahead of delivered |
| `DirectPairTest` (unit) | Ordering of a direct conversation's participants the way PostgreSQL orders UUIDs |
| `KeysetCursorTest` (unit) | Encoding and decoding of list cursors (conversations and messages), a stable format; rejection of cursors the server didn't issue |
| `ConversationControllerIT` | Every conversation endpoint through HTTP: creation and duplicate direct conversations, validation, listing, pagination by number and by cursor (equal timestamps, every conversation exactly once across pages of several sizes, changes between pages, invalid cursors), access control (`401`, `403`, `404` for non-members), adding and removing members, the member limit, promoting and demoting (last administrator, invalid changes), renaming, deleting, leaving, the administrator hand-over and the deletion of groups whose last member leaves |
| `ConversationServiceIT` | Concurrent creation of the same direct conversation; under the conversation lock: concurrent adds of the same user, concurrent adds near the member limit, concurrent leaves, administrators demoting themselves at once, deletion during an addition, the last two members leaving at once; rollback when a membership insert fails or a deletion can't be completed; agreement with PostgreSQL's UUID order |
| `MessageControllerIT` | Both message endpoints through HTTP: sending (the caller is always the sender, content stored as sent, blank, too long, NUL and unpaired-surrogate content, malformed bodies, a failed commit is a `500` without details that changes nothing), access control (`401`, `404` for non-members, former members and deleted groups, identical to unknown IDs), sending moves the conversation to the top of the list while rejected messages don't, the history's order (equal timestamps), pagination by number and by cursor (every message exactly once across pages of several sizes, new messages between pages, size limit, invalid cursors), no messages from other conversations; sequence numbers from 1 in each conversation; the request's `X-Request-Id` becomes its event's correlation ID |
| `MessageServiceIT` | Sending at the same moment as the sender leaves, is removed or the group is deleted (the message is stored before or not at all); messages to one conversation stored one after the other, with times and sequence numbers in commit order, while other conversations aren't held up; concurrent messages get consecutive sequence numbers; concurrent messages and group changes without deadlocks; the activity time needs the caller's transaction; the number of statements per send and per page, however many senders or members it has; sends waiting for the same conversation hold no database connection, and non-members are refused before they could wait |
| `ConversationSendGateTest` (unit) | At most two sends to one conversation at a time, others not held up, the time limit, release after a failure, nothing left behind |
| `ReceiptControllerIT` | Receipts and unread counts through HTTP: acknowledging delivery and reading (covers earlier messages, read implies delivered, delivered isn't read), repeated and outdated acknowledgements change nothing, positions never move back, access control (`401`, `404` for non-members, former members and deleted groups, identical to unknown IDs), a message of another conversation is `404` like an unknown one, the member is always the caller, validation, every member's positions, independent positions in groups, leaving removes a position; unread counts exclude the member's own messages, count after the read position or everything without one, go down as the position moves, and are the same in the conversation list (by number and by cursor) as for each conversation |
| `ReceiptServiceIT` | An outdated acknowledgement waiting for a newer one doesn't move the position back; concurrent mixed acknowledgements end at the furthest positions; a member removed while acknowledging gets `404` and no position; a position whose transaction fails is neither stored nor announced, and receipts write no outbox events; only changes are announced, to the members at that moment; statements for unread counts per list page, however many conversations it has |
| `StompSecurityIT` | Over a real WebSocket connection: `CONNECT` with a valid token (the connection's user), with malformed, wrongly signed, expired, wrong-issuer and wrong-audience tokens, and without a bearer token; a token on the handshake is ignored; frames before `CONNECT` and unsupported frames; other origins can't connect; the rest of HTTP still needs a token; members can subscribe, non-members, former members and deleted groups get the same `404` as unknown IDs; patterns, other users' queues and other destinations are rejected; removed members stop receiving; non-members can't send; sending to broker destinations is rejected; the sender is always the connection's user; no `SUBSCRIBE` or `SEND` after the token expired |
| `StompMessagingIT` | Over real WebSocket connections: a message is stored before it is delivered, to every subscriber including the sender, with the stored ID and time and the same JSON as the history; a message whose commit fails is reported to the sender only and never delivered; messages reach only their own conversation's topic; each message is delivered once, sent through REST or STOMP; invalid and unreadable messages are answered on the error queue and the connection stays open; one connection's messages are stored and delivered in the order sent; concurrent senders: every message stored once, delivered once, each sender's order kept; messages missed while disconnected are in the history; a STOMP send's event carries the frame's request ID |
| `StompHeartbeatIT` | A connection that offered heartbeats and fell silent is closed; one that asked for none isn't; ended connections are counted |
| `StompReceiptsIT` | Over real WebSocket connections: a receipt change is stored before it is delivered, to every member including the acknowledging one, as the REST answer; acknowledgements that change nothing deliver nothing; changes reach only their own conversation's receipts topic; removed members stop receiving them and can't subscribe again; only members can subscribe, other destinations are rejected; a change whose commit fails is never delivered |
| `MultiInstanceDeliveryIT` | Two application instances sharing PostgreSQL and RabbitMQ, over real HTTP and WebSocket connections: a message stored through one reaches its recipients' connections on both instances once each, as the history has it, and a removed member's subscription on the other instance receives nothing; one connection's messages reach the other instance in the order sent; receipt changes reach members on the other instance; while RabbitMQ is stopped, each instance delivers to its own clients and the other's find the messages in the history, and once it is back, delivery between the instances resumes without late deliveries |
| `RealtimeFanoutPublisherTest` (unit) | What is passed on to the other instances: JSON with type, origin, request ID, transient; one channel, in commit order; after a failure, a pause without connection attempts and one warning, then a recovery; a full buffer drops what doesn't fit without holding up the committing thread; stopping closes the connection |
| `RealtimeFanoutConsumerTest` (unit) | Other instances' messages and receipt changes go to `realtime` as published; the instance's own are skipped; unreadable, incomplete and unknown events are dropped without failing; added fields are ignored; the request ID is in the log context |
| `OutboxRelayTest` (unit) | What the relay makes of each broker answer (acknowledged, rejected, returned, unconfirmed, unreachable); any failed send ends the batch, and so does the end of the time for sending; batches, shutdown, the retry delay; the request ID published as correlation ID |
| `MessageCountConsumerTest` (unit) | Reading and validating events: unreadable, incomplete, other types and versions are rejected without processing; unknown fields are ignored |
| `MessagingConfigTest` (unit) | Which consumer failures are retried |
| `OutboxIT` | Every stored message has exactly one pending `MessageCreated` event with the contract's fields and no content; a failure to write the event rolls back the message, and a message that can't be stored leaves no event; writing needs the sender's transaction |
| `OutboxRelayIT` | Against a real broker: events are marked published only after RabbitMQ confirmed them; returned (unroutable), rejected (nack) and unconfirmed (paused broker) events stay pending with their error and are retried after their delay; batches; an event whose publication couldn't be recorded is published again and processed once; a claim's lease, and a relay whose lease expired can't record a failure any more; concurrent claims get different events without waiting; concurrent relays publish every event once |
| `MessageCountConsumerIT` | Against a real broker: an event is processed and acknowledged; duplicate deliveries count once; transient failures are retried; an event that keeps failing is dead-lettered after its retries without any effect, with what's needed to investigate it, and doesn't block the queue; invalid events and events about unknown conversations are dead-lettered at once |
| `BrokerOutageIT` | Over real WebSocket connections, with the scheduled relay: every message's event is published and processed once, and the consumer delivers nothing over WebSocket; while RabbitMQ is stopped, messages sent through REST and STOMP are stored and delivered, their events stay pending with errors, and are published and processed once after it restarts; meanwhile `/actuator/health` reports RabbitMQ down while `/livez` and `/readyz` stay up, and the backlog shows in the metrics |
| `HealthEndpointIT` | Over real HTTP: `/livez` and `/readyz` are public and answer with a status only; no `/actuator` path is served on the application port; the management port reports every dependency with details but no credentials; liveness and readiness contain neither PostgreSQL nor RabbitMQ; Prometheus metrics with the expected names and no IDs in their labels; other endpoints aren't exposed; request IDs in every response, a proxy's kept if it looks like one |
| `DatabaseOutageIT` | With PostgreSQL's container paused (connections open, no answers): requests fail with `503` within the timeouts and store nothing, the instance stays alive and ready, `/actuator/health` reports the database down; afterwards requests succeed again without intervention |
| `RequestBodyLimitIT` | Over real HTTP: bodies above 64 KB are refused with `413`, whether their length is declared or they are chunked; smaller ones are read as before |
| `RequestLoggingFilterTest` (unit) | Request IDs in the log context and the response, a proxy's ID kept only if it looks like one, which requests are logged at which level, nothing from the query string |
| `DatabaseUnavailableTest` (unit) | Which database failures count as unavailability (`503`) and which don't |
| `MessageCountConsumerTest` (unit) | Also: the event ID and the request ID are in the log context while an event is processed, and a correlation ID that isn't an ID is left out |

The STOMP tests, `BrokerOutageIT`, `HealthEndpointIT`, `DatabaseOutageIT` and `RequestBodyLimitIT` run against a server on a random port (`webEnvironment = RANDOM_PORT`), with the management server on another random port. They, and `OutboxRelayIT`, start their own application context and containers. `OutboxRelayIT` and `BrokerOutageIT` pause or stop their RabbitMQ container, and `DatabaseOutageIT` its PostgreSQL container, which no other test uses. The test profile turns the scheduled relay off; `OutboxRelayIT` runs the relay itself, and `BrokerOutageIT` turns the schedule on. `MultiInstanceDeliveryIT` starts two applications itself, on random ports, against a PostgreSQL and a RabbitMQ container of its own, and stops that RabbitMQ.

To run only the operational tests (health, metrics, outages, limits, logging):

```bash
./mvnw verify -Dtest='RequestLoggingFilterTest,DatabaseUnavailableTest,ConversationSendGateTest' -Dit.test='HealthEndpointIT,DatabaseOutageIT,BrokerOutageIT,RequestBodyLimitIT,StompHeartbeatIT,MultiInstanceDeliveryIT'
```

To run only the conversation tests:

```bash
./mvnw verify -Dtest='DirectPairTest,KeysetCursorTest' -Dit.test='ConversationControllerIT,ConversationServiceIT,DatabaseSchemaIT'
```

To run only the message tests:

```bash
./mvnw verify -Dtest='KeysetCursorTest' -Dit.test='MessageControllerIT,MessageServiceIT,DatabaseSchemaIT'
```

To run only the receipt and unread-count tests:

```bash
./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='ReceiptControllerIT,ReceiptServiceIT,StompReceiptsIT,DatabaseSchemaIT'
```

To run only the real-time (WebSocket/STOMP) tests:

```bash
./mvnw verify -Dtest='RealtimeFanoutPublisherTest,RealtimeFanoutConsumerTest' -Dit.test='StompSecurityIT,StompMessagingIT,StompReceiptsIT,MultiInstanceDeliveryIT'
```

To run only the asynchronous-event tests (outbox, relay, consumer):

```bash
./mvnw verify -Dtest='OutboxRelayTest,MessageCountConsumerTest,MessagingConfigTest,RealtimeFanoutPublisherTest,RealtimeFanoutConsumerTest' -Dit.test='OutboxIT,OutboxRelayIT,MessageCountConsumerIT,BrokerOutageIT,MultiInstanceDeliveryIT'
```

GitHub Actions (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push to `main` and on every pull request.

## Database migrations

Flyway owns the schema. Hibernate only validates it (`spring.jpa.hibernate.ddl-auto=validate`) and never creates or alters tables.

1. Add a SQL file to `src/main/resources/db/migration`, named `V<version>__<description>.sql`, with the next free version. Example: `V2__create_conversations.sql`.
2. Start the application or run `./mvnw verify`. Flyway applies pending migrations at startup, before Hibernate validates the entities against the schema.

Rules:

- Never edit or rename a migration that has already been applied. Add a new migration instead.
- Change entities and migrations in the same commit, otherwise schema validation fails at startup.

Existing migrations:

| Version | Changes |
|---|---|
| `V1__create_users_and_refresh_tokens.sql` | Creates `users`, `refresh_token_families`, `refresh_tokens` |
| `V2__create_conversations.sql` | Creates `conversations`, `conversation_members` |
| `V3__add_group_lifecycle.sql` | Adds `conversations.deleted_at` with its checks; marks groups without members deleted |
| `V4__create_messages.sql` | Creates `messages` with its history index |
| `V5__create_outbox_and_processed_events.sql` | Creates `outbox_events` with a partial index of pending events, `processed_events`, and the example consumer's `conversation_message_counts` |
| `V6__add_message_sequence_numbers.sql` | Adds `messages.sequence_number`, numbers existing messages in history order, unique per conversation (its index includes the sender) |
| `V7__create_receipt_positions.sql` | Creates `receipt_positions`: each member's delivered and read position, removed with the membership |
| `V8__add_outbox_correlation_id.sql` | Adds `outbox_events.correlation_id`: the request that wrote the event, for tracing |

To see which migrations have been applied to the local database:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history"'
```
