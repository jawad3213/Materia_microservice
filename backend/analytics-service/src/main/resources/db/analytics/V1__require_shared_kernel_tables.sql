-- The shared kernel's tables analytics uses while every service shares one database: the change history (audit_log),
-- the outbox, the inbox, the document code sequences, attachments and exchange rates, in public. The monolith's migrations own them until each
-- service gets its own database (Phase 6); analytics only checks they are there, so that on a new database it says
-- what to do instead of failing later on a missing table.

do $$
declare
    missing text;
begin
    select string_agg(t, ', ') into missing
    from unnest(array['audit_log', 'outbox_messages', 'processed_events', 'global_code_sequences', 'attachments',
                        'exchange_rates']) as t
    where to_regclass('public.' || t) is null;
    if missing is not null then
        raise exception 'Shared tables missing in public (%): start the monolith first, its migrations create them', missing;
    end if;
end $$;
