-- ===================================================================
-- One database per service (phase 6): each service connects as its own user, which owns its schema and everything
-- in it, and may not use another service's. Run by the PostgreSQL admin (deploy.sh, psql -v ON_ERROR_STOP=1) before
-- the services start and again once procurement (the monolith) has migrated; idempotent.
--
--   materia_identity    -> identity        materia_procurement -> procurement, public (its migrations' history and
--   materia_masterdata  -> masterdata                              the legacy shared tables)
--   materia_finance     -> finance         materia_analytics   -> analytics
--
-- psql variables: identity_password, masterdata_password, procurement_password, finance_password, analytics_password.
--
-- On a new database, procurement's legacy migrations still create every context's tables in public and move them to
-- the services' schemas (V13): procurement may create schemas, and the second run hands each schema and its tables to
-- their service. Until a service has run its copy of the shared kernel's tables (its "shared kernel tables"
-- migration), it may read the legacy shared tables in public, which it copies its rows from; then that access ends.
-- ===================================================================

-- ---- The users ----
select format('create role %I login', r)
from (values ('materia_identity'), ('materia_masterdata'), ('materia_procurement'), ('materia_finance'),
             ('materia_analytics')) as roles(r)
where not exists (select 1 from pg_roles where rolname = r) \gexec

alter role materia_identity with login password :'identity_password';
alter role materia_masterdata with login password :'masterdata_password';
alter role materia_procurement with login password :'procurement_password';
alter role materia_finance with login password :'finance_password';
alter role materia_analytics with login password :'analytics_password';

-- ---- Creating schemas: procurement's legacy V13 creates the services' schemas, and each service's first migration
-- runs "create schema if not exists" on its own, which PostgreSQL checks against the database even when it exists.
-- A schema a service creates is its own: it gains no access to another service's. ----
select format('grant create on database %I to materia_identity, materia_masterdata, materia_procurement, '
              || 'materia_finance, materia_analytics', current_database()) \gexec

-- ---- Procurement: public (its migrations' history, the legacy tables) ----
alter schema public owner to materia_procurement;
revoke create on schema public from public;
select format('alter table public.%I owner to materia_procurement', tablename)
from pg_tables where schemaname = 'public' and tableowner <> 'materia_procurement' \gexec
select format('alter sequence public.%I owner to materia_procurement', sequencename)
from pg_sequences where schemaname = 'public' and sequenceowner <> 'materia_procurement' \gexec

-- ---- Each service owns its schema and everything in it; nobody else may use it ----
select format('alter schema %I owner to %I', s.schema_name, s.role_name)
from (values ('identity', 'materia_identity'), ('masterdata', 'materia_masterdata'),
             ('procurement', 'materia_procurement'), ('finance', 'materia_finance'),
             ('analytics', 'materia_analytics')) as s(schema_name, role_name)
join information_schema.schemata n on n.schema_name = s.schema_name \gexec

select format('alter table %I.%I owner to %I', t.schemaname, t.tablename, s.role_name)
from (values ('identity', 'materia_identity'), ('masterdata', 'materia_masterdata'),
             ('procurement', 'materia_procurement'), ('finance', 'materia_finance'),
             ('analytics', 'materia_analytics')) as s(schema_name, role_name)
join pg_tables t on t.schemaname = s.schema_name
where t.tableowner <> s.role_name \gexec

select format('alter sequence %I.%I owner to %I', q.schemaname, q.sequencename, s.role_name)
from (values ('identity', 'materia_identity'), ('masterdata', 'materia_masterdata'),
             ('procurement', 'materia_procurement'), ('finance', 'materia_finance'),
             ('analytics', 'materia_analytics')) as s(schema_name, role_name)
join pg_sequences q on q.schemaname = s.schema_name
where q.sequenceowner <> s.role_name \gexec

select format('revoke all on schema %I from public', schema_name)
from information_schema.schemata
where schema_name in ('identity', 'masterdata', 'procurement', 'finance', 'analytics') \gexec

-- ---- The legacy shared tables in public: readable by a service until its own copy exists ----
-- A service's copy migration is its version 2 (identity, masterdata, finance), 1 (analytics); procurement owns public.
select format('grant usage on schema public to %1$I; grant select on public.outbox_messages, public.processed_events, '
              || 'public.audit_log, public.global_code_sequences, public.attachments, public.exchange_rates to %1$I',
              s.role_name)
from (values ('identity', 'materia_identity', '2'), ('masterdata', 'materia_masterdata', '2'),
             ('finance', 'materia_finance', '2'), ('analytics', 'materia_analytics', '1')) as s(schema_name, role_name, version)
where to_regclass('public.audit_log') is not null
  and (to_regclass(s.schema_name || '.flyway_schema_history') is null
       or not exists (select 1 from information_schema.tables
                      where table_schema = s.schema_name and table_name = 'audit_log')) \gexec

select format('revoke all on all tables in schema public from %1$I; revoke usage on schema public from %1$I',
              s.role_name)
from (values ('identity', 'materia_identity'), ('masterdata', 'materia_masterdata'),
             ('finance', 'materia_finance'), ('analytics', 'materia_analytics')) as s(schema_name, role_name)
where exists (select 1 from information_schema.tables
              where table_schema = s.schema_name and table_name = 'audit_log') \gexec
