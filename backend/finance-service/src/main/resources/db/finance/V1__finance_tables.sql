-- Finance's own tables, now owned by the finance service (its Flyway history: finance.flyway_schema_history).
--
-- On a database that already has them (every existing environment: the monolith's migrations created them, its V13
-- moved them to this schema), this changes nothing: every statement is "if not exists". On a new database the
-- monolith, which starts first (V2 requires the shared kernel's tables it creates), has created them already.
--
-- Same definitions as the monolith's V1__init.sql, with the columns its V6 (reporting-currency valuation), V9 (VAT
-- per line) and V11 (price match) added, and V10's settings table, moved here.

create schema if not exists finance;


CREATE TABLE IF NOT EXISTS finance.invoices (
    id uuid NOT NULL,
    invoice_code character varying(50) NOT NULL,
    invoice_type character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    purchase_order_id character varying(100),
    purchase_order_code character varying(100),
    goods_receipt_id character varying(100),
    goods_receipt_code character varying(100),
    supplier_id character varying(100) NOT NULL,
    supplier_name character varying(255) NOT NULL,
    supplier_code character varying(50),
    external_reference character varying(100),
    invoice_date date NOT NULL,
    due_date date,
    received_date date,
    payment_date date,
    currency_code character varying(3) NOT NULL,
    total_amount numeric(19,4) NOT NULL,
    total_tax_amount numeric(19,4) NOT NULL,
    total_amount_with_tax numeric(19,4) NOT NULL,
    is_verified boolean NOT NULL,
    has_discrepancy boolean NOT NULL,
    discrepancy_summary character varying(1000),
    verification_date timestamp(6) without time zone,
    verified_by character varying(100),
    verified_by_name character varying(255),
    paid_amount numeric(19,4),
    paid_at timestamp(6) without time zone,
    paid_by character varying(100),
    paid_by_name character varying(255),
    notes character varying(1000),
    internal_notes character varying(1000),
    created_at timestamp(6) without time zone NOT NULL,
    created_by character varying(100),
    updated_at timestamp(6) without time zone,
    updated_by character varying(100),
    version bigint,
    CONSTRAINT invoices_invoice_type_check CHECK (((invoice_type)::text = ANY ((ARRAY['STANDARD'::character varying, 'CREDIT_NOTE'::character varying])::text[]))),
    CONSTRAINT invoices_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'VERIFIED'::character varying, 'PAID'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT invoices_invoice_code_key UNIQUE (invoice_code),
    CONSTRAINT invoices_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_invoice_date ON finance.invoices USING btree (invoice_date);
CREATE INDEX IF NOT EXISTS idx_invoice_po_id ON finance.invoices USING btree (purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_invoice_status ON finance.invoices USING btree (status);
CREATE INDEX IF NOT EXISTS idx_invoice_supplier_id ON finance.invoices USING btree (supplier_id);

CREATE TABLE IF NOT EXISTS finance.invoice_lines (
    id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    line_number integer NOT NULL,
    purchase_order_line_id character varying(100),
    goods_receipt_line_id character varying(100),
    material_code character varying(50),
    material_name character varying(255) NOT NULL,
    unit_of_measure character varying(50),
    quantity_ordered integer,
    quantity_received integer,
    quantity_invoiced integer NOT NULL,
    quantity_discrepancy integer,
    unit_price numeric(19,4) NOT NULL,
    line_total numeric(19,4) NOT NULL,
    tax_amount numeric(19,4),
    line_total_with_tax numeric(19,4),
    currency_code character varying(3) NOT NULL,
    has_quantity_discrepancy boolean NOT NULL,
    discrepancy_notes character varying(1000),
    notes character varying(1000),
    created_at timestamp(6) without time zone NOT NULL,
    created_by character varying(100),
    updated_at timestamp(6) without time zone,
    updated_by character varying(100),
    version bigint,
    CONSTRAINT invoice_lines_pkey PRIMARY KEY (id),
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id) REFERENCES finance.invoices(id)
);
CREATE INDEX IF NOT EXISTS idx_invoice_lines_invoice_id ON finance.invoice_lines USING btree (invoice_id);

CREATE TABLE IF NOT EXISTS finance.payments (
    id uuid NOT NULL,
    payment_code character varying(50) NOT NULL,
    status character varying(30) NOT NULL,
    supplier_id character varying(100) NOT NULL,
    supplier_name character varying(255) NOT NULL,
    supplier_code character varying(50),
    total_amount numeric(19,4) NOT NULL,
    paid_amount numeric(19,4),
    currency_code character varying(3) NOT NULL,
    payment_date timestamp(6) without time zone,
    confirmed_date timestamp(6) without time zone,
    bank_reference character varying(100),
    transaction_id character varying(100),
    payment_method character varying(50),
    payment_receipt character varying(255),
    notes character varying(1000),
    internal_notes character varying(1000),
    created_at timestamp(6) without time zone NOT NULL,
    created_by character varying(100),
    updated_at timestamp(6) without time zone,
    updated_by character varying(100),
    version bigint,
    CONSTRAINT payments_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT payments_payment_code_key UNIQUE (payment_code),
    CONSTRAINT payments_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_payment_status ON finance.payments USING btree (status);
CREATE INDEX IF NOT EXISTS idx_payment_supplier_id ON finance.payments USING btree (supplier_id);

CREATE TABLE IF NOT EXISTS finance.payment_lines (
    id uuid NOT NULL,
    payment_id uuid NOT NULL,
    line_number integer NOT NULL,
    invoice_id character varying(100) NOT NULL,
    invoice_code character varying(100),
    supplier_id character varying(100) NOT NULL,
    supplier_name character varying(255),
    amount numeric(19,4) NOT NULL,
    paid_amount numeric(19,4),
    currency_code character varying(3) NOT NULL,
    is_paid boolean NOT NULL,
    notes character varying(1000),
    created_at timestamp(6) without time zone NOT NULL,
    created_by character varying(100),
    updated_at timestamp(6) without time zone,
    updated_by character varying(100),
    version bigint,
    CONSTRAINT payment_lines_pkey PRIMARY KEY (id),
    CONSTRAINT fk_payment_lines_payment FOREIGN KEY (payment_id) REFERENCES finance.payments(id)
);
CREATE INDEX IF NOT EXISTS idx_payment_lines_invoice_id ON finance.payment_lines USING btree (invoice_id);
CREATE INDEX IF NOT EXISTS idx_payment_lines_payment_id ON finance.payment_lines USING btree (payment_id);

-- V6: valuation in the reporting currency
alter table finance.invoices add column if not exists fx_rate_to_reporting numeric(19,8);
alter table finance.invoices add column if not exists amount_reporting numeric(19,4);
alter table finance.invoices add column if not exists fx_rate_date date;
alter table finance.invoices add column if not exists fx_rate_source varchar(20);
alter table finance.invoices add column if not exists fx_reporting_currency varchar(3);
alter table finance.payments add column if not exists fx_rate_to_reporting numeric(19,8);
alter table finance.payments add column if not exists amount_reporting numeric(19,4);
alter table finance.payments add column if not exists fx_rate_date date;
alter table finance.payments add column if not exists fx_rate_source varchar(20);
alter table finance.payments add column if not exists fx_reporting_currency varchar(3);

-- V9: VAT per invoice line
alter table finance.invoice_lines add column if not exists tax_rate numeric(5,2);
alter table finance.invoice_lines add column if not exists order_tax_rate numeric(5,2);
alter table finance.invoice_lines add column if not exists has_tax_discrepancy boolean not null default false;

-- V11: price match per invoice line
alter table finance.invoice_lines add column if not exists order_unit_price numeric(19,4);
alter table finance.invoice_lines add column if not exists price_variance_percent numeric(9,2);
alter table finance.invoice_lines add column if not exists has_price_discrepancy boolean not null default false;

-- V10: the settings managed from the Settings page
create table if not exists finance.app_settings (
    id            uuid          not null primary key,
    setting_key   varchar(100)  not null,
    setting_value varchar(2000),
    created_at    timestamp(6)  not null,
    updated_at    timestamp(6),
    created_by    varchar(100),
    updated_by    varchar(100),
    version       bigint,
    constraint uk_app_settings_key unique (setting_key)
);
