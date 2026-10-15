-- Identity's own tables, now owned by the identity service (its Flyway history: identity.flyway_schema_history).
--
-- On a database that already has them (every existing environment: the monolith's V1 created them, its V13 moved
-- them to this schema), this changes nothing: every statement is "if not exists". On a new database it creates them,
-- whichever service starts first: the monolith's V13 leaves alone the identity tables that already exist here.
--
-- Same definitions as the monolith's V1__init.sql and V3__users_email_case_insensitive_unique.sql, moved here.

create schema if not exists identity;

create table if not exists identity.users (
    id uuid not null,
    created_at timestamp without time zone default current_timestamp not null,
    updated_at timestamp without time zone default current_timestamp,
    version bigint default 0,
    created_by character varying(100),
    updated_by character varying(100),
    email character varying(255) not null,
    password_hash character varying(255) not null,
    role character varying(50) not null,
    enabled boolean default true not null,
    must_change_password boolean default false not null,
    first_name character varying(100),
    last_name character varying(100),
    full_name character varying(200),
    phone character varying(30),
    status character varying(30) default 'ACTIVE'::character varying not null,
    department character varying(100),
    constraint users_pkey primary key (id),
    constraint uk_users_email unique (email)
);
create index if not exists idx_users_email on identity.users (email);
create unique index if not exists uk_users_email_lower on identity.users (lower(email));

create table if not exists identity.refresh_tokens (
    id uuid not null,
    created_at timestamp without time zone default current_timestamp not null,
    updated_at timestamp without time zone default current_timestamp,
    version bigint default 0,
    token_hash character varying(64) not null,
    user_id uuid not null,
    expiry_date timestamp without time zone not null,
    revoked boolean default false not null,
    device_info character varying(255),
    ip_address character varying(50),
    created_by character varying(100),
    updated_by character varying(100),
    constraint refresh_tokens_pkey primary key (id),
    constraint uk_refresh_tokens_hash unique (token_hash),
    constraint refresh_tokens_user_id_fkey foreign key (user_id) references identity.users (id) on delete cascade
);
create index if not exists idx_refresh_tokens_hash on identity.refresh_tokens (token_hash);
create index if not exists idx_refresh_tokens_user_id on identity.refresh_tokens (user_id);

create table if not exists identity.password_reset_tokens (
    id uuid not null,
    created_at timestamp without time zone default current_timestamp not null,
    updated_at timestamp without time zone default current_timestamp,
    version bigint default 0,
    created_by character varying(100),
    updated_by character varying(100),
    token character varying(255) not null,
    user_id uuid not null,
    email character varying(255) not null,
    expiry_date timestamp without time zone not null,
    used boolean default false not null,
    constraint password_reset_tokens_pkey primary key (id),
    constraint uk_password_reset_tokens_token unique (token),
    constraint fk_password_reset_tokens_user foreign key (user_id) references identity.users (id) on delete cascade
);
create index if not exists idx_prt_email on identity.password_reset_tokens (email);
create index if not exists idx_prt_token on identity.password_reset_tokens (token);
create index if not exists idx_prt_user_id on identity.password_reset_tokens (user_id);

create table if not exists identity.employees (
    id uuid not null,
    created_at timestamp without time zone default current_timestamp not null,
    updated_at timestamp without time zone default current_timestamp,
    version bigint default 0,
    created_by character varying(100),
    updated_by character varying(100),
    code character varying(50) not null,
    first_name character varying(100) not null,
    last_name character varying(100) not null,
    full_name character varying(200) not null,
    email character varying(255) not null,
    phone character varying(50),
    status character varying(50) default 'ACTIVE'::character varying not null,
    user_id uuid,
    hire_date date,
    termination_date date,
    termination_reason character varying(500),
    constraint employees_pkey primary key (id),
    constraint uk_employees_code unique (code),
    constraint uk_employees_email unique (email),
    constraint fk_employees_users foreign key (user_id) references identity.users (id) on delete set null
);
create index if not exists idx_employees_code on identity.employees (code);
create index if not exists idx_employees_email on identity.employees (email);
create index if not exists idx_employees_status on identity.employees (status);
create index if not exists idx_employees_user_id on identity.employees (user_id);
