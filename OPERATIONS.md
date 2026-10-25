# Operations

How to run Chattywap in production-like conditions, what it guarantees when its dependencies fail, how to watch it, and what to do when something goes wrong. Measured capacity and the load tests are in [loadtest/README.md](loadtest/README.md); the architecture in [ARCHITECTURE.md](ARCHITECTURE.md).

- [Configuration](#configuration)
- [Health and readiness](#health-and-readiness)
- [Metrics](#metrics)
- [Logs and tracing an operation](#logs-and-tracing-an-operation)
- [Database connections](#database-connections)
- [Resource limits](#resource-limits)
- [When a dependency fails](#when-a-dependency-fails)
- [Running several instances](#running-several-instances)
- [Runbook](#runbook)

## Configuration

Besides the variables in the README's [Configure the local environment](README.md#configure-the-local-environment):

| Variable | Default | Meaning |
|---|---|---|
| `MANAGEMENT_PORT` | `8081` | Port of the operational endpoints (health details, metrics). Must differ from the application port; if it doesn't, every `/actuator` path is refused. |
| `MANAGEMENT_ADDRESS` | `127.0.0.1` | Interface the management port listens on. The default only accepts connections from the same machine; in a deployment, set the internal interface (or `0.0.0.0` inside a container) and keep the port out of the load balancer and the internet. |
| `POSTGRES_POOL_SIZE` | `10` | Database connections per instance. See [Database connections](#database-connections). |
| `POSTGRES_CONNECTION_TIMEOUT_MS` | `5000` | How long a request waits for a free connection before it fails with `503`. |
| `POSTGRES_SOCKET_TIMEOUT` | `30` | Seconds a statement may wait for PostgreSQL's answer before its connection is given up. `0` waits forever. Raise it while a migration that runs longer is applied. |
| `SLOW_QUERY_THRESHOLD_MS` | `500` | Statements slower than this are logged (`org.hibernate.SQL_SLOW`, `INFO`). |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | none | `ecs` or `logstash` writes every log line as one JSON object (see [Logs](#logs-and-tracing-an-operation)). |
| `LOGGING_LEVEL_COM_ADONIS_CHATTYWAP` | `info` | `debug` adds a line per published and processed event and per request. |

Properties without an environment variable of their own can be set like any Spring property, for example `CHATTYWAP_REALTIME_HEARTBEAT=20s`:

| Property | Default | Meaning |
|---|---|---|
| `chattywap.http.max-request-body-size` | `64KB` | Larger request bodies get `413`. |
| `chattywap.logging.slow-request-threshold` | `1s` | Slower requests are logged at `WARN`. |
| `chattywap.messages.send-wait-timeout` | `5s` | How long a send waits for the sends to the same conversation in front of it before `503`. |
| `chattywap.realtime.heartbeat` | `10s` | STOMP heartbeat interval offered to clients; `0s` turns heartbeats off. |
| `server.tomcat.threads.max` | `200` | Request threads; also handle STOMP frames (see [Resource limits](#resource-limits)). |

Shutdown is graceful (`server.shutdown: graceful`): on `SIGTERM` the instance reports itself not ready, finishes requests in progress for up to 30 seconds (`spring.lifecycle.timeout-per-shutdown-phase`), closes WebSocket connections (clients reconnect elsewhere), lets the outbox relay finish its batch (at most the confirm timeout, 10 seconds) and the consumer its message, passes live deliveries still waiting on to the other instances (at most 2 seconds), then stops; RabbitMQ deletes its fan-out queue. Give the process at least 45 seconds before killing it (for example `terminationGracePeriodSeconds`); killed earlier, nothing stored is lost, but events may be published twice, requests in progress fail, and clients on other instances miss the live deliveries that were still waiting.

## Health and readiness

| Endpoint | Port | Meaning | Use |
|---|---|---|---|
| `GET /livez` | application | The process works. Only the application's own state, never a dependency. | Liveness probe: restart the instance when it fails. |
| `GET /readyz` | application | The instance takes traffic: it has started, and isn't shutting down. | Readiness probe / load-balancer health check. |
| `GET /actuator/health` | management | Overall status, with every component and its details: `db` (PostgreSQL), `rabbit` (RabbitMQ), disk space, liveness and readiness state. `503` when any is `DOWN`. | Monitoring, alerting, diagnosis. |
| `GET /actuator/health/{component}` | management | One component, for example `/actuator/health/db`. | Diagnosis. |

`/livez` and `/readyz` are public and answer `{"status":"UP"}` (`200`) or `{"status":"DOWN"}`/`OUT_OF_SERVICE` (`503`), nothing else. Everything under `/actuator` exists only on the management port and needs no token there: access to that port is the authorization, so it must not be reachable from outside. On the application port, `/actuator/...` is refused (`401`, or `404` with a token). Health details contain versions, the database product and disk space; never credentials or connection URLs.

**What dependency failures do:**

| Failure | Liveness | Readiness | `/actuator/health` |
|---|---|---|---|
| RabbitMQ down | `UP` | `UP` | `DOWN` (`rabbit`) |
| PostgreSQL down | `UP` | `UP` | `DOWN` (`db`) |
| Shutting down | `UP` | `OUT_OF_SERVICE` | `OUT_OF_SERVICE` |

Readiness deliberately leaves out both dependencies. Every instance shares them, so taking instances out of the load balancer when one fails helps no request; and the database check needs a pooled connection, so a pool that is exhausted under load would take every instance out at once and turn overload into an outage. Without RabbitMQ, the application keeps storing messages and delivering them to the clients of the instance that stored them (events wait in the outbox); without PostgreSQL, requests fail fast with `503` (see [When a dependency fails](#when-a-dependency-fails)). Alert on `/actuator/health` instead.

**Cost of the checks:** the probes on the application port read in-memory state only. `/actuator/health` borrows a pooled connection for one `isValid()` round trip and asks the cached RabbitMQ connection for its server properties; while RabbitMQ is unreachable, that check waits up to the connection timeout (5 seconds). Scraping it every 10–30 seconds per instance is negligible.

## Metrics

All metrics are in Prometheus format at `GET /actuator/prometheus` on the management port (`/actuator/metrics/{name}` shows one as JSON). Every series is tagged `application="chattywap"`. Tags have small, fixed sets of values: no user, conversation, message or event IDs, which belong in the logs.

**HTTP and the JVM**

| Metric | Helps diagnose |
|---|---|
| `http_server_requests_seconds` (`_count`, `_sum`, `_bucket`; tags `method`, `uri` template, `status`, `outcome`) | Request rate, latency percentiles (buckets from 10 ms to 5 s), error rate (`status=~"5.."`): `503`s are the database or a busy conversation, `500`s bugs. |
| `tomcat_threads_busy_threads`, `tomcat_threads_config_max_threads` | Request threads in use. Near the maximum: requests (and STOMP frames, see below) are queuing. |
| `jvm_memory_used_bytes{area="heap"}`, `jvm_gc_pause_seconds`, `jvm_threads_live_threads`, `process_cpu_usage` | Memory pressure, GC pauses, CPU saturation. |
| `executor_*` (tag `name`) | The schedulers and the WebSocket outbound pool: `executor_queued_tasks{name="clientOutboundChannelExecutor"}` grows when deliveries to clients fall behind. |

**PostgreSQL**

| Metric | Helps diagnose |
|---|---|
| `hikaricp_connections_active`, `_idle`, `_pending`, `_max` (tag `pool="chattywap"`) | Pool saturation: `pending > 0` for long means requests wait for connections. |
| `hikaricp_connections_timeout_total` | Requests that got no connection within `POSTGRES_CONNECTION_TIMEOUT_MS` (answered `503`). |
| `hikaricp_connections_acquire_seconds` (buckets) | How long requests wait for a connection. |
| `hikaricp_connections_usage_seconds` | How long connections are held: grows with slow statements or lock waits. |
| Log `org.hibernate.SQL_SLOW` | Statements slower than `SLOW_QUERY_THRESHOLD_MS`, with their SQL. For the whole picture, enable `pg_stat_statements` and `log_min_duration_statement` on the server. |

**WebSocket and STOMP**

| Metric | Helps diagnose |
|---|---|
| `chattywap_websocket_sessions` | Open connections on this instance. |
| `chattywap_websocket_sessions_closed_total{reason}` | Connections the server closed because of the client: `send-limit-exceeded` (a slow client didn't take its messages in time), `no-connect` (no `CONNECT` within a minute), `transport-error`. |
| `chattywap_websocket_disconnects_total{close_status}` | Every connection that ended. `normal`, `going-away` are ordinary; a rise in `no-close-frame` means networks dropping connections, `protocol-error` heartbeat timeouts and rejected frames. |
| `chattywap_stomp_frames_rejected_total{command,status}` | Rejected frames: `CONNECT` + `401` are failed authentications, `SUBSCRIBE` + `404` refused subscriptions, `503` a database outage. |
| `chattywap_stomp_sends_total{outcome}` | STOMP `SEND`s: `stored`, `invalid`, `rejected`, `unavailable` (database), `error`. |
| `chattywap_websocket_broadcasts_total{type,origin,outcome}` | Deliveries of messages and receipt changes to this instance's subscribers, stored on this instance (`origin="local"`) or another (`remote`): `sent`, `none-connected` (no recipient connected here, common with several instances), `failed` (logged with the message's IDs). |

**Live delivery between instances** (see [Running several instances](#running-several-instances))

| Metric | Helps diagnose |
|---|---|
| `chattywap_fanout_published_total` | Messages and receipt changes this instance passed on to the others. Grows with every one stored here, also with a single instance. |
| `chattywap_fanout_dropped_total{reason}` | Live deliveries not passed on: `unavailable` (RabbitMQ unreachable or refusing; after a failure, everything for one second), `buffer-full` (10,000 waiting: a broker that blocks publishers), `error` (a bug, logged). Clients on other instances miss them live and find them in the history. |
| `chattywap_fanout_buffered` | Live deliveries waiting to be published. Normally 0; grows while RabbitMQ blocks publishers. |
| `chattywap_fanout_received_total{outcome}` | What came from RabbitMQ: `delivered` (from another instance, handed to the WebSocket broker), `own` (this instance's, skipped), `invalid` (unreadable: instances of incompatible versions, logged). Each instance's `own` follows its `published`; if it falls behind, the instance doesn't consume its queue. |

**Outbox and RabbitMQ**

| Metric | Helps diagnose |
|---|---|
| `chattywap_outbox_pending` | Events not published yet. Normally near 0; grows while RabbitMQ is unreachable. |
| `chattywap_outbox_oldest_pending_age_seconds` | How far behind publishing is. The best single alert for the asynchronous path. |
| `chattywap_outbox_published_total`, `chattywap_outbox_failed_total{reason}`, `chattywap_outbox_retried_total` | Publication rate; failures by `nack`, `returned` (no queue bound), `timeout`, `unreachable`, `send-error`, `out-of-time`. |
| `chattywap_events_consumed_total{consumer,outcome}` | Events processed, or skipped as `duplicate`. |
| `chattywap_events_consumer_failures_total{consumer}` | Failed processing attempts, retries included. |
| `chattywap_events_dead_lettered_total{queue}` | Messages moved to a dead-letter queue. Any increase needs a look. |
| RabbitMQ's own metrics, port 15692 (`rabbitmq_prometheus`, on by default in the image) | Queue depths, including `chattywap.message-counts.dead-letter` and the instances' `chattywap.realtime.<instance ID>` (normally near 0: one that grows belongs to an instance that can't keep up), consumers, unacknowledged messages, memory and disk alarms. |

The two outbox gauges query PostgreSQL when they are read (an index-only count over pending events). With a scrape every 15 seconds that is negligible, also with a backlog of 100,000 events (see [loadtest/README.md](loadtest/README.md)).

Suggested alerts: `/actuator/health` not `UP` for 2 minutes; `rate(http_server_requests_seconds_count{status=~"5.."}[5m])` above 1 % of requests; `hikaricp_connections_pending > 0` for 5 minutes; `increase(hikaricp_connections_timeout_total[5m]) > 0`; `chattywap_outbox_oldest_pending_age_seconds > 300`; `increase(chattywap_events_dead_lettered_total[1h]) > 0`; with several instances, `increase(chattywap_fanout_dropped_total[5m]) > 0` while RabbitMQ is up, and `increase(chattywap_fanout_received_total{outcome="invalid"}[5m]) > 0`.

## Logs and tracing an operation

Logs go to standard output. By default each line is plain text with the request ID in brackets after the thread name; with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (or `logstash`) each line is a JSON object, and the context below appears as fields of its own.

| Field | Set for | Value |
|---|---|---|
| `requestId` | Each HTTP request; each STOMP frame; while a consumer processes an event, the request that wrote it | A UUID, or the `X-Request-Id` a proxy sent (if it looks like an ID: up to 64 letters, digits, `.`, `_`, `:`, `-`) |
| `userId` | Authenticated HTTP requests and STOMP frames | The caller's user ID |
| `eventId` | While a consumer processes an event, or dead-letters it | The event ID (AMQP message ID) |

Every response carries its request ID in the `X-Request-Id` header, so a client can report it. Logs never contain passwords, tokens, `Authorization` headers, message content or event bodies: they hold IDs, statuses and error causes.

**What is logged:** requests that fail with a server error or take longer than `chattywap.logging.slow-request-threshold` (`WARN`: method, path without query string, status, duration); unexpected errors with their stack trace (`ERROR`); database unavailability (`WARN`, cause only, no stack trace); failed event publications (`WARN`, one line per relay batch and reason: during a one-minute RabbitMQ outage, 53 lines instead of the 4,000 one per attempt would be) and dead-lettered messages (`ERROR`); slow SQL statements (`INFO`). Successful requests are logged at `DEBUG` only, so the log grows with problems, not traffic. For an access log of every request, set the filter's logger to `DEBUG`: `SPRING_APPLICATION_JSON='{"logging.level.com.adonis.chattywap.common.RequestLoggingFilter":"debug"}'` (an environment variable like `LOGGING_LEVEL_...` can't name it, because Spring lower-cases logger names given that way).

**Following a message from its request to its consumers:**

1. The client reports the request ID (`X-Request-Id`), or you find the request's `WARN`/`ERROR` line. Search the logs for that `requestId`: the request, its errors, and, later, the consumer's lines for its events.
2. The outbox records which request wrote each event: `select id, published_at, attempts, last_error from outbox_events where correlation_id = '<requestId>';` (or by message: `where payload::jsonb ->> 'messageId' = '<messageId>'`).
3. The relay publishes the event with the request ID as AMQP `correlation_id` and the event ID as `message_id`. A failed attempt is logged with the event ID.
4. The consumer logs with `requestId` and `eventId` in its context; whether it processed the event: `select * from processed_events where event_id = '<eventId>';`. A dead-lettered event has an `ERROR` line with its ID and cause; the message itself is in the dead-letter queue.

STOMP frames get request IDs the same way; a message sent through STOMP can be followed from its event's `correlation_id`.

## Database connections

Each instance keeps at most `POSTGRES_POOL_SIZE` (10) connections. The total must fit PostgreSQL's `max_connections` (100 by default), minus `superuser_reserved_connections` (3) and whatever else connects (migrations run in the application's pool; monitoring, backups, administrators):

```
instances × POSTGRES_POOL_SIZE + other clients ≤ max_connections − superuser_reserved_connections
```

With the defaults, at most 9 instances fit beside a few other clients; during a rolling deployment, count the old and new instances together. More connections only help while PostgreSQL has CPU to spare: in the load test, 10 connections served about 870 requests per second before the pool was the limit, 20 about 1,160 and 40 about 1,320, while PostgreSQL went from 2 to 3.5 cores ([loadtest/README.md](loadtest/README.md)). Measure before raising it, and size against the database's cores (2–4 connections per core is a common starting point), not against request threads. A connection pooler (PgBouncer in transaction mode) is the next step when many instances are needed.

Timeouts: a request waits at most `POSTGRES_CONNECTION_TIMEOUT_MS` (5 s) for a connection and then fails with `503`; a statement waits at most `POSTGRES_SOCKET_TIMEOUT` (30 s) for an answer before its connection is given up. Without these, a database that stops answering keeps request threads waiting 30 seconds (pool default) or forever (driver default), as measured before they were set.

## Resource limits

| Resource | Limit | When it is reached |
|---|---|---|
| Request body | 64 KB (`chattywap.http.max-request-body-size`) | `413`; nothing beyond the limit is read |
| Message content | 4,000 characters | `400` |
| Page size | 100 (`spring.data.web.pageable.max-page-size`) | Reduced to 100 |
| STOMP frame | 64 KB (Spring's default message size limit) | Connection closed |
| Request threads | 200 (`server.tomcat.threads.max`), queue 100 | Requests wait in the accept queue, then are refused |
| STOMP inbound frames | Handled one at a time per connection, on a request thread | The server stops reading the connection until the frame is handled (TCP backpressure); nothing queues in memory |
| Outbound messages per connection | Spring's defaults: 10 s send time, 512 KB buffer | The slow connection is closed (`send-limit-exceeded`) |
| Connections without `CONNECT` | 60 s | Closed (`no-connect`) |
| Silent connections that asked for heartbeats | 3 × the agreed interval (30 s by default) | Closed |
| Sends waiting for one conversation, per instance | `chattywap.messages.send-wait-timeout` (5 s) | `503`, nothing stored |
| Group members | 100 | `409` |
| Outbox relay | One thread, batches of 100, one connection | Backlog grows; see the runbook |
| Consumer | Spring's default listener concurrency (1), prefetch 10, 4 attempts | Queue grows; failures dead-lettered |
| Live deliveries waiting to be passed on to the other instances | 10,000 per instance, one publishing thread and connection | Dropped (`chattywap_fanout_dropped_total{reason="buffer-full"}`); this instance's own clients are unaffected |
| Live deliveries waiting in an instance's fan-out queue | 10,000 (RabbitMQ, `x-max-length`), one consumer, prefetch 250 | RabbitMQ drops the oldest |

STOMP frames are handled on the request threads, one at a time per connection, so a client that sends faster than its frames are handled is slowed down by TCP instead of filling the server's memory (see [ARCHITECTURE.md](ARCHITECTURE.md), decision 24). Many connections sending at once compete with REST requests for the same threads and database connections, like REST requests compete with each other.

There is no per-user rate limit. Login and registration hash passwords with BCrypt (about 0.1 s of CPU each), so a flood of them can saturate CPU; put rate limiting in front of `/api/v1/auth/*` at the load balancer or gateway, where it sees client addresses.

## When a dependency fails

What the implementation guarantees; nothing here is exactly-once, and nothing makes an instance available without PostgreSQL.

**PostgreSQL unavailable or not answering**

- Requests that need the database fail with `503` (`The service is temporarily unavailable. Try again later.`) within `POSTGRES_CONNECTION_TIMEOUT_MS` (5 s), or `POSTGRES_SOCKET_TIMEOUT` (30 s) for a statement already sent; STOMP sends get the same `503` on `/user/queue/errors`. Never a success that wasn't stored: the transaction rolls back, and no message, event or receipt is kept.
- Request threads aren't held longer than those timeouts, so the instance stays responsive (`/livez`, `/readyz` keep answering) and recovers by itself: the pool replaces broken connections once the database answers. Nothing needs to be cleaned up. Covered by `DatabaseOutageIT`.
- The outbox relay's runs fail and are retried every poll interval; consumers' transactions fail and are retried, then dead-lettered after 4 attempts (replay them, see the runbook).
- An application that starts without PostgreSQL fails at startup (Flyway), and is restarted by the orchestrator.

**RabbitMQ unavailable**

- Sending (REST and STOMP), the history, receipts, and WebSocket delivery to the clients connected to the instance that stored the message keep working: nothing in a request talks to RabbitMQ.
- Clients connected to other instances don't receive those messages and receipt changes live. Each instance drops the live deliveries it can't pass on, and after a failure everything for the next second without trying, so there is one connection attempt per second, not one per message. It logs one `WARN` when passing them on starts failing and one `INFO`, with the number dropped, when it works again; `chattywap_fanout_dropped_total{reason="unavailable"}` counts them. Nothing is delivered late: once RabbitMQ is back, every instance declares its queue again when its consumer reconnects (within about 5 seconds), and live delivery between instances resumes with the next message. What was dropped is in the history, and clients see the gap in the sequence numbers. Covered by `MultiInstanceDeliveryIT`.
- A RabbitMQ that blocks publishers (memory or disk alarm) holds up the publishing thread: up to 10,000 live deliveries per instance wait in memory and are passed on late when it unblocks, more are dropped (`buffer-full`). Requests and the instance's own clients aren't affected.
- Events stay in the outbox, pending, and are retried with growing delays (1 s, doubled per attempt, at most 5 min). The relay tries one connection per run, with a 5 s connection timeout, so there is no tight retry loop. `chattywap_outbox_pending` and `chattywap_outbox_oldest_pending_age_seconds` show the backlog. Each attempt that fails is one row update, and the failures of a batch are one log line per reason. Once RabbitMQ is back, events are published as their next attempt comes due (at most 5 minutes later; see the runbook to hurry them), at up to about 500 per second; the example consumer processes about 100 per second ([loadtest/README.md](loadtest/README.md#outbox-throughput)). Covered by `BrokerOutageIT`.

**A consumer failing**

- Transient failures are retried in the listener (4 attempts, 1–4 s apart); invalid events and data the database rejects are dead-lettered at once; a message that keeps crashing the consumer is dead-lettered by the quorum queue after 10 deliveries. A poison message never blocks the queue for longer than its retries.
- Redelivery and republishing are harmless: consumers record processed event IDs in the same transaction as their effect.

**WebSocket connections dropping**

- Delivery is at most once and never counts as delivered or read: receipts come only from the client's own acknowledgements. A client that reconnects reads what it missed from the history and the receipts endpoint.
- A failed broadcast is logged and counted; the stored message and the request that stored it are unaffected.

**Shutdown and restart**

- Requests in progress finish (graceful shutdown); WebSocket clients are disconnected with `going-away` and reconnect, to any instance.
- An instance's fan-out queue lives as long as its connection to RabbitMQ: RabbitMQ deletes it, with any live deliveries in it, when the instance stops, and a restarted instance gets a new one. Instances never share or take over each other's queues.
- Pending outbox events survive restarts in PostgreSQL. An event is marked published only after RabbitMQ confirmed it; a restart between the confirmation and the record publishes it again (at least once).
- A consumer interrupted before acknowledging gets the message redelivered.

## Running several instances

Several instances can run behind a load balancer, with these properties:

| State | Where it lives | With several instances |
|---|---|---|
| Users, conversations, messages, receipts, refresh tokens | PostgreSQL | Shared. Invariants (one direct conversation per pair, membership rules, sequence numbers, receipt positions) are enforced by constraints and row locks, not by memory. |
| Access tokens | Stateless JWTs | Valid on every instance; no session affinity needed for REST. |
| Outbox relay | PostgreSQL leases | Every instance may run it; `for update skip locked` and leases keep relays from claiming the same event at once and from skipping events (`OutboxRelayIT`). |
| Send gate | Memory, per instance | An optimization only: the conversation's row lock still orders sends across instances. |
| WebSocket sessions and subscriptions | Memory, per instance (Spring's simple broker) | Each instance's own; live deliveries reach the other instances through RabbitMQ. See below. |

**Live delivery across instances.** A message or receipt change stored through one instance reaches its recipients wherever they are connected: the instance that stored it delivers it to its own clients directly, and passes it on through the fanout exchange `chattywap.realtime` to every other instance, which delivers it to its clients (ARCHITECTURE.md, decision 26). Clients can connect to any instance, and no sticky sessions are needed.

- **Same rules as on one instance.** Only the members when the message was stored receive it, decided once, on the instance that stored it; a subscription that outlived its membership receives nothing, on any instance. Delivery is at most once and best effort; the history and the receipts endpoint are the record.
- **Never twice.** Each instance receives its own events back from RabbitMQ and skips them, so no connection gets an event twice.
- **Order.** Events stored through one instance reach every connection in the order they were committed there, so the messages one connection sends arrive everywhere in the order sent. Events stored through different instances interleave in any order, as messages from different senders always could; clients order by sequence number or `(createdAt, id)`. `createdAt` order can differ from commit order if the instances' clocks differ (ARCHITECTURE.md, decision 11); sequence numbers can't.
- **Latency.** Clients on other instances get an event one hop through RabbitMQ later than the instance's own clients: in the load test, 3 ms later at the median and 6 ms at p95, all on one machine ([loadtest/README.md](loadtest/README.md#websocket-delivery-across-two-instances)).
- **When RabbitMQ is down**, each instance delivers only to its own clients; see [When a dependency fails](#when-a-dependency-fails).
- **What it costs.** Every instance receives every message and receipt change, its own included: with N instances, each one is published once and delivered to N queues. Each instance has one more RabbitMQ connection (`chattywap-realtime-fanout`) and one queue, `chattywap.realtime.<instance ID>` (non-durable, exclusive, auto-delete), which exists while the instance is connected. This suits a handful of instances; for many more, see ARCHITECTURE.md, decision 26.
- **Message content passes through RabbitMQ**, transiently, unlike the outbox's events, which carry only IDs. Restrict access to RabbitMQ (users, permissions, network) as you would access to the database: whoever can publish to `chattywap.realtime` can deliver to clients.
- **Rolling deployments.** Old and new instances exchange live deliveries while both run. Changes to the delivered JSON must add fields only; an event an instance can't read is dropped, logged and counted (`chattywap_fanout_received_total{outcome="invalid"}`).

## Runbook

### Requests are slow or fail with 503: database connections exhausted

1. Check `hikaricp_connections_pending` and `hikaricp_connections_active` against `_max`, and `hikaricp_connections_timeout_total`. Pending above 0 with active at max: every connection is busy.
2. Find out what holds them. `hikaricp_connections_usage_seconds` rising means connections are held longer; then, on the database:
   ```sql
   select pid, state, wait_event_type, wait_event, now() - xact_start as in_transaction, left(query, 120)
   from pg_stat_activity where datname = current_database() and state <> 'idle' order by xact_start;
   ```
   Many sessions waiting on `Lock` for `conversations` rows: one conversation is receiving more messages than it can store one after the other; the send gate limits that to two connections per instance and conversation, and the excess fails with `503` after 5 s. Many sessions waiting there means many instances, or a long-running group change holding the lock. Sessions `idle in transaction` for long: a bug holding a transaction open; the stack of a thread dump (`jcmd <pid> Thread.print`) shows where.
3. `org.hibernate.SQL_SLOW` lines and `pg_stat_statements` show slow statements. Expensive by design: page-number history pages deep into a long conversation (`?page=` with a large offset, cost grows with the offset; clients should use `nextCursor`), and unread counts of members without a read position in very long conversations.
4. If PostgreSQL has CPU to spare and the pool is the limit under normal load, raise `POSTGRES_POOL_SIZE` within the [connection budget](#database-connections). If PostgreSQL's CPU is saturated, more connections make it worse.

### The outbox backlog grows

1. `chattywap_outbox_pending` and `chattywap_outbox_oldest_pending_age_seconds` rise. Check `chattywap_outbox_failed_total` by `reason` and the relay's `WARN` lines (`Publishing N outbox event(s) failed (reason) ...`, one per batch and reason):
   - `unreachable` / `send-error` / `timeout`: RabbitMQ is down, unreachable or blocking publishers (memory or disk alarm: `docker compose exec rabbitmq rabbitmq-diagnostics alarms`). Events are kept and published once it recovers; nothing to do in the application.
   - `returned`: no queue is bound to the routing key (a binding was removed). Restarting an instance declares the bindings again.
   - `nack`: the broker refused the events (queue limits, a quorum queue without a majority).
   - No failures but a growing backlog: the relay doesn't keep up or isn't running. Check that `chattywap.messaging.outbox.relay-enabled` is true on at least one instance and that its log shows no `Outbox relay run failed` (database errors).
2. Pending events, oldest first, with their last error:
   ```sql
   select id, event_type, attempts, next_attempt_at, last_error, correlation_id, created_at
   from outbox_events where published_at is null order by created_at limit 50;
   ```
3. After a long outage, events wait up to 5 minutes (the longest retry delay) before their next attempt. To publish them now: `update outbox_events set next_attempt_at = now() where published_at is null;`

### Consumers fail or dead-letter events

1. `chattywap_events_consumer_failures_total` rises; `chattywap_events_dead_lettered_total{queue}` counts what was given up. The consumer's `WARN` lines (`Processing message ... failed`) and the `ERROR` line (`Dead-lettering message ...`) carry the event ID and the cause, with the original request's ID in the context.
2. Inspect the dead letters: management UI → *Queues and Streams* → `chattywap.message-counts.dead-letter` → *Get messages*. `x-first-death-reason` is `rejected` (the consumer gave up) or `delivery_limit` (it crashed 10 times); the message ID is the event ID.
3. Fix the cause, then replay through the outbox, which keeps every event: `update outbox_events set published_at = null, next_attempt_at = now() where id = '<eventId>';`. Consumers skip events they already processed. Purge the dead letters afterwards.

### PostgreSQL or RabbitMQ is down

See [When a dependency fails](#when-a-dependency-fails). In short: PostgreSQL down means `503`s for nearly everything and recovery without intervention once it is back; RabbitMQ down means a growing outbox backlog that drains by itself, and live delivery only to clients on the instance that stored a message until it is back. Keep instances running and in the load balancer; restarting them doesn't help.

### Real-time delivery seems to miss messages

1. Several instances, and the missed messages were stored through another instance than the client's? Check the way between them ([above](#running-several-instances)):
   - On the storing instance, `chattywap_fanout_dropped_total` by `reason`, and its `WARN` line `Publishing live deliveries to the other instances failed`: `unavailable` means RabbitMQ was unreachable or refused them, `buffer-full` that it blocked publishers (`docker compose exec rabbitmq rabbitmq-diagnostics alarms`).
   - On the client's instance, `chattywap_fanout_received_total{outcome="delivered"}` should rise with the other instances' traffic. If it doesn't, its queue isn't consumed: `docker compose exec rabbitmq rabbitmqctl list_queues name consumers messages` should show one `chattywap.realtime.<instance ID>` per running instance, each with one consumer. A consumer that doesn't come back after an outage shows as `SimpleMessageListenerContainer` warnings in that instance's log; restarting the instance gives it a new queue.
   - `chattywap_fanout_received_total{outcome="invalid"}`: instances of incompatible versions during a deployment.
2. `chattywap_websocket_sessions_closed_total{reason="send-limit-exceeded"}` rising: clients too slow to take their messages (poor networks); they reconnect and catch up from the history.
3. `chattywap_websocket_broadcasts_total{outcome="failed"}`: delivery failed after the commit; the log line names the message.
4. The history is the record. A message in the history but not delivered live was sent while the client wasn't subscribed, or while RabbitMQ couldn't pass it on to the client's instance.
