-- Test only: the shared kernel's tables, which in a real environment the monolith's migrations create in public
-- (identity's V2 requires them). Identity's tests run on a database of their own, so they create them here first,
-- with the monolith's definitions (V1, V5, V6, V7, V12).

create table if not exists public.outbox_messages (
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

create table if not exists public.global_code_sequences (
    next_val integer not null,
    prefix character varying(30) not null primary key
);

create table if not exists public.audit_log (
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

create table if not exists public.processed_events (
    consumer     varchar(100) not null,
    event_id     uuid         not null,
    event_type   varchar(100),
    processed_at timestamp(6) not null default now(),
    constraint pk_processed_events primary key (consumer, event_id)
);

create table if not exists public.attachments (
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

create table if not exists public.exchange_rates (
    id            uuid          not null primary key,
    rate_date     date          not null,
    from_currency varchar(3)    not null,
    to_currency   varchar(3)    not null,
    rate          numeric(19,8) not null check (rate > 0),
    source        varchar(20)   not null,
    created_at    timestamp(6)  not null default now(),
    constraint uk_exchange_rates_day_pair unique (rate_date, from_currency, to_currency)
);
