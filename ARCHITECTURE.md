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

**Event flow**: a feature publishes a domain event (a record in its `dto/` package) with Spring's `ApplicationEventPublisher`. `messaging` listens inside the publishing transaction and writes the matching integration event (a record in `messaging/dto/`) to the outbox, so the change and its event commit together. The relay publishes outbox rows to RabbitMQ, and consumers hand them to services or to `realtime`. Because features never depend on `messaging`, this flow creates no cycle (decision 15).

`realtime` also listens to domain events directly, after the publishing transaction has committed (`@TransactionalEventListener`), and delivers them to the clients connected to its instance: immediate, best-effort WebSocket delivery that doesn't depend on RabbitMQ. The first such event is `message.dto.MessageSentEvent` (decision 14). Consumers of the outbox's events never deliver them over WebSocket again.

## Configuration and test infrastructure

- Connection settings and secrets come from environment variables (`POSTGRES_*`, `RABBITMQ_*`, `JWT_*`) and are referenced in `application.yaml`. Committed configuration holds no credentials; the only committed key is the test-only JWT secret in `src/test/resources/application-test.yaml`. For local development, the values live in an uncommitted `.env` file that Docker Compose reads and the application imports (`spring.config.import`). Real environment variables take precedence.
- `compose.yaml` runs PostgreSQL and RabbitMQ for local development. The application runs on the host.
- Unit tests (`*Test`, `*Tests`) run with Surefire in `mvn test` and need no external services. Integration tests (`*IT`) run with Failsafe in `mvn verify` and get PostgreSQL and RabbitMQ from Testcontainers (`TestcontainersConfiguration`, wired with `@ServiceConnection`). The test profile turns the scheduled outbox relay off, so that nothing publishes or consumes in the background of other tests; tests of the relay run it themselves, and `BrokerOutageIT` turns it on.
- Actuator exposes only the health endpoint over HTTP. It shows each component's status, never its details.

## Decisions

1. **Token format**: access tokens are self-contained JWTs (HS256, 15 minutes), issued with Spring Security's `JwtEncoder` and validated by its OAuth2 resource server support (`spring-boot-starter-security-oauth2-resource-server`) with a `JwtDecoder` that checks signature, expiry, issuer and audience. Refresh tokens are opaque random strings, stored as SHA-256 hashes in PostgreSQL, single-use and rotated on every refresh. Each login starts a token family (`refresh_token_families`); reusing a rotated token revokes the family. Every refresh and logout locks the family row first, so the operations of one session are serialized.
2. **Caller identity**: the JWT subject is the user ID. Controllers take Spring Security's `Authentication` as a parameter and read `Authentication#getName()`, so they depend on Spring Security, not on `auth`.
3. **Credential ownership**: the password hash is a column of `users`, owned by `user`. `auth` hashes passwords (it owns the `PasswordEncoder`) and passes only hashes to `UserService`, which hands them back for login as a `UserCredentials` DTO. `UserService` normalizes emails (trim, lower case), and a check constraint keeps the stored form normalized.
4. **Errors**: REST errors use RFC 9457 problem details. Feature exceptions extend Spring's `ErrorResponseException`, so the handler in `common` maps them without knowing any feature. Security failures (missing or invalid access token) are answered in the same format by an entry point in `auth`, because they happen in the filter chain, before Spring MVC.
5. **IDs**: tables use UUID primary keys, generated by Hibernate. The exception is `outbox_events`, whose ID is the event ID: the application generates it, because the serialized payload contains it before the row is inserted.
6. **Direct-conversation uniqueness**: a direct conversation stores its two participants on its own row (`direct_user1_id < direct_user2_id`, in PostgreSQL's unsigned UUID order) under a unique constraint. A request that loses a creation race catches the violation and reads the winner's conversation in a new transaction.
7. **Group changes**: adding, removing, leaving, promoting, demoting, renaming and deleting lock the conversation row first (`SELECT ... FOR NO KEY UPDATE`), so changes to one conversation are serialized. Sending a message takes the same lock, in any conversation (decision 12). This keeps memberships unique without failed inserts, and lets the member limit and the "an active group has an administrator" rule rely on what they read: a count read after the lock can't be outdated by a concurrent change. New rules about a group's members or roles must take the same lock.
8. **Member limit**: at most 100 members per group, the creator included, defined once as `ConversationService.MAX_GROUP_MEMBERS` and checked by the service under the lock from decision 7. It isn't repeated as a database constraint, which would need a stored member count or a trigger. A request that would exceed it adds nobody (`409`).
9. **Group lifecycle**: deleting a group, or its last member leaving, removes its memberships and sets `conversations.deleted_at` in one transaction (soft delete). The row stays so that rows referring to the conversation, such as future messages, don't need `on delete cascade` or become orphans. Memberships are what grant access, so removing them makes a deleted group unreachable through the same membership checks every request already makes; queries also filter on `deleted_at`. Direct conversations can't be deleted (check constraint).
10. **Conversation-list pagination**: the order `updatedAt desc, id desc` (most recent activity first, decision 12) is total because the ID is unique. Besides page numbers (kept for compatibility), the list supports keyset pagination with an opaque cursor that encodes the last entry's `(updatedAt, id)`. Cursors avoid the shifts that inserts, deletes and moves cause in offset pagination. They don't provide a snapshot: each page reads the current data. The cursor encoding (`KeysetCursor`) and the page metadata live in `common`, because message history uses the same pagination.
11. **Message ordering**: a conversation's history is ordered by `created_at`, then by ID, newest first; the unique ID makes the order total and the same on every request. `created_at` is a timestamp, not a sequence number: the application's clock, truncated to microseconds, read after the send has taken the conversation lock (decision 12). Messages of one conversation are therefore stored one after the other, and with one clock that doesn't go back, a message that commits later has a later `created_at`: the history order is the commit order. That is a consequence of the lock, not a guarantee: with several application instances whose clocks differ, or a clock that is set back, a message that commits later can sort below one that was already visible, possibly within a page a client has already loaded. Messages stored in the same microsecond are ordered by their random IDs. A strict per-conversation sequence number isn't needed for storing and paging history, so there is none (see unresolved decision 9). History pages like the conversation list (decision 10); a cursor page compares `(created_at, id)` as one row value, so PostgreSQL starts its index scan at the cursor.
12. **Activity on a member's behalf**: other features check access to a conversation through `ConversationService`: `requireMembership` to read, `recordActivity` to write. `recordActivity` takes the conversation lock from decision 7 (checking membership before and again after it), moves the conversation's `updated_at` to now and returns that time, which the caller uses for what it stores (the message's `created_at`). It requires the caller's transaction (`MANDATORY`), so the activity time and the message commit or roll back together, and the lock lasts until the message is stored: leaving, removal and group deletion wait for it, and a send that comes after them is rejected. A message is therefore never stored after its sender lost access. Taking the conversation lock first, the same order as every group change, also rules out deadlocks between sends and group changes; a send that locked anything else first and then updated the conversation row could deadlock with a removal. The cost: sends to one conversation run one after the other, and wait for group changes in progress. `updated_at` is the conversation's activity time: the last change to it or its newest message. The conversation list is ordered by it. It has no index, so the frequent updates touch no index and can be HOT updates.
13. **STOMP authentication**: clients connect to `/ws` (plain WebSocket) and present the access token in the STOMP CONNECT frame as `Authorization: Bearer <token>`. The HTTP handshake is public (only `GET /ws`); `auth` ignores an `Authorization` header on it, so the CONNECT frame is the one place a token counts. `realtime.StompSecurityInterceptor`, on the client inbound channel, validates the token with Spring Security's `JwtAuthenticationProvider` and the `JwtDecoder` bean from `auth` (the rules REST uses), sets the resulting `JwtAuthenticationToken` as the connection's user, and removes the header from the frame. STOMP handlers take `Authentication` and read `getName()`, like controllers (decision 2). The interceptor denies by default: CONNECT, SUBSCRIBE to `/topic/conversations/{id}/messages` and `/user/queue/errors`, SEND to `/app/conversations/{id}/messages`, UNSUBSCRIBE, DISCONNECT and heartbeats; SUBSCRIBE and SEND re-validate the token, so a connection can't act after its token expired. Subscription authorization is decided by `ConversationService.requireMembership`, send authorization by `MessageService` (rule 3). Rejections are ERROR frames with problem-detail bodies, sent by the interceptor itself (frames are processed in order, decision 14, and Spring's ordering decorator swallows exceptions from interceptors); the connection is closed. Spring closes it right after the ERROR frame, and its session decorator discards frames still queued for a connection that is closing, which loses the ERROR frame when another frame to the same connection is being written at that moment; `realtime.FlushBeforeCloseSessionDecorator`, every connection's session, makes closing wait until the frames sent before have been written.
14. **Real-time delivery**: Spring's simple in-memory broker (`/topic`, `/queue`, user destinations under `/user`), so delivery reaches only clients connected to the same instance (unresolved decision 1). `MessageService.sendMessage`, the one path for REST and STOMP sends, reads the conversation's members under the conversation lock and publishes `MessageSentEvent` (the stored message and those members as recipients). `realtime.MessageBroadcaster` delivers it after the commit to `/topic/conversations/{id}/messages`, naming the recipients' STOMP sessions in an internal header; `realtime.RecipientSessionsInterceptor` on the client outbound channel drops the message for every other subscription, so a subscription that outlived its user's membership receives nothing. Delivery is at most once and best effort; the history is the record, and clients catch up from it after reconnecting. Frames from one connection are processed in order and messages to one connection are sent in publish order (`preserveReceiveOrder`, `preservePublishOrder`), so one client's messages are stored and delivered in the order it sent them. Errors of a STOMP send go to the sending connection on `/user/queue/errors` as problem details.
15. **Transactional outbox**: `messaging.OutboxWriter` turns `MessageSentEvent` into a `messaging.dto.MessageCreatedEvent` (version 1, IDs only, no content) and inserts it into `outbox_events` while `MessageService.sendMessage` is still in its transaction (`@EventListener`, `MANDATORY`): the message and its event commit together or not at all, and nothing in the transaction talks to RabbitMQ. `messaging.OutboxRelay` polls on its own scheduler thread (`outboxRelayScheduler`, so it neither waits for nor delays the WebSocket broker's tasks). Each batch is claimed in one short statement (`update ... where id in (select ... for update skip locked) returning *`) that counts the attempt and moves `next_attempt_at` past a lease; no lock or transaction is held while publishing. Events go to the durable topic exchange `chattywap.events` as persistent, mandatory messages with correlated publisher confirms; only an acknowledgement for a message that wasn't returned marks the event published. Nacks, returns, confirm timeouts and connection failures keep the event pending with `last_error` and a delay that doubles per attempt up to a maximum; events are retried indefinitely and never dropped. A batch ends before its lease: sending stops at the first failed send and once only the confirm timeout is left, and a failure is recorded only if the event's attempt count is still the claim's, so a relay whose lease expired can't move another relay's lease. The relay publishes on a connection of its own, so a broker that blocks publishers doesn't block consumers' acknowledgements. A lease that expires (the relay stopped mid-batch) makes the event due again, so a crash after the broker's confirmation but before the database update publishes it twice: delivery is at least once. The relay's times are PostgreSQL's `now()`, so instances agree on leases; events have no guaranteed order. Polling was chosen over CDC (no extra infrastructure) and over publishing after the commit from the request thread (which would lose events on a crash between commit and publish).
16. **Consumers and idempotency**: one durable quorum queue per consumer, bound to the routing keys it handles, with a per-consumer dead-letter queue behind the direct exchange `chattywap.events.dead-letter`. The container acknowledges after the listener returns (`acknowledge-mode: auto`) and never requeues a rejected message (`default-requeue-rejected: false`). Failures are retried in the listener with exponential backoff (`spring.rabbitmq.listener.simple.retry`), except invalid events, unreadable payloads and data integrity violations, which fail the same way every time (`MessagingConfig.isRetryable`); after that the message is rejected and the broker dead-letters it. The quorum queue's delivery limit dead-letters a message that keeps crashing the consumer before it can reject it. Dead-lettering is at least once (`x-dead-letter-strategy`, which needs `reject-publish` overflow), so a dead letter isn't lost if the dead-letter queue can't take it at once. Consumers deduplicate by event ID in `processed_events (consumer_name, event_id)`, inserted with `on conflict do nothing` in the same transaction as their effect, so a redelivery or a republished event changes nothing, and a crash between the two leaves neither. The example consumer (`MessageCountConsumer` → `MessageCountService`) counts events per conversation in `conversation_message_counts`, an effect whose duplicates would be visible; nothing in the API reads it. Processing is therefore effectively once per event in PostgreSQL, but delivery stays at least once; effects outside the database (push notifications, later) can't be made exactly once this way.

## Unresolved decisions

1. **STOMP broker strategy**: Spring's simple in-memory broker with RabbitMQ (AMQP) fanning events out to every instance, or a STOMP broker relay to RabbitMQ's STOMP plugin. The relay needs an extra dependency (Reactor Netty) and moves work between `realtime` and `messaging`. Until this is decided, the simple broker runs alone (decision 14), which supports a single application instance only.
2. **STOMP authentication**: resolved, see decision 13.
3. **Outbox relay and delivery semantics**: resolved, see decisions 15 and 16. Still open: retention of published outbox rows and of `processed_events` (both grow without bound; a cleanup must keep `processed_events` rows longer than any event can still be redelivered), and whether a future consumer needs events in order (the relay publishes them in no guaranteed order).
4. **Presence storage**: per-instance memory breaks with more than one instance. The options are PostgreSQL, propagation through RabbitMQ, or a new store such as Redis (new dependency). Also: who may see whose presence.
5. **Boundary enforcement**: the rules are currently enforced by review and Java visibility. ArchUnit or Spring Modulith could check them in tests, but either adds a dependency.
6. **Refresh-token cleanup**: expired and revoked rows are never deleted. A scheduled cleanup must keep rotated tokens of live families, or reuse detection stops working for them.
7. **Account deletion**: users who created or belong to a conversation, or sent a message, can't be deleted (`on delete restrict`). Account deletion has to decide what happens to their conversations, memberships and messages (remove, anonymize, hand over).
8. **Retention of deleted groups**: deleted groups are kept indefinitely, including their names and messages. Whether and when they are purged is a product decision that hasn't been made; a purge has to delete the messages explicitly (`on delete restrict`).
9. **Message sequencing for real-time delivery**: history order matches commit order only on one instance with a clock that doesn't go back (decision 11). The WebSocket milestone added no sequence number: clients subscribe first, then read the history down to the last message they have, and drop duplicates by ID; live messages from different senders can arrive in a different order than the history, so clients place them by `(createdAt, id)`. That is sound on the single instance the simple broker supports (decision 14). If clients need gap detection or a reliable "messages since" catch-up with several instances, the options are a per-conversation sequence number (cheap now: sends already hold the conversation lock, so it could be a counter on the conversation row), a `created_at` never lower than the conversation's previous activity, or an overlap window when catching up. Decide together with the broker strategy and the delivery semantics (unresolved decisions 1 and 3).
