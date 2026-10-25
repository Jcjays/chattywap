# Load tests

Repeatable load and stress tests with [k6](https://k6.io), run in Docker against the application on your machine, with PostgreSQL and RabbitMQ from a Compose project of their own. Nothing here is meant for production: the scripts create users, send messages and flood connections. Results from one run are at the end, with what they do and don't show.

## What is here

| File | Purpose |
|---|---|
| `loadtest.env.example` | Settings for the load-test environment: its own database and ports, a long access-token lifetime. Copy to `loadtest/.env`. |
| `seed.sql` | Representative data, generated in PostgreSQL: users, direct and group conversations, deep histories, read positions, published outbox history. Every ID is derived from a name, so the scripts compute IDs instead of looking them up. |
| `k6/rest-mix.js` | A mix of authenticated REST actions at a fixed rate: conversation lists, history pages and scrolling back by cursor, sending, unread counts, reading, receipts. |
| `k6/send-contention.js` | Concurrent sends to one conversation (`MODE=same`) or to different ones (`MODE=spread`), with unrelated history reads alongside. |
| `k6/long-history.js` | Members opening the five conversations with 200,000 messages: the first history page by page number, or a deeper one (`PAGE`). |
| `k6/websocket.js` | Many STOMP connections subscribed to their conversations, messages sent through REST, delivery time measured at every subscriber and deliveries counted against those expected. Optionally spread over several instances. |
| `k6/stomp-flood.js` | Abuse test: one connection sends as many large messages as it can. Shows whether the server buffers what it can't handle yet. |
| `outbox-backlog.sql`, `outbox-backlog.sh` | A backlog of pending outbox events, and how fast the relay and the consumer work through it. |
| `run-k6.sh` | Runs a script in the `grafana/k6` container; the summary goes to `loadtest/results/`. |
| `sample-metrics.sh` | One line of metrics every few seconds while a test runs: application CPU and heap, connection pool, request threads, WebSocket sessions, outbox backlog, and the containers' CPU and memory. |

Every script takes its load from environment variables (`-e NAME=value`): rate or concurrency, duration, number of users. The defaults are a modest load; raise them step by step.

## Setting up

Needs Docker and a JDK; k6 runs in a container (`grafana/k6:1.3.0`), so it needn't be installed.

1. Settings. Copy the example and set the passwords and a JWT secret (`openssl rand -base64 32`):

   ```bash
   cp loadtest/loadtest.env.example loadtest/.env
   ```

2. PostgreSQL and RabbitMQ, as a separate Compose project with their own volumes and ports (55432, 55672, 55673), so the development data is never touched:

   ```bash
   docker compose -p chattywap-loadtest --env-file loadtest/.env up -d --wait
   ```

3. The application, built as a jar and started from `loadtest/`, where it reads `loadtest/.env`. Use a fixed heap, so that runs are comparable:

   ```bash
   ./mvnw -DskipTests package
   cd loadtest && java -Xms512m -Xmx1g -jar ../target/chattywap-0.0.1-SNAPSHOT.jar
   ```

   For several instances, start more from `loadtest/` with other ports, for example `SERVER_PORT=8082 MANAGEMENT_PORT=8083 java -Xms512m -Xmx1g -jar ../target/chattywap-0.0.1-SNAPSHOT.jar`. They share the database and RabbitMQ, and pass live deliveries on to each other through RabbitMQ.

4. Once it has started (Flyway has created the schema), the data, in about a minute and a half:

   ```bash
   docker compose -p chattywap-loadtest --env-file loadtest/.env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < loadtest/seed.sql
   ```

   The defaults create 2,000 users (password `loadtest-password`), 16,200 conversations (16,000 direct, 200 groups of 10 to 100 members), 43,000 memberships and 2.1 million messages: most conversations have 60 to 500 messages, five groups have 200,000 each and the "hot" group (users 1–100) has 50,000. Users 1–20 are "power users" with over 300 conversations each. 70 % of memberships have a read position close to the newest message; the rest have none, the most expensive case for unread counts. Change the sizes with psql variables, for example `-v users=10000`; the k6 scripts take `SEED_USERS` and `SEED_GROUPS` to match.

To start again from scratch: `docker compose -p chattywap-loadtest --env-file loadtest/.env down -v`.

## Running

From the project directory, with the application running. Next to each test, record the metrics:

```bash
mkdir -p loadtest/results
sh loadtest/sample-metrics.sh > loadtest/results/metrics-$(date +%H%M%S).txt &
sh loadtest/run-k6.sh rest-mix.js -e RATE=100 -e DURATION=2m
kill %1
```

| Test | Command | Parameters |
|---|---|---|
| REST mix | `sh loadtest/run-k6.sh rest-mix.js -e RATE=200` | `RATE` user actions/s (50), `DURATION` (2m), `USERS` acting users (200), `MAX_VUS` (200) |
| Sends to one conversation | `sh loadtest/run-k6.sh send-contention.js -e MODE=same` | `MODE` `same` or `spread`, `SENDERS` (50, at most 100), `READ_RATE` unrelated reads/s (20), `DURATION` (1m) |
| Opening long conversations | `sh loadtest/run-k6.sh long-history.js -e RATE=100` | `RATE` (50), `PAGE` (0), `DURATION` (1m) |
| WebSocket delivery | `sh loadtest/run-k6.sh websocket.js -e CONNECTIONS=200` | `CONNECTIONS` (200), `SEND_RATE` messages/s (10), `DURATION` (1m), `BASE_URLS` several instances, comma-separated, as seen from the container (`http://host.docker.internal:8080,http://host.docker.internal:8082`): connections and sends are spread over them, and `stomp_delivery_latency_other_instance` measures the deliveries that crossed RabbitMQ |
| STOMP flood | `sh loadtest/run-k6.sh stomp-flood.js -e FRAMES=20000` | `FRAMES` (20000), `SIZE` characters (4000), `WAIT` (120s) |
| Outbox backlog | `sh loadtest/outbox-backlog.sh 50000` | events to add (50000), `INTERVAL` seconds between samples (5) |

Start with a modest load, note throughput, latency percentiles, error rate and resources, and raise the load until a resource saturates: that resource is the first bottleneck. A run whose own k6 container is short of CPU measures k6, not the server; keep an eye on it (`docker stats`).

To grow an outbox backlog the realistic way, stop RabbitMQ (`docker compose -p chattywap-loadtest --env-file loadtest/.env stop rabbitmq`), run `rest-mix.js` for a while (sending keeps working), start RabbitMQ again and run `outbox-backlog.sh 0` to watch the backlog drain.

## Results

Measured on 2026-10-24, before and after Milestone 9's changes ("baseline" is the code before them), with the same data, settings and JVM flags. Each step ran for one minute unless stated otherwise.

**Environment.** One Windows 11 machine with 12 logical CPUs and 32 GB RAM. PostgreSQL 18.6 and RabbitMQ 4.3.7 in Docker Desktop 27.3 (WSL 2) with their images' default settings (`shared_buffers` 128 MB, `max_connections` 100, synchronous commits). The application on the host: JDK 21.0.1, `-Xms512m -Xmx1g`, 10 database connections, 200 request threads. k6 1.3.0 in a container on the same machine, reaching the application through Docker's port forwarding. Data: the defaults of `seed.sql` (2.1 million messages).

**What this does and doesn't show.** Load generator, application and databases share the same 12 CPUs, and Docker Desktop's networking and virtual disk add stalls of their own (seen twice: dial timeouts for about 10 seconds in one run at 800 actions per second, and commits taking 350 ms for about 4 seconds in two runs, confirmed in PostgreSQL's log). The absolute numbers are this machine's, not a production capacity; they show where the first limits are and what the changes did. One application instance, except in [WebSocket delivery across two instances](#websocket-delivery-across-two-instances). The REST mix is an assumed workload, not a measured one.

### First bottleneck: the connection pool

REST mix (`rest-mix.js`, 200 users; about 15 % of actions send a message):

| Actions/s offered | Requests/s (baseline → after) | Errors | p95 list | p95 history page | p95 send |
|---|---|---|---|---|---|
| 50 | 61 → 61 | 0 → 0 | 33 → 12 ms | 9 → 8 ms | 22 → 28 ms |
| 100 | 118 → 119 | 0 → 0 | 36 → 9 ms | 22 → 6 ms | 312 → 27 ms |
| 200 | 232 → 234 | 0 → 0 | 33 → 8 ms | 9 → 6 ms | 31 → 31 ms |
| 400 | 461 → 462 | 0 → 0 | 46 → 12 ms | 26 → 9 ms | 66 → 47 ms |
| 800 | 810 → 817 | 0 → 0 | 2.2 → 1.5 s | 2.3 → 1.6 s | 2.3 → 2.0 s |
| 1600 | 868 → 1,069 | 0 → 0 | 2.5 → 1.1 s | 2.6 → 1.1 s | 2.6 → 1.2 s |

Up to 400 actions per second (460 requests per second) nothing saturates: the application used under one CPU, PostgreSQL about 1.5. Lower latencies after the changes at low load are within run-to-run variation (warmer caches; the baseline logged every event at `DEBUG`) and aren't claimed as an improvement. Around 850 requests per second the **database connection pool** is the limit: all 10 connections busy, about 190 request threads waiting for one, PostgreSQL at only 2 of 12 CPUs. At 1600 actions per second the changed build served 23 % more requests with half the tail latency; the likeliest reasons are the much smaller log volume and the cheaper history totals (below), but this run doesn't isolate them.

More connections, measured with the baseline build at 1600 actions per second:

| Pool size | Requests/s | PostgreSQL CPU |
|---|---|---|
| 10 | 868 | 2 cores |
| 20 | 1,164 | 2.5–3 cores |
| 40 | 1,315 | 3.5 cores |

Doubling the pool bought a third more throughput, doubling it again an eighth, while the database's CPU kept climbing and the machine ran out of CPU. The default stays at 10, configurable: size it from the database's cores and the number of instances ([OPERATIONS.md](../OPERATIONS.md#database-connections)).

### Second bottleneck: one busy conversation

`send-contention.js`, 50 senders sending again as soon as their last message was stored, and 20 unrelated history reads per second:

| | Sends/s to the conversation | p95 send | p95 unrelated read | Errors | Connections in use / waiting |
|---|---|---|---|---|---|
| Baseline, one conversation | 80 | 0.69 s | **575 ms** | 0 | 10 / 52 |
| After, one conversation (90 s) | 55 | 1.0 s | **8 ms** | 0 | 3 / 0 |
| Baseline, 50 conversations | 323 | 0.30 s | 1.15 s | 0 | 10 / 40 |
| After, 50 conversations | 326 | 0.35 s | 0.64 s | 0 | 10 / 45 |

Sends to one conversation are stored one after the other (its row lock), and before the change every waiting send held a connection: one busy conversation took the whole pool, and every other request on the instance waited. The send gate (ARCHITECTURE.md, decision 23) lets two sends per conversation take a connection and keeps the rest waiting in memory: unrelated reads stayed at 8 ms. The price is the conversation's own rate, 55 instead of 80 messages per second on this machine. One permit instead of two gave 45 per second, three gave 50; runs with one or three also hit the commit stalls described above, which turned into `503`s for sends that waited longer than 5 seconds. 50 conversations at once are bound by the pool in both builds, as they should be.

### Opening long conversations

`long-history.js`, 100 first pages per second of the conversations with 200,000 messages:

| | p95 | p99 | PostgreSQL CPU |
|---|---|---|---|
| Baseline | 85 ms | 136 ms | 2–3.3 cores |
| After | 8 ms | 25 ms | 0.1 core |

Each page by number reported the total by counting the conversation's messages (23 ms and three processes per request, see the query plans). It now reads the highest sequence number, which is the same value.

### WebSocket delivery

`websocket.js`: every connected member subscribed to its conversations; half the messages to the 100-member group, half to direct conversations.

| | Delivered / expected | Delivery p95 | Delivery p99 | Handshake median / p99 |
|---|---|---|---|---|
| 200 connections, 10 msg/s, baseline | 29,132 / 29,132 | 50 ms | 80 ms | 37 / 104 ms |
| 200 connections, 10 msg/s, after | 29,620 / 29,620 | 52 ms | 123 ms | 99 / 326 ms |
| 1,000 connections, 20 msg/s, baseline | 54,383 / 58,286 (93 %) | 3.9 s | 6.3 s | 21 / 311 ms |
| 1,000 connections, 20 msg/s, after | 62,376 / 62,376 | 45 ms | 75 ms | 349 ms / 1.2 s |

The application used under 2 % CPU while connections were open; two of the 1,000 k6 connections never connected in either build, which points at the load generator. Handling STOMP frames on the request threads (decision 24) costs handshake time while 1,000 clients connect and subscribe at once: their subscriptions are checked on the same threads. The baseline's late and lost deliveries at 1,000 connections didn't recur after the change; the cause wasn't isolated.

### WebSocket delivery across two instances

Measured on 2026-10-25 with live deliveries fanned out between instances through RabbitMQ (ARCHITECTURE.md, decision 26): two instances from the same jar, each with the settings above, sharing the load-test PostgreSQL and RabbitMQ. `websocket.js` with `BASE_URLS` set to both: 1,000 connections and 20 messages per second, connections and sends alternating between the instances, so about half of all deliveries crossed RabbitMQ.

| | Delivered / expected | Delivery median | p95 | p99 |
|---|---|---|---|---|
| One instance, all connections and sends on it, run 1 | 61,981 / 61,984 | 23 ms | 71 ms | 409 ms |
| One instance, run 2 | 57,360 / 57,360 | 21 ms | 4.3 s | 5.3 s |
| Two instances, run 1, all deliveries | 57,770 / 57,770 | 27 ms | 84 ms | 143 ms |
| Two instances, run 2, sent through the subscriber's own instance | 60,124 / 60,124 (both kinds) | 18 ms | 47 ms | 80 ms |
| Two instances, run 2, sent through the other instance, through RabbitMQ | | 21 ms | 53 ms | 84 ms |

- **The hop through RabbitMQ costs a few milliseconds:** within the same run, 3 ms at the median, 6 ms at p95 and 4 ms at p99. Every delivery arrived in both two-instance runs; neither instance dropped a live delivery (`chattywap_fanout_dropped_total` was never created), `chattywap_fanout_buffered` was 0 afterwards, and each instance's `own` count matched what it had published.
- **The single-instance runs show this machine's noise, not the fan-out's cost.** Run 2's seconds-long tail came from a database stall: sends to the 100-member group, serialized by its row lock, held a connection for up to 1.1 s and waited up to 0.7 s for the lock, so their deliveries started late (latency is measured from the send request). The JVM paused for garbage collection 0.2 s in all since it started. Run 1's three missing deliveries weren't traced; handshakes took up to 3.3 s in that run, so some subscriptions were probably still being processed when the first messages were sent. Only one run of each kind was free of such effects, so compare within a run, not across runs.

### A client sending faster than the server handles

`stomp-flood.js`: one connection sends 4,000-character messages as fast as it can.

- **Baseline:** 20,000 frames (80 MB) were read in **1.1 s** and queued in server memory; the server worked through them at about 55 per second, still holding over 10,000 after three minutes.
- **After:** the client is slowed down by TCP to the rate the server handles them: 5,000 frames took **86 s** to send (58 per second), one request thread busy, heap unchanged. A flooding client that also subscribes and doesn't read its deliveries is disconnected after about 35 seconds.

### Failures

| Experiment | Baseline | After |
|---|---|---|
| PostgreSQL paused for 45 s, 6 reads and 1 send | 5 × `500` after 30 s; 1 hung for the 45 s (as long as the pause) | 5 × `503` after 5.0 s; 2 statements already sent: `503` after 30 s; `/readyz` stayed `200`; first request afterwards `200` |
| 8 concurrent 50 MB login bodies, no token | `400` after up to 2.4 s each, `OutOfMemoryError` twice | `413` in under 10 ms each, heap unchanged |
| RabbitMQ stopped for 1 minute at 100 actions/s | (not measured) | sends unaffected (p95 39 ms, no errors); 900 events pending, drained within about a minute of RabbitMQ's return; 53 relay log lines (4,087 before the relay summed up its failures) |

### Outbox throughput

`outbox-backlog.sh 50000`: 50,000 pending events published in about 98 seconds (**510 per second**, one relay thread) and processed by the example consumer in 511 seconds (**98 per second**: one consumer thread, a transaction per event). The consumer is the limit; raise `spring.rabbitmq.listener.simple.concurrency` if a real consumer needs more. Under saturation of the REST mix, the relay competes for the same connection pool, and up to 250 events waited before they were published.

### Query plans

`EXPLAIN (ANALYZE, BUFFERS)` of the statements Hibernate generates, against the seeded data, the largest conversation having 200,000 messages:

| Statement | Plan | Time |
|---|---|---|
| History page (first or by cursor, any depth) | Index scan backward on `(conversation_id, created_at, id)` | 0.07–0.15 ms |
| History total, by counting (before) | Parallel index-only scan of 200,000 entries | 23 ms |
| History total, highest sequence number (after) | One index entry | 0.02 ms |
| History page by offset 100,000 | Sequential scan and sort on disk (38 MB) | 132 ms |
| Unread count with a read position | Index-only scan of the unread entries | 0.06 ms |
| Unread count without a read position | Parallel index-only scan of 200,000 entries | 16 ms |
| Conversation list of a user with 314 conversations | Hash join, top-20 sort | 3.3 ms |
| Membership check, next sequence number | Index lookups | under 0.1 ms |
| Outbox claim with 100,000 pending among 565,000 rows | Partial index of pending events | 0.1 ms |
| Outbox gauges with 100,000 pending | Index scan of pending events | 21 and 25 ms |

Deep page numbers and unread counts without a read position still grow with the history; cursors avoid the first. Both are listed in ARCHITECTURE.md, unresolved decision 12.

### Logs

Over the same 17 minutes of contention, WebSocket and flood tests, the baseline wrote 110,964 log lines (one per event written, published and processed, at `DEBUG`); the changed build wrote 320, mostly requests slower than a second.
