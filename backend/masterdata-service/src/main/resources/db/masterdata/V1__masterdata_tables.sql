-- Master data's own tables, now owned by the master data service (its Flyway history:
-- masterdata.flyway_schema_history).
--
-- On a database that already has them (every existing environment: the monolith's V1 created them, its V13 moved
-- them to this schema), this changes nothing: every statement is "if not exists". On a new database the monolith,
-- which starts first (V2 requires the shared kernel's tables it creates), has created them already.
--
-- Same definitions as the monolith's V1__init.sql, moved here.

create schema if not exists masterdata;


CREATE TABLE IF NOT EXISTS masterdata.categories (
    level integer,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone,
    version bigint,
    id uuid NOT NULL,
    status character varying(20) NOT NULL,
    category_type character varying(30),
    code character varying(50) NOT NULL,
    parent_code character varying(50),
    created_by character varying(100),
    name character varying(100) NOT NULL,
    updated_by character varying(100),
    short_description character varying(200),
    description character varying(500),
    path character varying(500),
    parent_id character varying(255),
    CONSTRAINT categories_category_type_check CHECK (((category_type)::text = ANY ((ARRAY['MATERIAL'::character varying, 'PRODUCT'::character varying, 'SERVICE'::character varying, 'RAW_MATERIAL_CAT'::character varying, 'COMPONENT_CAT'::character varying, 'PACKAGING_CAT'::character varying, 'SPARE_PART_CAT'::character varying, 'CONSUMABLE_CAT'::character varying, 'TOOL_CAT'::character varying, 'CHEMICAL_CAT'::character varying, 'ELECTRONIC_CAT'::character varying, 'FAMILY'::character varying, 'BRAND'::character varying, 'DEPARTMENT'::character varying, 'PROJECT'::character varying, 'GEOGRAPHIC'::character varying, 'SEASONAL'::character varying])::text[]))),
    CONSTRAINT categories_code_key UNIQUE (code),
    CONSTRAINT categories_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_category_parent_id ON masterdata.categories USING btree (parent_id);
CREATE INDEX IF NOT EXISTS idx_category_status ON masterdata.categories USING btree (status);
CREATE INDEX IF NOT EXISTS idx_category_type ON masterdata.categories USING btree (category_type);

CREATE TABLE IF NOT EXISTS masterdata.suppliers (
    payment_delay integer,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone,
    version bigint,
    currency_code character varying(10),
    id uuid NOT NULL,
    contact_phone character varying(20),
    postal_code character varying(20),
    status character varying(20) NOT NULL,
    code character varying(50) NOT NULL,
    city character varying(100),
    contact_person character varying(100),
    country character varying(100),
    created_by character varying(100),
    updated_by character varying(100),
    address character varying(500),
    description character varying(500),
    contact_email character varying(255),
    name character varying(255) NOT NULL,
    CONSTRAINT suppliers_currency_code_check CHECK (((currency_code)::text = ANY ((ARRAY['MAD'::character varying, 'EUR'::character varying, 'USD'::character varying])::text[]))),
    CONSTRAINT suppliers_code_key UNIQUE (code),
    CONSTRAINT suppliers_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_supplier_city ON masterdata.suppliers USING btree (city);
CREATE INDEX IF NOT EXISTS idx_supplier_country ON masterdata.suppliers USING btree (country);
CREATE INDEX IF NOT EXISTS idx_supplier_status ON masterdata.suppliers USING btree (status);

CREATE TABLE IF NOT EXISTS masterdata.supplier_payment_terms (
    supplier_id uuid NOT NULL,
    payment_term character varying(255),
    CONSTRAINT fk8kpayf27d2wsb1cu8i22d940t FOREIGN KEY (supplier_id) REFERENCES masterdata.suppliers(id)
);

CREATE TABLE IF NOT EXISTS masterdata.materials (
    available_stock integer,
    average_purchase_price numeric(19,4),
    cost_price numeric(19,4),
    current_stock integer,
    economic_order_quantity integer,
    stock_on_order integer,
    last_purchase_price numeric(19,4),
    maximum_stock integer,
    minimum_stock integer,
    reorder_point integer,
    safety_stock integer,
    standard_price numeric(19,4),
    created_at timestamp(6) without time zone NOT NULL,
    obsoleted_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    version bigint,
    average_purchase_price_currency character varying(10),
    cost_price_currency character varying(10),
    last_purchase_price_currency character varying(10),
    standard_price_currency character varying(10),
    id uuid NOT NULL,
    unit_of_measure character varying(20),
    material_type character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    code character varying(50) NOT NULL,
    category_name character varying(100),
    created_by character varying(100),
    obsoleted_by character varying(100),
    updated_by character varying(100),
    short_description character varying(200),
    obsoleted_reason character varying(500),
    search_keywords character varying(500),
    description character varying(1000),
    alternative_name character varying(255),
    category_id character varying(255),
    name character varying(255) NOT NULL,
    supplier_id character varying(255),
    supplier_name character varying(255),
    CONSTRAINT materials_average_purchase_price_currency_check CHECK (((average_purchase_price_currency)::text = ANY ((ARRAY['MAD'::character varying, 'EUR'::character varying, 'USD'::character varying])::text[]))),
    CONSTRAINT materials_cost_price_currency_check CHECK (((cost_price_currency)::text = ANY ((ARRAY['MAD'::character varying, 'EUR'::character varying, 'USD'::character varying])::text[]))),
    CONSTRAINT materials_last_purchase_price_currency_check CHECK (((last_purchase_price_currency)::text = ANY ((ARRAY['MAD'::character varying, 'EUR'::character varying, 'USD'::character varying])::text[]))),
    CONSTRAINT materials_material_type_check CHECK (((material_type)::text = ANY ((ARRAY['RAW_MATERIAL'::character varying, 'FINISHED_GOOD'::character varying, 'COMPONENT'::character varying, 'PACKAGING'::character varying, 'SPARE_PART'::character varying, 'CONSUMABLE'::character varying, 'SERVICE'::character varying, 'TOOL'::character varying, 'CHEMICAL'::character varying, 'ELECTRONIC'::character varying])::text[]))),
    CONSTRAINT materials_standard_price_currency_check CHECK (((standard_price_currency)::text = ANY ((ARRAY['MAD'::character varying, 'EUR'::character varying, 'USD'::character varying])::text[]))),
    CONSTRAINT materials_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ACTIVE'::character varying, 'INACTIVE'::character varying, 'BLOCKED'::character varying, 'OBSOLETE'::character varying])::text[]))),
    CONSTRAINT materials_unit_of_measure_check CHECK (((unit_of_measure)::text = ANY ((ARRAY['KG'::character varying, 'G'::character varying, 'T'::character varying, 'MG'::character varying, 'LB'::character varying, 'OZ'::character varying, 'L'::character varying, 'ML'::character varying, 'M3'::character varying, 'DM3'::character varying, 'CM3'::character varying, 'GAL'::character varying, 'FT3'::character varying, 'M'::character varying, 'CM'::character varying, 'MM'::character varying, 'KM'::character varying, 'IN'::character varying, 'FT'::character varying, 'YD'::character varying, 'M2'::character varying, 'CM2'::character varying, 'MM2'::character varying, 'HA'::character varying, 'ACRE'::character varying, 'PCE'::character varying, 'BOX'::character varying, 'CART'::character varying, 'PACK'::character varying, 'SET'::character varying, 'PAL'::character varying, 'DRUM'::character varying, 'ROLL'::character varying, 'SHEET'::character varying, 'REEL'::character varying, 'HOUR'::character varying, 'DAY'::character varying, 'WEEK'::character varying, 'MONTH'::character varying, 'KWH'::character varying, 'KW'::character varying, 'HP'::character varying, 'C'::character varying, 'F'::character varying, 'K'::character varying, 'BAR'::character varying, 'PSI'::character varying, 'PA'::character varying, 'ATM'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT materials_code_key UNIQUE (code),
    CONSTRAINT materials_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_material_category_id ON masterdata.materials USING btree (category_id);
CREATE INDEX IF NOT EXISTS idx_material_status ON masterdata.materials USING btree (status);
CREATE INDEX IF NOT EXISTS idx_material_supplier_id ON masterdata.materials USING btree (supplier_id);

CREATE TABLE IF NOT EXISTS masterdata.material_stock_movements (
    new_stock integer NOT NULL,
    previous_stock integer NOT NULL,
    quantity integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone,
    version bigint,
    id uuid NOT NULL,
    material_id uuid NOT NULL,
    movement_type character varying(30) NOT NULL,
    created_by character varying(100),
    updated_by character varying(100),
    reason character varying(500),
    CONSTRAINT material_stock_movements_movement_type_check CHECK (((movement_type)::text = ANY ((ARRAY['OPENING_BALANCE'::character varying, 'RECEIPT'::character varying, 'ISSUE'::character varying, 'ADJUSTMENT'::character varying])::text[]))),
    CONSTRAINT material_stock_movements_pkey PRIMARY KEY (id),
    CONSTRAINT fkkihr1ny42uednqq2dgv6y79v FOREIGN KEY (material_id) REFERENCES masterdata.materials(id)
);
