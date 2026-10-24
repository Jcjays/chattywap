# Architecture

## Style

Chattywap is a **modular monolith**: one Spring Boot application (Java 21, Spring Boot 4.1, Maven), one PostgreSQL database, one RabbitMQ broker. Code is organized **by feature**. Each feature package owns its entry points, business rules and persistence. There are no microservices, no CQRS, and no extra "clean architecture" layers.

## Packages

Root package: `com.adonis.chattywap`. `ChattywapApplication` stays in the root package so component scanning covers every feature.

```
com.adonis.chattywap
├── ChattywapApplication.java
├── auth/           authentication, token issuing/validation, Spring Security configuration
├── user/           user profiles and user lookup
├── conversation/   conversations and their membership
├── message/        message persistence and operations, delivery/read receipts
├── presence/       online/offline state and presence operations
├── realtime/       WebSocket/STOMP configuration, CONNECT authentication, STOMP entry points, outbound delivery
├── messaging/      RabbitMQ configuration, event publishing and consuming, transactional outbox
└── common/         shared exceptions, API error handling, genuinely cross-cutting code
```

There is no top-level `config` package. Configuration lives with the feature it configures: security in `auth`, STOMP in `realtime`, RabbitMQ in `messaging`.

### Inside a feature

Features are flat by default. Add a `dto/` subpackage once a feature has DTOs. Add other subpackages only when a feature grows large enough that they clearly help.

```
conversation/
├── ConversationController.java
├── ConversationService.java
├── ConversationRepository.java
├── Conversation.java
└── dto/
```

These names show where each responsibility goes. Classes are created when a feature needs them, not ahead of time.

## Layers

| Layer | Responsibility |
|---|---|
| Controller / STOMP handler | Transport only: bind and validate input, identify the caller, call a service, map the result to a DTO. |
| Service | Business rules and authorization (e.g. "only members may post to a conversation"). Owns transaction boundaries and publishes domain events. The only entry point other features may call. |
| Repository | Spring Data interface for the feature's own tables. Package-private where possible, so only the owning feature can reach it. |
| Entity | JPA mapping of a table the feature owns. Never leaves the feature. |
| DTO | Records for request, response and event payloads. Request DTOs carry Bean Validation constraints. |

## Dependencies and boundaries

Allowed dependencies between packages:

| Package | May depend on |
|---|---|
| `common` | nothing in the application |
| `user` | `common` |
| `auth` | `user`, `common` |
| `conversation` | `user`, `common` |
| `message` | `conversation`, `user`, `common` |
| `presence` | `user`, `common` |
| `realtime` | `auth`, `message`, `conversation`, `presence`, `user`, `common` |
| `messaging` | `realtime`, `message`, `conversation`, `presence`, `user`, `common` |

The graph must stay acyclic. A new edge is fine if it keeps the graph acyclic; update this table in the same change.

Rules:

1. Controllers and STOMP handlers contain no business logic.
2. Controllers and handlers never use repositories; they call services.
3. Services enforce business rules and authorization. A check made only in a controller or interceptor does not count.
4. Entities are never exposed in REST responses, STOMP payloads or RabbitMQ messages. The owning feature maps them to DTOs.
5. Features call each other only through services, using DTOs and IDs. No feature uses another feature's repository or entity.
6. An entity refers to another feature's entity by ID, not by JPA association.
7. RabbitMQ consumers deserialize the payload and delegate to a service (or to `realtime` for delivery). They contain no business logic.
8. Code goes into `common` only when two or more features genuinely need it. `common` never depends on a feature.
9. Spring components use constructor injection: `final` fields, one constructor, no field `@Autowired`.
10. No generic base controllers, base services, base entities, or generic CRUD abstractions.
11. No microservices, CQRS, or additional architectural layers.

## Where infrastructure belongs

**REST (Spring MVC)**: controllers in `auth`, `user`, `conversation` and `message` (e.g. message history). The `@RestControllerAdvice` that maps exceptions to HTTP errors lives in `common`. The `SecurityFilterChain` lives in `auth`.

**STOMP / WebSocket**: only `realtime`. It holds the endpoint and broker configuration, the CONNECT interceptor (validates the token via `auth`), subscription authorization (decided by the owning service), `@MessageMapping` handlers that call `message`, `conversation` and `presence` services, connect/disconnect listeners that update `presence`, and outbound delivery to connected users. No other package uses STOMP types or `SimpMessagingTemplate`.

**PostgreSQL**: each table is owned by exactly one feature, and its entity and repository live in that feature. The outbox table belongs to `messaging`. Flyway migrations in `src/main/resources/db/migration` are the only way the schema changes; Hibernate never generates it.

**RabbitMQ**: only `messaging`. It holds exchange/queue/binding configuration, the outbox relay, and `@RabbitListener` consumers. No other package imports Spring AMQP.

**Event flow**: a feature publishes a domain event (a record in its `dto/` package) with Spring's `ApplicationEventPublisher`. `messaging` listens inside the publishing transaction and writes the event to the outbox, so the change and its event commit together. The relay publishes outbox rows to RabbitMQ, and consumers hand them to services or to `realtime`. Because features never depend on `messaging`, this flow creates no cycle.

## Configuration and test infrastructure

- Connection settings and secrets come from environment variables (`POSTGRES_*`, `RABBITMQ_*`, `JWT_*`) and are referenced in `application.yaml`. Committed configuration holds no credentials; the only committed key is the test-only JWT secret in `src/test/resources/application-test.yaml`. For local development, the values live in an uncommitted `.env` file that Docker Compose reads and the application imports (`spring.config.import`). Real environment variables take precedence.
- `compose.yaml` runs PostgreSQL and RabbitMQ for local development. The application runs on the host.
- Unit tests (`*Test`, `*Tests`) run with Surefire in `mvn test` and need no external services. Integration tests (`*IT`) run with Failsafe in `mvn verify` and get PostgreSQL and RabbitMQ from Testcontainers (`TestcontainersConfiguration`, wired with `@ServiceConnection`).
- Actuator exposes only the health endpoint over HTTP. It shows each component's status, never its details.

## Decisions

1. **Token format**: access tokens are self-contained JWTs (HS256, 15 minutes), issued with Spring Security's `JwtEncoder` and validated by its OAuth2 resource server support (`spring-boot-starter-security-oauth2-resource-server`) with a `JwtDecoder` that checks signature, expiry, issuer and audience. Refresh tokens are opaque random strings, stored as SHA-256 hashes in PostgreSQL, single-use and rotated on every refresh. Each login starts a token family (`refresh_token_families`); reusing a rotated token revokes the family. Every refresh and logout locks the family row first, so the operations of one session are serialized.
2. **Caller identity**: the JWT subject is the user ID. Controllers take Spring Security's `Authentication` as a parameter and read `Authentication#getName()`, so they depend on Spring Security, not on `auth`.
3. **Credential ownership**: the password hash is a column of `users`, owned by `user`. `auth` hashes passwords (it owns the `PasswordEncoder`) and passes only hashes to `UserService`, which hands them back for login as a `UserCredentials` DTO. `UserService` normalizes emails (trim, lower case), and a check constraint keeps the stored form normalized.
4. **Errors**: REST errors use RFC 9457 problem details. Feature exceptions extend Spring's `ErrorResponseException`, so the handler in `common` maps them without knowing any feature. Security failures (missing or invalid access token) are answered in the same format by an entry point in `auth`, because they happen in the filter chain, before Spring MVC.
5. **IDs**: tables use UUID primary keys, generated by Hibernate.

## Unresolved decisions

1. **STOMP broker strategy**: Spring's simple in-memory broker with RabbitMQ (AMQP) fanning events out to every instance, or a STOMP broker relay to RabbitMQ's STOMP plugin. The relay needs an extra dependency (Reactor Netty) and moves work between `realtime` and `messaging`.
2. **STOMP authentication**: how the client presents the access token on STOMP CONNECT (for example an `Authorization` native header) and how `realtime` validates it with the `JwtDecoder` from `auth`.
3. **Outbox relay and delivery semantics**: relay mechanism (e.g. polling), event serialization, exchange/queue topology, and idempotent consumers for at-least-once delivery.
4. **Presence storage**: per-instance memory breaks with more than one instance. The options are PostgreSQL, propagation through RabbitMQ, or a new store such as Redis (new dependency). Also: who may see whose presence.
5. **Message ordering**: how message order in a conversation is defined (per-conversation sequence number or timestamp).
6. **Boundary enforcement**: the rules are currently enforced by review and Java visibility. ArchUnit or Spring Modulith could check them in tests, but either adds a dependency.
7. **Refresh-token cleanup**: expired and revoked rows are never deleted. A scheduled cleanup must keep rotated tokens of live families, or reuse detection stops working for them.
