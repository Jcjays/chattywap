-- Representative data for load tests and query plans. Run it once against an empty load-test database whose schema
-- Flyway has created (start the application once), never against production:
--
--   docker compose -p chattywap-loadtest --env-file loadtest/.env exec -T postgres \
--     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < loadtest/seed.sql
--
-- Sizes can be changed with psql variables (-v users=5000 ...). The defaults create about 2,000 users, 16,200
-- conversations and 2.1 million messages, in a few minutes. Every ID is derived from a name (md5 of it, as a UUID),
-- so the k6 scripts compute IDs instead of looking them up:
--
--   user n                  md5('lt-user-<n>')             email lt-<n>@loadtest.invalid, password loadtest-password
--   group g                 md5('lt-group-<g>')            g = 1 is the "hot" group: users 1-100, all of them members
--   direct conversation a-b md5('lt-direct-<a>-<b>')       b = a+1 ... a+5 (wrapping around), and the power users'
--
-- Shape:
--   * every user has 10 direct conversations; users 1-20 ("power users") have 300 more each;
--   * 200 groups of 10 to 100 members; group 1 has 100 members (users 1-100) and 50,000 messages, groups 2-6 have
--     200,000 messages each (deep histories), the other groups 500;
--   * direct conversations have 60 messages each;
--   * 70 % of the memberships have a read position up to 19 messages behind the newest; the others have none, so
--     every message others sent counts as unread (the most expensive unread count);
--   * 200,000 published outbox events, the history a running system accumulates.

\set ON_ERROR_STOP on
\if :{?users} \else \set users 2000 \endif
\if :{?groups} \else \set groups 200 \endif
\if :{?power_users} \else \set power_users 20 \endif
\if :{?power_user_directs} \else \set power_user_directs 300 \endif
\if :{?direct_messages} \else \set direct_messages 60 \endif
\if :{?group_messages} \else \set group_messages 500 \endif
\if :{?hot_messages} \else \set hot_messages 50000 \endif
\if :{?large_messages} \else \set large_messages 200000 \endif
\if :{?published_events} \else \set published_events 200000 \endif

\timing on

begin;

-- BCrypt in PostgreSQL, so that seeding needs nothing but psql. Spring's encoder reads the $2a$ hash behind its
-- {bcrypt} prefix. One hash for every user: hashing is deliberately slow.
create extension if not exists pgcrypto;
create temporary table seed_password as
select '{bcrypt}' || crypt('loadtest-password', gen_salt('bf', 10)) as hash;

insert into users (id, email, password_hash, display_name, created_at, updated_at)
select md5('lt-user-' || n)::uuid, 'lt-' || n || '@loadtest.invalid', (select hash from seed_password),
       'Load test user ' || n, now() - interval '120 days', now() - interval '120 days'
from generate_series(1, :users) n;

-- The conversations to create, with their members in order (the first is a group's administrator) and their number
-- of messages. Each one's newest message lies somewhere in the last ten days, so that the conversation lists aren't
-- ordered like the IDs.
create temporary table seed_conversations (
    id            uuid primary key,
    type          text   not null,
    name          text,
    members       uuid[] not null,
    message_count int    not null,
    start_at      timestamptz,
    end_at        timestamptz
);

insert into seed_conversations (id, type, name, members, message_count)
select md5('lt-group-' || g)::uuid, 'GROUP',
       case when g = 1 then 'lt-hot' else 'lt-group-' || g end,
       case when g = 1
            then array(select md5('lt-user-' || k)::uuid from generate_series(1, 100) k order by k)
            -- Steps of 7 through all users never repeat one (7 and the user count are coprime for the default).
            else array(select md5('lt-user-' || (((g * 13 + k * 7) % :users) + 1))::uuid
                       from generate_series(0, 9 + (g * 37) % 91) k order by k)
       end,
       case when g = 1 then :hot_messages when g between 2 and 6 then :large_messages else :group_messages end
from generate_series(1, :groups) g;

insert into seed_conversations (id, type, members, message_count)
select md5('lt-direct-' || a || '-' || b)::uuid, 'DIRECT',
       array[md5('lt-user-' || a)::uuid, md5('lt-user-' || b)::uuid], :direct_messages
from (select n as a, ((n + d - 1) % :users) + 1 as b
      from generate_series(1, :users) n, generate_series(1, 5) d) pairs;

insert into seed_conversations (id, type, members, message_count)
select md5('lt-direct-' || p || '-' || q)::uuid, 'DIRECT',
       array[md5('lt-user-' || p)::uuid, md5('lt-user-' || q)::uuid], :direct_messages
from generate_series(1, :power_users) p, generate_series(1, :power_user_directs) j,
     lateral (select p * 50 + j + 5 as q) other;

update seed_conversations
set end_at = now() - (abs(hashtext(id::text)) % 864000) * interval '1 second';
update seed_conversations
set start_at = end_at - interval '80 days';

insert into conversations (id, type, name, created_by, direct_user1_id, direct_user2_id, created_at, updated_at)
select id, type, name, members[1],
       case when type = 'DIRECT' then least(members[1], members[2]) end,
       case when type = 'DIRECT' then greatest(members[1], members[2]) end,
       start_at - interval '1 day', end_at
from seed_conversations;

insert into conversation_members (id, conversation_id, user_id, role, joined_at)
select gen_random_uuid(), c.id, m.user_id,
       case when c.type = 'GROUP' and m.position = 1 then 'ADMIN' else 'MEMBER' end,
       c.start_at - interval '1 day'
from seed_conversations c, unnest(c.members) with ordinality m(user_id, position);

-- Members take turns sending; the newest message is at end_at.
insert into messages (id, conversation_id, sender_id, content, created_at, sequence_number)
select gen_random_uuid(), c.id, c.members[1 + (s % array_length(c.members, 1))],
       'Load test message ' || s || ' ' || repeat('lorem ipsum ', 1 + s % 15),
       c.start_at + (c.end_at - c.start_at) * (s::float8 / c.message_count),
       s
from seed_conversations c, generate_series(1, c.message_count) s;

insert into receipt_positions (conversation_id, user_id, last_delivered_sequence_number, delivered_at,
                               last_read_sequence_number, read_at)
select c.id, m.user_id, c.message_count, c.end_at,
       greatest(1, c.message_count - abs(hashtext(c.id::text || m.user_id::text)) % 20), c.end_at
from seed_conversations c, unnest(c.members) m(user_id)
where c.message_count > 0 and abs(hashtext(c.id::text || m.user_id::text)) % 10 < 7;

insert into outbox_events (id, event_type, event_version, routing_key, payload, created_at, published_at, attempts,
                           next_attempt_at)
select gen_random_uuid(), 'MessageCreated', 1, 'message.created', '{"seed": true}',
       now() - interval '10 days', now() - interval '10 days', 1, now() - interval '10 days'
from generate_series(1, :published_events);

drop extension pgcrypto;

commit;

vacuum analyze;

select (select count(*) from users) as users, (select count(*) from conversations) as conversations,
       (select count(*) from conversation_members) as memberships, (select count(*) from messages) as messages,
       (select count(*) from receipt_positions) as receipt_positions, (select count(*) from outbox_events) as outbox_events;
