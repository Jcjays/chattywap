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
| `DatabaseSchemaIT` | Migration constraints: unique and normalized email, foreign keys, cascading deletes, token-hash format |

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

To see which migrations have been applied to the local database:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history"'
```
