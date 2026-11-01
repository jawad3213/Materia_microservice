-- ===================================================================
-- The outbox relay parks a message that can never be published (it cannot be serialized, is too large for the broker,
-- names an invalid topic): parked_at says when, last_error why, and the relay goes on with the later ones instead of
-- stopping behind it for good. To publish it again once fixed: set parked_at back to null.
-- The partial index serves the relay's query for pending messages, and the purge of published ones.
-- ===================================================================
alter table masterdata.outbox_messages add column if not exists parked_at timestamp(6);
create index if not exists idx_outbox_messages_pending on masterdata.outbox_messages (created_at)
    where published_at is null and parked_at is null;
create index if not exists idx_outbox_messages_published_at on masterdata.outbox_messages (published_at)
    where published_at is not null;
create index if not exists idx_processed_events_processed_at on masterdata.processed_events (processed_at);
