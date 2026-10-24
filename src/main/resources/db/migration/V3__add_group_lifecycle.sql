-- Group deletion. Deleting a group, or its last member leaving, marks the group deleted instead of removing
-- its row.
--
-- A deleted group has no members: deleting it removes its memberships, which are what grant access, the same way
-- leaving removes one. The conversation row stays, with its name, creator and timestamps, so that records
-- referring to it (messages, once they exist) keep a valid conversation to point to. The application never
-- shows deleted groups and nothing can be done with them.

alter table conversations
    add column deleted_at timestamptz,
    -- Direct conversations can't be deleted.
    add constraint conversations_only_groups_deleted check (deleted_at is null or type = 'GROUP'),
    add constraint conversations_deleted_after_created check (deleted_at >= created_at);

-- Before this migration, a group whose last member left stayed in the table without members. Those are exactly
-- the groups the new rule deletes. Leaving set updated_at, so it holds the moment the last member left.
update conversations c
set deleted_at = c.updated_at
where c.type = 'GROUP'
  and not exists (select 1 from conversation_members m where m.conversation_id = c.id);
