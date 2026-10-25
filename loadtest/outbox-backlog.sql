-- A backlog of pending MessageCreated events, as if RabbitMQ had been unreachable while :events messages were sent.
-- Each event is valid and names an existing message, so the relay publishes it and the example consumer processes it.
-- Used by loadtest/outbox-backlog.sh; load-test database only.

\set ON_ERROR_STOP on
\if :{?events} \else \set events 50000 \endif

insert into outbox_events (id, event_type, event_version, routing_key, payload, created_at, attempts, next_attempt_at)
select e.id, 'MessageCreated', 1, 'message.created',
       json_build_object('eventId', e.id, 'eventType', 'MessageCreated', 'eventVersion', 1,
                         'occurredAt', to_char(e.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                         'messageId', e.message_id, 'conversationId', e.conversation_id,
                         'senderId', e.sender_id)::text,
       now(), 0, now()
-- A new ID for every row: gen_random_uuid() in the select list of the subquery is evaluated per row.
from (select gen_random_uuid() as id, m.id as message_id, m.conversation_id, m.sender_id, m.created_at
      from (select id, conversation_id, sender_id, created_at from messages limit :events) m) e;
