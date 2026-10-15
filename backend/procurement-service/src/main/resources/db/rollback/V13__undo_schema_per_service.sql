-- Undoes V13__schema_per_service.sql: every table back in public, and V13 forgotten by Flyway. Not on the Flyway
-- path (db/rollback is never migrated); run it by hand, in one transaction, before rolling back to an application
-- image older than V13:
--
--   psql "$DATABASE_URL" --single-transaction -v ON_ERROR_STOP=1 -f V13__undo_schema_per_service.sql
--
-- Like V13 it only renames: rows, indexes and constraints move with their tables.

alter table identity.users set schema public;
alter table identity.refresh_tokens set schema public;
alter table identity.password_reset_tokens set schema public;
alter table identity.employees set schema public;

alter table masterdata.categories set schema public;
alter table masterdata.materials set schema public;
alter table masterdata.suppliers set schema public;
alter table masterdata.supplier_payment_terms set schema public;
alter table masterdata.material_stock_movements set schema public;

alter table procurement.purchase_requisitions set schema public;
alter table procurement.purchase_requisition_lines set schema public;
alter table procurement.purchase_orders set schema public;
alter table procurement.purchase_order_lines set schema public;
alter table procurement.goods_receipts set schema public;
alter table procurement.goods_receipt_lines set schema public;
alter table procurement.return_to_vendor set schema public;
alter table procurement.return_to_vendor_lines set schema public;

alter table finance.invoices set schema public;
alter table finance.invoice_lines set schema public;
alter table finance.payments set schema public;
alter table finance.payment_lines set schema public;
alter table finance.app_settings set schema public;

drop schema identity;
drop schema masterdata;
drop schema procurement;
drop schema finance;
drop schema analytics;

delete from public.flyway_schema_history where version = '13';
