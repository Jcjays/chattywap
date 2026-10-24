-- Delivery and read receipts, owned by the message feature: how far each member of a conversation has acknowledged
-- receiving and reading its messages. One row per member, not per message and recipient: a position covers every
-- message up to it, the way a read marker does. Rows are written by the member's own acknowledgements only, and only
-- ever move forward.
--
-- A member without a row has acknowledged nothing yet. The first acknowledgement inserts the row.
--
-- Delete behavior: a row belongs to its membership and goes with it (cascade), so a member who leaves or is removed,
-- or whose group is deleted, keeps no position; joining again starts without one. The positions name messages of the
-- same conversation (foreign keys on the sequence number), which can't be deleted (V4).

create table receipt_positions (
    conversation_id                uuid        not null,
    user_id                        uuid        not null,
    -- The sequence number (V6) of the last message the member's client acknowledged receiving: it has every message
    -- of the conversation up to and including that one.
    last_delivered_sequence_number bigint      not null,
    -- When last_delivered_sequence_number last moved forward.
    delivered_at                   timestamptz not null,
    -- The sequence number of the last message the member reported reading; null if they haven't reported any.
    last_read_sequence_number      bigint,
    -- When last_read_sequence_number last moved forward.
    read_at                        timestamptz,
    -- Also serves every lookup by conversation (its members' positions) and by member.
    primary key (conversation_id, user_id),
    constraint receipt_positions_member_fkey foreign key (conversation_id, user_id)
        references conversation_members (conversation_id, user_id) on delete cascade,
    constraint receipt_positions_delivered_message_fkey foreign key (conversation_id, last_delivered_sequence_number)
        references messages (conversation_id, sequence_number) on delete restrict,
    constraint receipt_positions_read_message_fkey foreign key (conversation_id, last_read_sequence_number)
        references messages (conversation_id, sequence_number) on delete restrict,
    constraint receipt_positions_read_complete check ((last_read_sequence_number is null) = (read_at is null)),
    -- A member can only read what their client received: reporting read progress also acknowledges delivery up to
    -- the same message.
    constraint receipt_positions_read_not_after_delivered
        check (last_read_sequence_number <= last_delivered_sequence_number)
);
