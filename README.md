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
| `GET /api/v1/conversations?page=0&size=20` | none | `200` with one page of the caller's conversations |
| `GET /api/v1/conversations/{conversationId}` | none | `200` with the conversation and its members |
| `POST /api/v1/conversations/{conversationId}/members` | `userIds` | `200` with the updated conversation |
| `DELETE /api/v1/conversations/{conversationId}/members/{userId}` | none | `204`, no body |
| `POST /api/v1/conversations/{conversationId}/leave` | none | `204`, no body |

### Conversation types and roles

- **Direct**: exactly two members and no name. Two users have at most one direct conversation: asking for it again, from either side, returns the existing one with `200`. Nobody can be added to, removed from or leave a direct conversation. Both members have the role `MEMBER`.
- **Group**: a name that isn't blank and has at most 100 characters as sent (surrounding whitespace counts; characters outside the Basic Multilingual Plane, such as most emoji, count as two). It is stored without surrounding whitespace. The creator is always a member, with the role `ADMIN`. Everyone added at creation or later joins as `MEMBER`.

Members see each other's user ID, display name, role and join time. Email addresses are never included.

### Authorization rules

| Endpoint | Allowed for | Otherwise |
|---|---|---|
| Create direct | Any user. The recipient must exist and must not be the caller. | `400` |
| Create group | Any user. All `memberIds` must exist. | `400` |
| List | Any user. The list contains only conversations the caller is a member of. | |
| Details | Members of the conversation | `404` |
| Add members | Administrators of a group | `404` for non-members, `409` for direct conversations, `403` for members who aren't administrators |
| Remove member | Yourself: any member of a group (the same as leaving). Someone else: administrators of the group. | `404` for non-members, `409` for direct conversations, `403` for members who aren't administrators, `404` if the user to remove isn't a member |
| Leave | Members of a group | `404` for non-members, `409` for direct conversations |

- **Non-members can't tell a conversation exists.** A conversation the caller doesn't belong to gets exactly the same `404` as an ID that doesn't exist, on every endpoint. Guessing IDs reveals nothing.
- Checks run in this order: membership, then conversation type, then role, then the request's user IDs. Only members ever get a `403` or `409`, and they already know the conversation.
- Membership is checked in the database on every request, so a removed member loses access immediately.

### Membership rules

- **Adding** users who are already members does nothing for them; the others are added. Repeating a request is therefore harmless and returns `200`. If any of the IDs doesn't exist, the request fails with `400` and nobody is added.
- **Removing**: administrators can remove any other member, including other administrators.
- **Leaving** (or removing yourself) is open to every member. When the group's last administrator leaves, the longest-standing remaining member (earliest `joinedAt`, ties broken by user ID) becomes `ADMIN`. A group with members therefore always has an administrator. There is no endpoint to promote members yet, so in practice a group has exactly one administrator.
- When the **last member** leaves, the group stays in the database without members. Nobody can see or rejoin it.
- Adding, removing and leaving update the conversation's `updatedAt`, which moves it to the top of its members' lists.

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
{"content":[{"id":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","type":"GROUP","name":"Weekend Plans","role":"ADMIN","memberCount":3,"otherUser":null,"createdAt":"2026-10-24T10:25:00.654321Z","updatedAt":"2026-10-24T10:25:00.654321Z"},{"id":"3c1e5a7b-2d4f-4a6c-8e0b-1f2a3b4c5d6e","type":"DIRECT","name":null,"role":"MEMBER","memberCount":2,"otherUser":{"id":"9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c","displayName":"Bob"},"createdAt":"2026-10-24T10:20:00.123456Z","updatedAt":"2026-10-24T10:20:00.123456Z"}],"page":{"size":20,"number":0,"totalElements":2,"totalPages":1}}
```

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

### Pagination

`GET /api/v1/conversations` is paginated with Spring Data's parameters:

- `page`: zero-based page number, default `0`.
- `size`: entries per page, default `20`, at most `100` (larger values are reduced to 100). Invalid values fall back to the defaults.
- The order is fixed: most recently updated first (`updatedAt` descending), then by `id` descending, so conversations updated at the same moment always come in the same order. A `sort` parameter is ignored.
- The response has the entries in `content` and the page metadata in `page`: `size`, `number`, `totalElements`, `totalPages`. A page past the end has an empty `content`.

Pages are offsets into the current order. If a conversation changes between two requests, it moves to the top, and later pages shift by one: an entry can appear twice or be skipped. Clients that need an exact view should reload from page `0`.

### Errors

Errors use the same problem-detail format as the rest of the API (see [Errors](#errors)).

| Situation | Status | Notes |
|---|---|---|
| Invalid body: missing `recipientId`, blank or too long `name`, missing `memberIds`, empty `userIds`, more than 100 IDs, a `null` ID | `400` | `errors` lists each invalid field, e.g. `memberIds[1]` |
| Malformed UUID in the path or body | `400` | |
| Direct conversation with yourself | `400` | |
| User IDs in the body that don't exist | `400` | `userIds` lists them. Nothing is created or added. |
| Missing, malformed, expired or wrongly signed access token | `401` | |
| Adding or removing others without being an administrator | `403` | |
| Conversation doesn't exist, or the caller isn't a member | `404` | Identical response in both cases |
| The user to remove isn't a member | `404` | |
| Adding, removing or leaving in a direct conversation | `409` | |

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"Some users do not exist.","instance":"/api/v1/conversations/group","userIds":["0e8f6a1c-55d4-4c3a-9b7e-2f1d0c9b8a7e"]}
```

Unknown users in the body are a `400`, not a `404`: the endpoint exists, the request refers to something that doesn't.

### Design decisions

- **One direct conversation per pair, also under concurrency.** The two participants are stored on the conversation row (`direct_user1_id`, `direct_user2_id`), in a fixed order, under a unique constraint. Two simultaneous requests for the same pair can both find no conversation, but only one insert succeeds; the other request then reads the conversation the first one created and returns it with `200`.
- **Membership changes are serialized per conversation.** Adding, removing and leaving lock the conversation row first, after checking that the caller is a member, so outsiders can't hold the lock. Concurrent changes to the same group therefore run one after the other: the same user can't be added twice, and the last administrator leaving at the same time as the member who would replace them can't leave a group without an administrator.
- **Transactions.** Creating a conversation and its memberships, and each membership change with the `updatedAt` it touches, happen in one transaction: they are stored completely or not at all.
- **Delete behavior.** Users who created or belong to a conversation can't be deleted (foreign keys with `on delete restrict`): there is no account deletion yet, and when there is, it has to decide what happens to the user's conversations explicitly. Memberships are deleted together with their conversation (`on delete cascade`); nothing in the application deletes conversations yet.
- **Indexes.** `conversation_members (user_id, conversation_id)` serves the conversation list and its count; the unique constraint on `(conversation_id, user_id)` serves every lookup by conversation (members, member counts, membership checks); the unique constraint on the direct pair serves the duplicate check. The query plans were checked against seeded data; no other index is needed for these queries.

### Known limitations

- No endpoints to promote or demote administrators, rename groups or delete conversations. Groups therefore have exactly one administrator.
- No limit on group size, apart from at most 100 user IDs per request.
- Groups whose last member left stay in the database.
- Offset pagination: entries can shift between pages when conversations change (see [Pagination](#pagination)).
- Conversation details and membership changes load all members of the conversation. That's cheap for chat-sized groups; very large groups would need paginated member lists.

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
| `DatabaseSchemaIT` | Migration constraints: unique and normalized email, foreign keys, cascading deletes, token-hash format; conversation types and roles, group and direct shape, unique ordered direct pair, unique membership, conversation foreign keys and delete behavior |
| `DirectPairTest` (unit) | Ordering of a direct conversation's participants the way PostgreSQL orders UUIDs |
| `ConversationControllerIT` | Every conversation endpoint through HTTP: creation and duplicate direct conversations, validation, listing and pagination, access control (`401`, `403`, `404` for non-members), adding and removing members, leaving and the administrator hand-over |
| `ConversationServiceIT` | Concurrent creation of the same direct conversation, concurrent adds and leaves (the conversation lock), rollback when a membership insert fails, agreement with PostgreSQL's UUID order |

To run only the conversation tests:

```bash
./mvnw verify -Dtest=DirectPairTest -Dit.test='ConversationControllerIT,ConversationServiceIT,DatabaseSchemaIT'
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

| Version | Creates |
|---|---|
| `V1__create_users_and_refresh_tokens.sql` | `users`, `refresh_token_families`, `refresh_tokens` |
| `V2__create_conversations.sql` | `conversations`, `conversation_members` |

To see which migrations have been applied to the local database:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history"'
```
