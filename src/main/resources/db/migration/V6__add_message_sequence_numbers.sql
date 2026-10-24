-- Per-conversation sequence numbers of messages: a conversation's first message is 1, every following one gets the
-- next number, without gaps. Read and delivery receipts (V7) are positions in this order.
--
-- The application assigns the number when it stores a message: one more than the conversation's highest, read while
-- the send holds the conversation lock (see ConversationService#recordActivity). Messages of one conversation are
-- therefore numbered one after the other in commit order, whatever the application's clocks say, unlike created_at.
-- A transaction that rolls back doesn't use up its number, and messages are never deleted, so there are no gaps.

alter table messages add column sequence_number bigint;

-- Existing messages are numbered in the order of their history: created_at, then id.
update messages m
set sequence_number = numbered.sequence_number
from (select id, row_number() over (partition by conversation_id order by created_at, id) as sequence_number
      from messages) numbered
where m.id = numbered.id;

alter table messages
    alter column sequence_number set not null,
    add constraint messages_sequence_number_positive check (sequence_number > 0),
    -- The unique constraint catches a number assigned twice, which only a bug could cause. Its index also serves the
    -- highest number of a conversation (read backwards, one entry) and unread counts: "conversation_id = ? and
    -- sequence_number > ? and sender_id <> ?" is answered from the index alone, because it includes sender_id.
    add constraint messages_conversation_id_sequence_number_key
        unique (conversation_id, sequence_number) include (sender_id);
