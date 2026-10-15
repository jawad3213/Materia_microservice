-- One schema per service (docs/architecture/modular-monolith.md). Each table moves, with its rows, indexes,
-- constraints and owned sequences, to the schema of the service that owns it; no foreign key crosses two services,
-- so every constraint stays inside one schema. Nothing is copied: ALTER TABLE ... SET SCHEMA only renames.
--
-- Each entity names its service's schema (@Table(schema = ...)); native SQL finds tables through the connection's
-- search_path (spring.datasource.hikari.connection-init-sql).
--
-- The shared kernel's tables (code sequences, outbox, inbox, change history, exchange rates, attachments) stay in
-- public while every context runs in one application; each extracted service gets its own copy.
--
-- From here on, a migration names the schema of every table it touches (identity.users, not users) and never relies
-- on the search_path, which Flyway sets for itself.
--
-- Undo (before rolling back to an application without the search_path): db/rollback/V13__undo_schema_per_service.sql

create schema if not exists identity;
create schema if not exists masterdata;
create schema if not exists procurement;
create schema if not exists finance;
-- Analytics owns no table yet: the dashboard composes the other services' figures.
create schema if not exists analytics;

-- Identity: accounts and employees
alter table public.users set schema identity;
alter table public.refresh_tokens set schema identity;
alter table public.password_reset_tokens set schema identity;
alter table public.employees set schema identity;

-- Master data: catalogue, suppliers and stock
alter table public.categories set schema masterdata;
alter table public.materials set schema masterdata;
alter table public.suppliers set schema masterdata;
alter table public.supplier_payment_terms set schema masterdata;
alter table public.material_stock_movements set schema masterdata;

-- Procurement: requisitions, orders, receipts and returns
alter table public.purchase_requisitions set schema procurement;
alter table public.purchase_requisition_lines set schema procurement;
alter table public.purchase_orders set schema procurement;
alter table public.purchase_order_lines set schema procurement;
alter table public.goods_receipts set schema procurement;
alter table public.goods_receipt_lines set schema procurement;
alter table public.return_to_vendor set schema procurement;
alter table public.return_to_vendor_lines set schema procurement;

-- Finance: invoices, payments and the settings they use
alter table public.invoices set schema finance;
alter table public.invoice_lines set schema finance;
alter table public.payments set schema finance;
alter table public.payment_lines set schema finance;
alter table public.app_settings set schema finance;
