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

## Unresolved decisions

1. **STOMP broker strategy**: Spring's simple in-memory broker with RabbitMQ (AMQP) fanning events out to every instance, or a STOMP broker relay to RabbitMQ's STOMP plugin. The relay needs an extra dependency (Reactor Netty) and moves work between `realtime` and `messaging`.
2. **Token format**: self-contained JWT (needs a JOSE/JWT library, such as the OAuth2 resource server starter) or opaque tokens stored in PostgreSQL. Also refresh tokens, and how the client presents the token on STOMP CONNECT.
3. **Caller identity**: how controllers and handlers get the current user without depending on `auth`. For example, `Authentication#getName()` returns the user ID, or a small principal type lives in `common`.
4. **Credential ownership**: password hashes on the `user` table, or in a table owned by `auth`.
5. **Outbox relay and delivery semantics**: relay mechanism (e.g. polling), event serialization, exchange/queue topology, and idempotent consumers for at-least-once delivery.
6. **Presence storage**: per-instance memory breaks with more than one instance. The options are PostgreSQL, propagation through RabbitMQ, or a new store such as Redis (new dependency). Also: who may see whose presence.
7. **IDs and ordering**: UUIDs or sequences, and how message order in a conversation is defined (per-conversation sequence number or timestamp).
8. **Boundary enforcement**: the rules are currently enforced by review and Java visibility. ArchUnit or Spring Modulith could check them in tests, but either adds a dependency.
9. **Local and test infrastructure**: no datasource or broker connection is configured, so the application and the existing `contextLoads` test fail at startup. The options are config pointing at locally running services, Spring Boot Docker Compose support, or Testcontainers. The last two add dependencies.
