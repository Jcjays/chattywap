# Chattywap

Messaging backend built with Spring Boot, PostgreSQL and RabbitMQ. See [ARCHITECTURE.md](ARCHITECTURE.md) for the package structure and design rules.

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

Every endpoint requires an access token except registration, login, refresh, logout and health. See [Authentication](#authentication).

## Check health

```bash
curl http://localhost:8080/actuator/health
```

```json
{"components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"rabbit":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
```

- `db` is PostgreSQL, `rabbit` is RabbitMQ. You can query one component at a time, for example `/actuator/health/db`.
- If a dependency is unavailable, its component and the overall status become `DOWN`, and the endpoint returns HTTP 503.
- Only the health endpoint is exposed over HTTP. It shows each component's status, never its details.

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
- Adding, removing, leaving, promoting, demoting and renaming update the conversation's `updatedAt`, which moves it to the top of its members' lists. Requests that change nothing (adding existing members, renaming to the current name) don't.

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
{"content":[{"id":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","type":"GROUP","name":"Weekend Plans","role":"ADMIN","memberCount":3,"otherUser":null,"createdAt":"2026-10-24T10:25:00.654321Z","updatedAt":"2026-10-24T10:25:00.654321Z"},{"id":"3c1e5a7b-2d4f-4a6c-8e0b-1f2a3b4c5d6e","type":"DIRECT","name":null,"role":"MEMBER","memberCount":2,"otherUser":{"id":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","displayName":"Bob"},"createdAt":"2026-10-24T10:20:00.123456Z","updatedAt":"2026-10-24T10:20:00.123456Z"}],"page":{"size":20,"number":0,"totalElements":2,"totalPages":1},"nextCursor":null}
```

`nextCursor` is `null` here because the list has no more entries. See [Pagination](#pagination).

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

`GET /api/v1/conversations` returns the caller's conversations in a fixed order: most recently updated first (`updatedAt` descending), then by `id` descending. The ID is unique, so conversations updated at the same moment always come in the same order, on every request and in both pagination modes. A `sort` parameter is ignored. Deleted groups are never listed or counted.

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
- **Changes to a group are serialized per conversation.** Adding, removing, leaving, promoting, demoting, renaming and deleting lock the conversation row first (`SELECT ... FOR NO KEY UPDATE`), after checking that the caller is a member, so outsiders can't hold the lock. Concurrent changes to the same group therefore run one after the other, and each one reads the members the previous one left. This is what keeps the rules true under concurrency:
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

Messages are stored and read through these endpoints only. Delivering them to connected clients as they arrive (WebSocket/STOMP), read receipts, unread counts, editing, deleting, reactions, attachments and search are not part of this API yet.

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
- **A message is never stored after its sender stopped being a member.** Sending locks the sender's membership until the message is stored. Leaving, being removed and deleting the group wait for a message that is being sent; a message sent while one of them is in progress waits for it and then gets `404`.

### Examples

Send a message:

```bash
curl -i -X POST http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"content": "Who is bringing snacks?"}'
```

```json
{"id":"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","sender":{"id":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","displayName":"Alice"},"content":"Who is bringing snacks?","createdAt":"2026-10-24T10:30:00.123456Z"}
```

Read the history:

```bash
curl 'http://localhost:8080/api/v1/conversations/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d/messages?size=20' -H 'Authorization: Bearer <accessToken>'
```

```json
{"content":[{"id":"1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","sender":{"id":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","displayName":"Bob"},"content":"Me!","createdAt":"2026-10-24T10:31:12.654321Z"},{"id":"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f","conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","sender":{"id":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11","displayName":"Alice"},"content":"Who is bringing snacks?","createdAt":"2026-10-24T10:30:00.123456Z"}],"page":{"size":20,"number":0,"totalElements":2,"totalPages":1},"nextCursor":null}
```

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
- A message's place in the history is the moment the server stored it, shortly before its transaction committed. If a message takes longer to commit than one sent just after it, it becomes visible second but sorts below the other, possibly in a page the client has already loaded. Reloading the first page shows it; a client that reloads only until it reaches a message it already has can miss it.
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
| Unexpected server error, including a failure to store the message | `500` | Generic message. Details go to the server log only. Nothing is stored. |

### Design decisions

- **Order by time, then ID.** The server sets `createdAt` (its clock, in microseconds) when it stores a message, and the ID breaks ties, so the order is total. There is no per-conversation sequence number: it would need a counter per conversation that every message locks, which serializes all senders of a conversation. The cost of using the clock: with several application instances, two messages sent within the instances' clock difference can be ordered differently from the order they were stored in; messages stored in the same microsecond are ordered by their random IDs; and the order is when messages were stored, not when they became visible (see [History order and pagination](#history-order-and-pagination)). Real-time delivery will need to take this into account.
- **Membership checked under a lock.** Sending checks the membership and stores the message in one transaction, with the sender's membership row locked (`SELECT ... FOR KEY SHARE`) in between. That lock conflicts only with deleting the row, so a message being sent directly holds up only leaving, removal and group deletion for that sender: not role changes, renames, additions or other senders. Sending never takes or waits for the conversation lock that group changes take. A removal that waits for a message holds that lock meanwhile, though, so other changes to the group wait behind it for the few milliseconds the message takes to store. Reading the history takes no lock: a read at the same moment as a removal sees the membership from before or after it.
- **Stored before reporting success.** The transaction commits before the `201` is sent. If it fails, the client gets a `500` and the message isn't stored. Storing a message is not delivering it: nothing is pushed to other members yet.
- **Senders without N+1 queries.** Messages refer to their conversation and sender by ID (no JPA association across features). A page loads all its senders in one query, reading only their ID and display name. Sending takes three queries (membership lock, insert, sender) and a page takes at most four (membership, messages, count, senders), however many senders it has.
- **Pagination in the database.** Pages use `FETCH FIRST`, and `OFFSET` for page numbers. A page by cursor selects the messages that sort after the cursor with a row comparison, `(created_at, id) < (t, i)`, which PostgreSQL uses as the start of the index scan. The equivalent `created_at < t or (created_at = t and id < i)` filters every newer message instead.
- **Indexes.** One new index, `messages (conversation_id, created_at, id)`. PostgreSQL reads it backwards for the newest-first order, so pages need no sort. Checked against 1,000,000 messages, 200,000 of them in one conversation: the first page and a cursor page 100,000 messages deep each read a handful of index pages in under 0.1 ms. Membership checks use the existing membership indexes. There is no index on `sender_id`: nothing looks messages up by sender, and users can't be deleted, so it would only slow down inserts.
- **Database constraints.** Foreign keys to the conversation and the sender (`on delete restrict`: a conversation or a user with messages can't be deleted; nothing in the application deletes either); `content` is `varchar(4000)` and can't be empty or only ASCII whitespace. The API's checks are stricter, so the database constraints only catch bugs.
- **Shared pagination code.** The cursor encoding and the page metadata moved to `common`, now that two lists use them. The cursor format didn't change, so cursors issued before keep working.

### Known limitations

- Page numbers get slower the further back they go: a page reads and skips every newer message (about 18 ms at an offset of 100,000), and every page by number counts the conversation's messages for `totalElements` (about 40 ms with 200,000 messages). Pages by cursor don't count and take the same time at any depth.
- Sending a message doesn't change the conversation's `updatedAt`, so the conversation list isn't ordered by latest message.
- There is no real-time delivery. Clients see new messages by loading the first page of the history again.
- The history is ordered by when messages were stored, which isn't always the order in which they became visible (see [History order and pagination](#history-order-and-pagination)).

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
| `DatabaseSchemaIT` | Migration constraints: unique and normalized email, foreign keys, cascading deletes, token-hash format; conversation types and roles, group and direct shape, unique ordered direct pair, unique membership, conversation foreign keys and delete behavior, only groups can be deleted; upgrading a V2 database to V3; message foreign keys, blank and too long content, conversations and users with messages can't be deleted |
| `DirectPairTest` (unit) | Ordering of a direct conversation's participants the way PostgreSQL orders UUIDs |
| `KeysetCursorTest` (unit) | Encoding and decoding of list cursors (conversations and messages), a stable format; rejection of cursors the server didn't issue |
| `ConversationControllerIT` | Every conversation endpoint through HTTP: creation and duplicate direct conversations, validation, listing, pagination by number and by cursor (equal timestamps, changes between pages, invalid cursors), access control (`401`, `403`, `404` for non-members), adding and removing members, the member limit, promoting and demoting (last administrator, invalid changes), renaming, deleting, leaving, the administrator hand-over and the deletion of groups whose last member leaves |
| `ConversationServiceIT` | Concurrent creation of the same direct conversation; under the conversation lock: concurrent adds of the same user, concurrent adds near the member limit, concurrent leaves, administrators demoting themselves at once, deletion during an addition, the last two members leaving at once; rollback when a membership insert fails or a deletion can't be completed; agreement with PostgreSQL's UUID order |
| `MessageControllerIT` | Both message endpoints through HTTP: sending (the caller is always the sender, content stored as sent, blank, too long, NUL and unpaired-surrogate content, malformed bodies, a failed insert is a `500` without details), access control (`401`, `404` for non-members, former members and deleted groups, identical to unknown IDs), the history's order (equal timestamps), pagination by number and by cursor (new messages between pages, size limit, invalid cursors), no messages from other conversations |
| `MessageServiceIT` | Sending at the same moment as the sender leaves, is removed or the group is deleted (the message is stored before or not at all), also when the removal already holds the conversation lock; what sending doesn't hold up and doesn't wait for; the number of queries per send and per page, however many senders it has |

To run only the conversation tests:

```bash
./mvnw verify -Dtest='DirectPairTest,KeysetCursorTest' -Dit.test='ConversationControllerIT,ConversationServiceIT,DatabaseSchemaIT'
```

To run only the message tests:

```bash
./mvnw verify -Dtest='KeysetCursorTest' -Dit.test='MessageControllerIT,MessageServiceIT,DatabaseSchemaIT'
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

To see which migrations have been applied to the local database:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history"'
```
