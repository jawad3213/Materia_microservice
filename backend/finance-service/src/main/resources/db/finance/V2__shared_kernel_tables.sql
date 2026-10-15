-- Phase 6, one database per service: finance's own copy of the shared kernel's tables, in its schema (finance): its
-- outbox and inbox of integration events, its change history, its document code sequences, its attachments and its
-- exchange rates. Its connection's search_path is finance alone, so the shared kernel's entities and native SQL use
-- these, never another service's.
--
-- Until now every service shared these tables in public (the monolith's migrations created them). Where they exist,
-- the rows that belong to finance are copied: the events its contexts (invoice, payment, settings) raised and the ones they
-- consumed, the change history of its records (Invoice, InvoiceLine, Payment, PaymentLine, Setting), the attachments of
-- the documents its contexts own (INVOICE), every code sequence and every exchange rate. On a new database there is nothing to copy. The public
-- tables stay as the monolith's migrations left them; no service reads them any more.

create schema if not exists finance;

create table if not exists finance.outbox_messages (
    id uuid not null primary key,
    event_id uuid not null unique,
    event_type character varying(150) not null,
    source_context character varying(60) not null,
    aggregate_id character varying(100),
    payload text not null,
    occurred_at timestamp(6) without time zone not null,
    created_at timestamp(6) without time zone not null,
    published_at timestamp(6) without time zone,
    attempts integer not null,
    last_error character varying(1000)
);

create table if not exists finance.global_code_sequences (
    next_val integer not null,
    prefix character varying(30) not null primary key
);

create table if not exists finance.audit_log (
    id          uuid          not null primary key,
    occurred_at timestamp(6)  not null,
    actor_id    varchar(100)  not null,
    entity_type varchar(60)   not null,
    entity_id   uuid          not null,
    parent_type varchar(60),
    parent_id   uuid,
    action      varchar(20)   not null check (action in ('CREATE', 'UPDATE', 'STATUS_CHANGE', 'DELETE')),
    summary     varchar(500),
    reason      varchar(1000),
    changes     jsonb         not null default '{}'::jsonb
);

create table if not exists finance.processed_events (
    consumer     varchar(100) not null,
    event_id     uuid         not null,
    event_type   varchar(100),
    processed_at timestamp(6) not null default now(),
    constraint pk_processed_events primary key (consumer, event_id)
);

create table if not exists finance.attachments (
    id uuid not null primary key,
    owner_type varchar(30) not null,
    owner_id uuid not null,
    category varchar(30) not null,
    file_name varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes bigint not null,
    storage_key varchar(300) not null unique,
    uploaded_by varchar(100),
    uploaded_by_name varchar(200),
    uploaded_at timestamp(6) not null
);

create table if not exists finance.exchange_rates (
    id            uuid          not null primary key,
    rate_date     date          not null,
    from_currency varchar(3)    not null,
    to_currency   varchar(3)    not null,
    rate          numeric(19,8) not null check (rate > 0),
    source        varchar(20)   not null,
    created_at    timestamp(6)  not null default now(),
    constraint uk_exchange_rates_day_pair unique (rate_date, from_currency, to_currency)
);

do $$
begin
    if to_regclass('public.outbox_messages') is not null then
        insert into finance.outbox_messages (id, event_id, event_type, source_context, aggregate_id, payload, occurred_at, created_at, published_at, attempts, last_error)
            select id, event_id, event_type, source_context, aggregate_id, payload, occurred_at, created_at, published_at, attempts, last_error from public.outbox_messages
            where source_context in ('invoice', 'payment', 'settings')
            on conflict do nothing;
    end if;
    if to_regclass('public.processed_events') is not null then
        insert into finance.processed_events (consumer, event_id, event_type, processed_at)
            select consumer, event_id, event_type, processed_at from public.processed_events
            where consumer like 'invoice.%' or consumer like 'payment.%' or consumer like 'settings.%'
            on conflict do nothing;
    end if;
    if to_regclass('public.global_code_sequences') is not null then
        insert into finance.global_code_sequences (next_val, prefix)
            select next_val, prefix from public.global_code_sequences
            on conflict do nothing;
    end if;
    if to_regclass('public.exchange_rates') is not null then
        insert into finance.exchange_rates (id, rate_date, from_currency, to_currency, rate, source, created_at)
            select id, rate_date, from_currency, to_currency, rate, source, created_at from public.exchange_rates
            on conflict do nothing;
    end if;
    if to_regclass('public.audit_log') is not null then
        insert into finance.audit_log (id, occurred_at, actor_id, entity_type, entity_id, parent_type, parent_id, action, summary, reason, changes)
            select id, occurred_at, actor_id, entity_type, entity_id, parent_type, parent_id, action, summary, reason, changes from public.audit_log
            where entity_type in ('Invoice', 'InvoiceLine', 'Payment', 'PaymentLine', 'Setting') or parent_type in ('Invoice', 'InvoiceLine', 'Payment', 'PaymentLine', 'Setting')
            on conflict do nothing;
    end if;
    if to_regclass('public.attachments') is not null then
        insert into finance.attachments (id, owner_type, owner_id, category, file_name, content_type, size_bytes, storage_key, uploaded_by, uploaded_by_name, uploaded_at)
            select id, owner_type, owner_id, category, file_name, content_type, size_bytes, storage_key, uploaded_by, uploaded_by_name, uploaded_at from public.attachments
            where owner_type in ('INVOICE')
            on conflict do nothing;
    end if;
end $$;
