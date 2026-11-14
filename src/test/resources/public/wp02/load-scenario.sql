-- Test setup only. Invoke exclusively inside the newly owned WP02 test Compose project.
-- psql variables are JSON values, never executable SQL or operational credentials.
\set ON_ERROR_STOP on
BEGIN;
UPDATE cane.products AS product
SET stock_qty = fixture."stockQty"
FROM jsonb_to_recordset(:'stockJson'::jsonb -> 'items') AS fixture(sku text, "stockQty" integer)
WHERE product.sku = fixture.sku;
UPDATE cane.settings AS setting
SET value = fixture.value
FROM jsonb_each(:'settingsJson'::jsonb) AS fixture(key, value)
WHERE setting.key = fixture.key;
COMMIT;
