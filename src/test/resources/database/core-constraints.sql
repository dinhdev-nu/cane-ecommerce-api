-- Adapted from approved cane-mvp/tests/cane-schema-check.sql.
-- WP01-01 storage constraints only; trigger behavior is verified in WP01-02.
-- JDBC caller owns the test transaction and always rolls fixtures back.
SET LOCAL search_path = cane, pg_catalog;
CREATE TEMP TABLE schema_check_results (label text PRIMARY KEY, rejected_constraint text) ON COMMIT DROP;

CREATE FUNCTION pg_temp.assert_ok(p_value boolean, p_label text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_value IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Check failed: %', p_label;
    END IF;
    INSERT INTO pg_temp.schema_check_results(label) VALUES (p_label);
END;
$$;

CREATE FUNCTION pg_temp.expect_error(p_sql text, p_state text, p_label text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE actual_state text; actual_constraint text;
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE, actual_constraint = CONSTRAINT_NAME;
        IF actual_state <> p_state THEN
            RAISE EXCEPTION '%: expected SQLSTATE %, got % (%)', p_label, p_state, actual_state, SQLERRM;
        END IF;
        INSERT INTO pg_temp.schema_check_results VALUES (p_label, actual_constraint);
        RETURN;
    END;
    RAISE EXCEPTION '%: statement unexpectedly succeeded', p_label;
END;
$$;

CREATE FUNCTION pg_temp.make_order(p_n integer, p_method text DEFAULT 'cod',
    p_mode text DEFAULT NULL, p_fulfillment text DEFAULT 'delivery') RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE result uuid;
BEGIN
    INSERT INTO orders (
        order_number, status, payment_method, fulfillment_method,
        recipient_name, recipient_phone, recipient_email, address, store_snapshot,
        subtotal_vnd, shipping_fee_vnd, bank_snapshot, bank_confirmation_mode,
        payment_due_at, link_expires_at, idempotency_key, request_hash
    ) VALUES (
        'CANE' || lpad(p_n::text, 6, '0'),
        CASE WHEN p_method = 'bank_transfer' THEN 'awaiting_payment' ELSE 'confirmed' END,
        p_method, p_fulfillment, 'Schema test', '0900000000', 'test@example.invalid',
        CASE WHEN p_fulfillment = 'delivery' THEN '{"wardCode":"TEST","wardName":"Test","line1":"Test"}'::jsonb END,
        CASE WHEN p_fulfillment = 'pickup' THEN '{"name":"Test","address":"Test"}'::jsonb END,
        25000, CASE WHEN p_fulfillment = 'pickup' THEN 0 ELSE 15000 END,
        CASE WHEN p_method = 'bank_transfer' THEN '{"bankCode":"TEST","bankName":"Test","accountNo":"TEST","accountName":"Test"}'::jsonb END,
        CASE WHEN p_method = 'bank_transfer' THEN p_mode END,
        CASE WHEN p_method = 'bank_transfer' THEN now() + interval '60 minutes' END,
        now() + interval '90 days', gen_random_uuid()::text, decode(repeat('01',32),'hex')
    ) RETURNING id INTO result;
    RETURN result;
END;
$$;

SELECT pg_temp.assert_ok((SELECT count(*)=10 FROM information_schema.tables
    WHERE table_schema='cane' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'), '10 core tables, excluding Flyway history');
SELECT pg_temp.assert_ok((SELECT count(*)=10 AND bool_and(table_name IN (
    'staff_users','staff_sessions','products','settings','orders','order_items',
    'payments','business_events','stock_movements','notification_outbox'))
    FROM information_schema.tables WHERE table_schema='cane' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'
    AND table_name <> 'payment_webhooks'), 'core table names match contract');
SELECT pg_temp.assert_ok(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='cane'
    AND column_name IN ('reserved','on_hand','inventory_state','fulfillment_status','access_token_hash','encrypted_payload')), 'no superseded columns');
SELECT pg_temp.assert_ok((SELECT count(*)=16 FROM settings), 'all 16 settings seeded');
SELECT pg_temp.assert_ok((SELECT value='false'::jsonb FROM settings WHERE key='bank_auto_confirm'), 'automatic confirmation disabled');
SELECT pg_temp.assert_ok((SELECT value='false'::jsonb FROM settings WHERE key='operator_can_confirm_bank'), 'operator bank confirmation disabled');
SELECT pg_temp.assert_ok((SELECT value='[]'::jsonb FROM settings WHERE key='enabled_payment_methods'), 'payment methods unconfigured');
SELECT pg_temp.assert_ok((SELECT value='null'::jsonb FROM settings WHERE key='shipping_fee_vnd'), 'shipping fee unconfigured rather than free');

-- Reject bad configuration at storage boundaries without requiring a generic JSON schema.
DO $$
DECLARE bad record;
BEGIN
    FOR bad IN SELECT * FROM (VALUES
        ('accepting_orders', '0', 'accepting-orders flag requires boolean'),
        ('bank_auto_confirm', '"enabled"', 'automatic confirmation rejects string flag'),
        ('operator_can_confirm_bank', 'null', 'operator permission cannot be null'),
        ('shipping_fee_vnd', '-1000', 'shipping fee cannot be negative'),
        ('free_ship_threshold_vnd', '1.5', 'free-shipping threshold uses whole VND'),
        ('shipping_fee_vnd', '"15000"', 'shipping fee rejects numeric string'),
        ('shipping_fee_vnd', '9223372036854775808', 'shipping fee fits bigint VND'),
        ('low_stock_threshold', '-1', 'low-stock threshold cannot be negative'),
        ('max_qty_per_sku', '0', 'maximum quantity must be positive'),
        ('max_qty_per_sku', '2147483648', 'quantity setting fits integer storage'),
        ('bank_hold_min', '0', 'bank reminder interval must be positive'),
        ('link_ttl_days', 'null', 'link lifetime cannot be null'),
        ('stale_alert_hours', '0.5', 'alert interval uses whole hours'),
        ('enabled_payment_methods', '["cod","card"]', 'payment methods reject unsupported values'),
        ('enabled_payment_methods', '"cod"', 'payment methods require an array'),
        ('enabled_payment_methods', 'null', 'payment methods cannot be null')
    ) AS cases(key, value, label) LOOP
        PERFORM pg_temp.expect_error(format('UPDATE settings SET value=%L::jsonb WHERE key=%L',
            bad.value, bad.key), '23514', bad.label);
    END LOOP;
END;
$$;
UPDATE settings SET value='0' WHERE key IN ('shipping_fee_vnd','free_ship_threshold_vnd','low_stock_threshold');
SELECT pg_temp.assert_ok((SELECT bool_and(value='0'::jsonb) FROM settings
    WHERE key IN ('shipping_fee_vnd','free_ship_threshold_vnd','low_stock_threshold')), 'zero fee and low-stock threshold are valid');
UPDATE settings SET value='["cod","bank_transfer"]' WHERE key='enabled_payment_methods';
SELECT pg_temp.assert_ok((SELECT value='["cod","bank_transfer"]'::jsonb FROM settings
    WHERE key='enabled_payment_methods'), 'supported payment methods can be enabled together');
UPDATE settings SET value='null' WHERE key IN ('shipping_fee_vnd','free_ship_threshold_vnd');
UPDATE settings SET value='5' WHERE key='low_stock_threshold';
UPDATE settings SET value='[]' WHERE key='enabled_payment_methods';
SELECT pg_temp.assert_ok((SELECT bool_and(value='null'::jsonb) FROM settings
    WHERE key IN ('shipping_fee_vnd','free_ship_threshold_vnd')), 'shipping settings retain explicit unconfigured semantics');

INSERT INTO staff_users (id,username,password_hash,role) VALUES
    ('00000000-0000-4000-8000-000000000001','__schema_owner','test-only-hash','owner'),
    ('00000000-0000-4000-8000-000000000002','__schema_operator','test-only-hash','operator');
SELECT pg_temp.expect_error($q$INSERT INTO staff_users(username,password_hash,role) VALUES ('bad-role','test','admin')$q$, '23514', 'owner/operator roles only');
SELECT pg_temp.expect_error($q$INSERT INTO staff_users(username,password_hash,role) VALUES ('__SCHEMA_OWNER','test','owner')$q$, '23505', 'case-insensitive staff username');
INSERT INTO staff_sessions(token_hash,staff_id,auth_version)
VALUES (decode(repeat('02',32),'hex'),'00000000-0000-4000-8000-000000000001',1);
SELECT pg_temp.expect_error($q$INSERT INTO staff_sessions(token_hash,staff_id,auth_version,expires_at)
    VALUES (decode(repeat('03',32),'hex'),'00000000-0000-4000-8000-000000000001',1,now()+interval '9 hours')$q$, '23514', 'session cannot exceed eight hours');
SELECT pg_temp.expect_error($q$UPDATE staff_sessions SET token_hash=decode('00','hex')$q$, '23514', 'session hash is 32 bytes');

INSERT INTO products(sku,name,unit_label,price_vnd,stock_qty,delivery_scope) VALUES
    ('__schema_a','Test A','unit',25000,20,'city'),
    ('__schema_b','Test B','unit',25000,10,'near');
SELECT pg_temp.expect_error($q$UPDATE products SET stock_qty=-1 WHERE sku='__schema_a'$q$, '23514', 'stock cannot be negative');
SELECT pg_temp.expect_error($q$UPDATE products SET price_vnd=0 WHERE sku='__schema_a'$q$, '23514', 'price must be positive');

SELECT pg_temp.make_order(1);
SELECT pg_temp.make_order(2,'bank_transfer','manual');
SELECT pg_temp.make_order(3,'bank_transfer','auto');
SELECT pg_temp.make_order(4,'cod',NULL,'pickup');
SELECT pg_temp.make_order(5);
SELECT pg_temp.assert_ok((SELECT total_vnd=40000 FROM orders WHERE order_number='CANE000001'), 'generated order total');
SELECT pg_temp.assert_ok((SELECT total_vnd=25000 AND shipping_fee_vnd=0 FROM orders WHERE order_number='CANE000004'), 'pickup has zero shipping');
SELECT pg_temp.expect_error($q$UPDATE orders SET idempotency_key=(SELECT idempotency_key FROM orders WHERE order_number='CANE000001') WHERE order_number='CANE000002'$q$, '23505', 'order idempotency key unique');
SELECT pg_temp.expect_error($q$UPDATE orders SET request_hash=decode('00','hex') WHERE order_number='CANE000001'$q$, '23514', 'order request hash is 32 bytes');
SELECT pg_temp.expect_error($q$UPDATE orders SET status='pending' WHERE order_number='CANE000001'$q$, '23514', 'order status vocabulary');
SELECT pg_temp.expect_error($q$UPDATE orders SET status='awaiting_payment' WHERE order_number='CANE000001'$q$, '23514', 'COD cannot await bank payment');
SELECT pg_temp.expect_error($q$UPDATE orders SET payment_due_at=now()+interval '1 hour' WHERE order_number='CANE000001'$q$, '23514', 'COD has no bank payment deadline');
SELECT pg_temp.expect_error($q$UPDATE orders SET payment_due_at=NULL WHERE order_number='CANE000002'$q$, '23514', 'manual reminder cannot be null while waiting');
SELECT pg_temp.expect_error($q$UPDATE orders SET bank_confirmation_mode=NULL WHERE order_number='CANE000002'$q$, '23514', 'null mode cannot bypass bank check');
SELECT pg_temp.expect_error($q$UPDATE orders SET bank_snapshot=NULL WHERE order_number='CANE000002'$q$, '23514', 'null snapshot cannot bypass bank check');
SELECT pg_temp.expect_error($q$UPDATE orders SET payment_due_at=NULL WHERE order_number='CANE000003'$q$, '23514', 'auto expiry cannot be null');
SELECT pg_temp.expect_error($q$UPDATE orders SET payment_due_at=created_at WHERE order_number='CANE000003'$q$, '23514', 'bank payment deadline must follow creation');
SELECT pg_temp.expect_error($q$UPDATE orders SET shipping_fee_vnd=1 WHERE order_number='CANE000004'$q$, '23514', 'pickup cannot charge shipping');
SELECT pg_temp.expect_error($q$UPDATE orders SET address=NULL WHERE order_number='CANE000001'$q$, '23514', 'delivery address required');
SELECT pg_temp.expect_error($q$UPDATE orders SET status='completed' WHERE order_number='CANE000004'$q$, '23514', 'pickup completion requires paid');
SELECT pg_temp.expect_error($q$UPDATE orders SET payment_status='refunded' WHERE order_number='CANE000001'$q$, '23514', 'refund only on cancelled order');
SELECT pg_temp.expect_error($q$UPDATE orders SET status='cancelled' WHERE order_number='CANE000001'$q$, '23514', 'cancelled requires kind reason and time');
SELECT pg_temp.expect_error($q$UPDATE orders SET delivery_issue=true,delivery_issue_reason='Test' WHERE order_number='CANE000001'$q$, '23514', 'delivery issue requires shipping');
UPDATE orders SET created_at=now()-interval '2 hours',payment_due_at=now()-interval '1 hour' WHERE order_number='CANE000002';
UPDATE settings SET value='true' WHERE key='bank_auto_confirm';
SELECT pg_temp.assert_ok((SELECT status='awaiting_payment' AND bank_confirmation_mode='manual'
    AND payment_due_at<now() FROM orders WHERE order_number='CANE000002'), 'overdue manual order remains waiting despite setting change');

-- Valid fulfillment paths must remain possible alongside the rejection checks.
SELECT pg_temp.make_order(6);
UPDATE orders SET status='preparing' WHERE order_number='CANE000006';
UPDATE orders SET status='shipping' WHERE order_number='CANE000006';
UPDATE orders SET status='completed' WHERE order_number='CANE000006';
SELECT pg_temp.assert_ok((SELECT status='completed' AND payment_status='unpaid' FROM orders
    WHERE order_number='CANE000006'), 'delivery COD may complete before money reconciliation');
UPDATE orders SET status='preparing' WHERE order_number='CANE000004';
UPDATE orders SET status='ready_for_pickup' WHERE order_number='CANE000004';
INSERT INTO payments(order_id,method,amount_vnd,applied_to_order,order_link_verified,confirmed_by)
SELECT id,'cash',total_vnd,true,true,'00000000-0000-4000-8000-000000000001' FROM orders WHERE order_number='CANE000004';
UPDATE orders SET status='completed',payment_status='paid' WHERE order_number='CANE000004';
SELECT pg_temp.assert_ok((SELECT status='completed' AND payment_status='paid' FROM orders
    WHERE order_number='CANE000004'), 'pickup COD can collect cash and complete together');
INSERT INTO payments(order_id,method,amount_vnd,provider_ref,bank_receipt,applied_to_order,order_link_verified,confirmed_by)
SELECT id,'bank',total_vnd,'TEST:TEST:CONFIRMED-MANUAL',
    '{"bankCode":"TEST","accountNo":"TEST","transferContent":"CANE000002","transactionAt":"2026-10-19T03:15:00Z"}',
    true,true,'00000000-0000-4000-8000-000000000001' FROM orders WHERE order_number='CANE000002';
UPDATE orders SET status='confirmed',payment_status='paid',payment_due_at=NULL
WHERE order_number='CANE000002';
SELECT pg_temp.assert_ok((SELECT status='confirmed' AND payment_status='paid' AND bank_confirmation_mode='manual'
    AND payment_due_at IS NULL FROM orders WHERE order_number='CANE000002'), 'confirm bank payment clears deadline and preserves mode');

INSERT INTO business_events(id,order_id,kind,actor,source_key)
SELECT '10000000-0000-4000-8000-000000000001',id,'order_placed','system','schema-test:placed' FROM orders WHERE order_number='CANE000001';
INSERT INTO order_items(order_id,sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,delivery_scope_snapshot)
SELECT id,'__schema_a','Test A','unit',25000,1,'city' FROM orders WHERE order_number='CANE000001';
SELECT pg_temp.expect_error($q$INSERT INTO order_items(order_id,sku,name_snapshot,unit_label_snapshot,unit_price_vnd,quantity,delivery_scope_snapshot)
    SELECT id,'__schema_a','Test A','unit',25000,1,'city' FROM orders WHERE order_number='CANE000001'$q$, '23505', 'one line per order SKU');

UPDATE products SET name='Current name',unit_label='Current unit',price_vnd=30000 WHERE sku='__schema_a';
SELECT pg_temp.assert_ok((SELECT name_snapshot='Test A' AND unit_label_snapshot='unit'
    AND unit_price_vnd=25000 AND quantity=1 AND line_total_vnd=25000 FROM order_items
    WHERE sku='__schema_a'), 'current product edits preserve purchase snapshots');
UPDATE products SET name='Test A',unit_label='unit',price_vnd=25000 WHERE sku='__schema_a';
UPDATE products SET stock_qty=stock_qty-1 WHERE sku='__schema_a';
INSERT INTO stock_movements(sku,delta,reason,order_id,event_id,actor)
SELECT '__schema_a',-1,'order',id,'10000000-0000-4000-8000-000000000001','system' FROM orders WHERE order_number='CANE000001';
SELECT pg_temp.expect_error($q$INSERT INTO stock_movements(sku,delta,reason,order_id,event_id,actor)
    SELECT '__schema_a',-1,'order',id,'10000000-0000-4000-8000-000000000001','system' FROM orders WHERE order_number='CANE000001'$q$, '23505', 'placement deducts each SKU once');
SELECT pg_temp.expect_error($q$INSERT INTO stock_movements(sku,delta,reason,order_id,event_id,actor)
    SELECT '__schema_a',1,'order',id,'10000000-0000-4000-8000-000000000001','system' FROM orders WHERE order_number='CANE000001'$q$, '23514', 'placement delta must be negative');
SELECT pg_temp.expect_error($q$INSERT INTO stock_movements(sku,delta,reason,event_id,actor)
    VALUES ('__schema_a',1,'cancel','10000000-0000-4000-8000-000000000001','system')$q$, '23514', 'return movement requires order');
SELECT pg_temp.expect_error($q$INSERT INTO stock_movements(sku,delta,reason,event_id,actor)
    VALUES ('__schema_a',1,'adjust',gen_random_uuid(),'system')$q$, '23503', 'ledger event must exist');
INSERT INTO business_events(id,order_id,kind,actor,source_key)
SELECT '10000000-0000-4000-8000-000000000002',id,'cancelled','system','schema-test:cancelled' FROM orders WHERE order_number='CANE000001';
WITH restored AS (
    INSERT INTO stock_movements(sku,delta,reason,order_id,event_id,actor)
    SELECT '__schema_a',1,'cancel',id,'10000000-0000-4000-8000-000000000002','system' FROM orders WHERE order_number='CANE000001'
    ON CONFLICT(order_id,sku) WHERE reason IN ('cancel','delivery_return') DO NOTHING
    RETURNING sku,delta
) UPDATE products s SET stock_qty=s.stock_qty+r.delta FROM restored r WHERE s.sku=r.sku;
UPDATE orders SET status='cancelled',cancel_kind='before_handover',cancel_reason='Test',cancelled_at=now() WHERE order_number='CANE000001';
SELECT pg_temp.assert_ok((SELECT stock_qty=20 FROM products WHERE sku='__schema_a'), 'pre-handover cancellation restores stock');
SELECT pg_temp.expect_error($q$INSERT INTO stock_movements(sku,delta,reason,order_id,event_id,actor)
    SELECT '__schema_a',1,'delivery_return',id,'10000000-0000-4000-8000-000000000002','system' FROM orders WHERE order_number='CANE000001'$q$, '23505', 'cancel and delivery return share once-only protection');
SELECT pg_temp.expect_error($q$DELETE FROM products WHERE sku='__schema_a'$q$, '23503', 'purchased SKU cannot be deleted');

INSERT INTO business_events(id,kind,actor,operation_key,request_hash,operation_result)
VALUES ('10000000-0000-4000-8000-000000000003','stock_adjusted','00000000-0000-4000-8000-000000000001',
    'schema-test-operation-0001',decode(repeat('04',32),'hex'),'{"sku":"__schema_a","stockBefore":20,"stockAfter":20,"delta":0}');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,operation_key,request_hash,operation_result)
    VALUES ('link_resent','00000000-0000-4000-8000-000000000001','schema-test-operation-0001',decode(repeat('05',32),'hex'),'{}')$q$, '23505', 'same staff operation key cannot repeat');
INSERT INTO business_events(kind,actor,operation_key,request_hash,operation_result)
VALUES ('internal_noop','00000000-0000-4000-8000-000000000002','schema-test-operation-0001',decode(repeat('04',32),'hex'),'{}');
SELECT pg_temp.assert_ok((SELECT count(*)=2 FROM business_events WHERE operation_key='schema-test-operation-0001'), 'operation keys scoped to immutable actor');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,operation_key) VALUES ('internal_noop','00000000-0000-4000-8000-000000000001','schema-test-bad-operation')$q$, '23514', 'operation key requires hash and result');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,request_hash,operation_result)
    VALUES ('internal_noop','00000000-0000-4000-8000-000000000001',decode(repeat('04',32),'hex'),'{}')$q$, '23514', 'hash and result require operation key');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,source_key) VALUES ('order_placed','system','schema-test:placed')$q$, '23505', 'stable event source cannot repeat');

INSERT INTO payments(id,method,amount_vnd,provider_ref,bank_receipt,confirmed_by,needs_review)
VALUES ('20000000-0000-4000-8000-000000000001','bank',40000,'TEST:ACCOUNT:REAL-TX-1',
    '{"bankCode":"TEST","accountNo":"ACCOUNT","transferContent":"Unknown","transactionAt":"2026-10-19T03:15:00Z"}','system',true);
SELECT pg_temp.assert_ok((SELECT order_id IS NULL AND NOT applied_to_order AND NOT order_link_verified
    FROM payments WHERE id='20000000-0000-4000-8000-000000000001'), 'unresolved bank receipt supported');
SELECT pg_temp.expect_error($q$INSERT INTO payments(method,amount_vnd,provider_ref,bank_receipt,confirmed_by)
    VALUES ('bank',40000,'TEST:ACCOUNT:REAL-TX-1','{}','system')$q$, '23505', 'manual and webhook share bank dedup reference');
SELECT pg_temp.expect_error($q$INSERT INTO payments(method,amount_vnd,confirmed_by) VALUES ('cash',40000,'system')$q$, '23514', 'cash receipt requires order');
SELECT pg_temp.expect_error($q$INSERT INTO payments(method,amount_vnd,provider_ref,confirmed_by) VALUES ('bank',40000,'TEST:ACCOUNT:MISSING','system')$q$, '23514', 'bank receipt requires evidence');
SELECT pg_temp.expect_error($q$UPDATE payments SET applied_to_order=true WHERE id='20000000-0000-4000-8000-000000000001'$q$, '23514', 'unknown receipt cannot be principal');
SELECT pg_temp.expect_error($q$UPDATE payments SET order_link_verified=true WHERE id='20000000-0000-4000-8000-000000000001'$q$, '23514', 'unknown receipt cannot be verified');
SELECT pg_temp.expect_error($q$UPDATE payments SET status='refunded' WHERE id='20000000-0000-4000-8000-000000000001'$q$, '23514', 'refund requires actual reference and time');
INSERT INTO business_events(id,payment_id,kind,actor,data)
VALUES ('10000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000001',
    'payment_received','system','{"orderLinkVerified":false,"amountVnd":40000}');
INSERT INTO notification_outbox(id,kind,event_id,payment_id,channel,recipient)
VALUES ('30000000-0000-4000-8000-000000000001','payment_received','10000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000001',
    'email','owner@example.invalid');
SELECT pg_temp.assert_ok((SELECT order_id IS NULL FROM notification_outbox WHERE id='30000000-0000-4000-8000-000000000001'), 'Owner notification supports unresolved receipt without order');

INSERT INTO notification_outbox(id,kind,order_id,event_id,channel,recipient)
SELECT '30000000-0000-4000-8000-000000000003','cancelled',id,'10000000-0000-4000-8000-000000000002','email','test@example.invalid'
FROM orders WHERE order_number='CANE000001';
INSERT INTO notification_outbox(kind,order_id,event_id,channel,recipient)
SELECT 'cancelled',id,'10000000-0000-4000-8000-000000000002','email','test@example.invalid'
FROM orders WHERE order_number='CANE000001'
ON CONFLICT(event_id,channel,recipient) DO NOTHING;
SELECT pg_temp.assert_ok((SELECT count(*)=1 FROM notification_outbox WHERE event_id='10000000-0000-4000-8000-000000000002'), 'duplicate enqueue is harmless');
INSERT INTO notification_outbox(id,kind,order_id,event_id,channel,recipient)
SELECT '30000000-0000-4000-8000-000000000004','owner_alert',id,'10000000-0000-4000-8000-000000000002','telegram','-100000000'
FROM orders WHERE order_number='CANE000001';
SELECT pg_temp.assert_ok((SELECT count(*)=2 FROM notification_outbox WHERE event_id='10000000-0000-4000-8000-000000000002'), 'distinct channels can share source event');
INSERT INTO notification_outbox(kind,order_id,event_id,channel,recipient)
SELECT 'owner_alert',id,'10000000-0000-4000-8000-000000000002','email','owner@example.invalid'
FROM orders WHERE order_number='CANE000001';
SELECT pg_temp.assert_ok((SELECT count(*)=3 FROM notification_outbox WHERE event_id='10000000-0000-4000-8000-000000000002'), 'distinct recipients can share event and channel');
SELECT pg_temp.expect_error($q$INSERT INTO notification_outbox(kind,event_id,channel,recipient)
    VALUES ('owner_alert',gen_random_uuid(),'email','owner@example.invalid')$q$, '23503', 'outbox requires existing event');
SELECT pg_temp.expect_error($q$INSERT INTO notification_outbox(kind,event_id,channel,recipient)
    VALUES ('refunded','10000000-0000-4000-8000-000000000002','email','owner@example.invalid')$q$, '23514', 'refund message requires receipt');
SELECT pg_temp.expect_error($q$UPDATE notification_outbox SET attempts=8 WHERE id='30000000-0000-4000-8000-000000000003'$q$, '23514', 'attempt eight cannot remain pending');
UPDATE notification_outbox SET status='processing',attempts=8,locked_at=now() WHERE id='30000000-0000-4000-8000-000000000003';
UPDATE notification_outbox SET status='sent',locked_at=NULL,sent_at=now() WHERE id='30000000-0000-4000-8000-000000000003';
SELECT pg_temp.assert_ok((SELECT status='sent' AND attempts=8 FROM notification_outbox WHERE id='30000000-0000-4000-8000-000000000003'), 'eighth attempt success is sent');
UPDATE notification_outbox SET status='processing',attempts=8,locked_at=now()-interval '6 minutes' WHERE id='30000000-0000-4000-8000-000000000004';
UPDATE notification_outbox SET status='exhausted',locked_at=NULL WHERE id='30000000-0000-4000-8000-000000000004';
SELECT pg_temp.assert_ok((SELECT status='exhausted' FROM notification_outbox WHERE id='30000000-0000-4000-8000-000000000004'), 'crashed eighth lease can be exhausted');
SELECT pg_temp.expect_error($q$UPDATE notification_outbox SET attempts=9 WHERE id='30000000-0000-4000-8000-000000000004'$q$, '23514', 'no ninth send attempt');

-- Remaining named row constraints and SQL NULL boundaries from database design section 5.
SELECT pg_temp.expect_error($q$UPDATE orders SET bank_snapshot='{}' WHERE order_number='CANE000001'$q$, '23514', 'COD rejects a bank snapshot');
SELECT pg_temp.expect_error($q$UPDATE orders SET link_expires_at=created_at WHERE order_number='CANE000006'$q$, '23514', 'link expiry follows creation');
SELECT pg_temp.expect_error($q$UPDATE orders SET status='cancelled',payment_status='refund_pending',cancel_kind='delivery_failed',cancel_reason='Test',cancelled_at=now() WHERE order_number='CANE000004'$q$, '23514', 'pickup cannot close as failed delivery');
SELECT pg_temp.expect_error($q$UPDATE orders SET cancel_request_note='orphan' WHERE order_number='CANE000006'$q$, '23514', 'cancel request note needs a request timestamp');
SELECT pg_temp.expect_error($q$UPDATE orders SET cancel_requested_at=now() WHERE order_number='CANE000006'$q$, '23514', 'completed order has no pending cancellation');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,public_label) VALUES ('test','system','Public')$q$, '23514', 'public event requires an order');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,data) VALUES ('test','system','[]')$q$, '23514', 'event data is an object');
SELECT pg_temp.expect_error($q$UPDATE settings SET value=NULL WHERE key='store'$q$, '23502', 'SQL null differs from JSON null');
SELECT pg_temp.expect_error($q$INSERT INTO products(sku,name,unit_label,price_vnd,delivery_scope) VALUES ('__bad','','unit',1,'city')$q$, '23514', 'product name is nonblank');
SELECT pg_temp.expect_error($q$INSERT INTO staff_sessions(token_hash,staff_id,auth_version) VALUES (decode(repeat('09',32),'hex'),gen_random_uuid(),1)$q$, '23503', 'session requires existing staff');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,order_id) VALUES ('test','system',gen_random_uuid())$q$, '23503', 'event order foreign key');
SELECT pg_temp.expect_error($q$INSERT INTO business_events(kind,actor,payment_id) VALUES ('test','system',gen_random_uuid())$q$, '23503', 'event payment foreign key');
SELECT pg_temp.expect_error($q$UPDATE notification_outbox SET status='processing',attempts=1,locked_at=NULL WHERE id='30000000-0000-4000-8000-000000000001'$q$, '23514', 'processing requires a lease');
SELECT pg_temp.expect_error($q$UPDATE notification_outbox SET status='exhausted',attempts=7 WHERE id='30000000-0000-4000-8000-000000000001'$q$, '23514', 'exhausted means eight attempts');
SELECT pg_temp.expect_error($q$UPDATE notification_outbox SET sent_at=now() WHERE id='30000000-0000-4000-8000-000000000001'$q$, '23514', 'unsent outbox has no sent timestamp');
SELECT pg_temp.expect_error($q$INSERT INTO notification_outbox(kind,event_id,channel,recipient) VALUES ('cancelled','10000000-0000-4000-8000-000000000002','email','test@example.invalid')$q$, '23514', 'order message requires order');
SELECT pg_temp.expect_error($q$INSERT INTO payments(order_id,method,amount_vnd,applied_to_order,order_link_verified,confirmed_by) SELECT order_id,'cash',25000,true,true,'system' FROM payments WHERE method='cash'$q$, '23505', 'one principal per order');
SELECT pg_temp.expect_error($q$INSERT INTO notification_outbox(kind,order_id,event_id,channel,recipient) SELECT 'owner_alert',order_id,event_id,channel,recipient FROM notification_outbox WHERE id='30000000-0000-4000-8000-000000000001'$q$, '23505', 'outbox uniqueness identifies event channel recipient');
