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

The same file is read by Docker Compose (to create the containers) and by the application, which imports `.env` from its working directory. Real environment variables take precedence over `.env`, so in other environments you set `POSTGRES_*` and `RABBITMQ_*` as environment variables instead.

| Variable | Used for | Default |
|---|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB` | Database location | `localhost`, `5432`, `chattywap` |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database credentials | none, required |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | Broker location | `localhost`, `5672` |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | Broker credentials | none, required |
| `RABBITMQ_MANAGEMENT_PORT` | Local RabbitMQ management UI (Compose only) | `15672` |

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

Spring Security is on the classpath, but authentication isn't implemented yet. Until it is, every endpoint except health requires a login, and Spring Boot logs a generated development-only password at startup.

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

## Run tests

```bash
./mvnw test
```

Runs the unit tests (`*Test`, `*Tests`) with Surefire. These don't need Docker.

```bash
./mvnw verify
```

Runs the unit tests, then the integration tests (`*IT`) with Failsafe. Integration tests start their own PostgreSQL and RabbitMQ containers through Testcontainers, so Docker must be running. They don't use `.env` or the Compose services. The container images are defined in `src/test/java/com/adonis/chattywap/TestcontainersConfiguration.java` and should stay in sync with `compose.yaml`.

GitHub Actions (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push to `main` and on every pull request.

## Database migrations

Flyway owns the schema. Hibernate only validates it (`spring.jpa.hibernate.ddl-auto=validate`) and never creates or alters tables.

1. Add a SQL file to `src/main/resources/db/migration`, named `V<version>__<description>.sql`. Example: `V1__create_users.sql`.
2. Start the application or run `./mvnw verify`. Flyway applies pending migrations at startup, before Hibernate validates the entities against the schema.

Rules:

- Never edit or rename a migration that has already been applied. Add a new migration instead.
- Change entities and migrations in the same commit, otherwise schema validation fails at startup.
- Until the first migration exists, Flyway logs `No migrations found`. This warning is expected.

To see which migrations have been applied to the local database:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history"'
```
