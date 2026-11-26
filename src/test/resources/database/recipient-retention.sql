BEGIN;
SET LOCAL search_path = cane, pg_catalog;

INSERT INTO orders (id, order_number, status, payment_status, fulfillment_method, payment_method,
    recipient_name, recipient_phone, recipient_email, address, note, subtotal_vnd, shipping_fee_vnd,
    link_expires_at, idempotency_key, request_hash, cancelled_at, cancel_kind, cancel_reason, created_at, updated_at)
SELECT id::uuid, order_number, status, payment_status, 'delivery', 'cod',
    'Fixture customer', '0800000000', 'customer@example.invalid',
    '{"wardCode":"1","wardName":"Fixture ward","line1":"Fixture home"}', 'Fixture note', 50000, 30000,
    now() + interval '90 days', 'retention-fixture-' || order_number, decode(repeat('42',32),'hex'),
    CASE WHEN status = 'cancelled' THEN now() - interval '110 days' END,
    CASE WHEN status = 'cancelled' THEN 'delivery_failed' END,
    CASE WHEN status = 'cancelled' THEN 'Fixture contact' END,
    now() - interval '120 days', now() - interval '110 days'
FROM (VALUES
    ('74000000-0000-4000-8000-000000000001', 'CANERET001', 'cancelled', 'refunded'),
    ('74000000-0000-4000-8000-000000000002', 'CANERET002', 'completed', 'unpaid')
) AS fixtures(id, order_number, status, payment_status);

INSERT INTO payments (id, order_id, method, amount_vnd, status, provider_ref, applied_to_order,
    order_link_verified, confirmed_by, confirmed_at, refunded_at, refund_reference, bank_receipt, note)
VALUES ('74000000-0000-4000-8000-000000001001', '74000000-0000-4000-8000-000000000001', 'bank', 80000,
    'refunded', 'receipt-fixture', true, true, 'fixture', now() - interval '115 days',
    now() - interval '100 days', 'refund-fixture', '{"amount":80000}', 'Fixture payment note');

INSERT INTO business_events (id, order_id, payment_id, kind, actor, data, note, operation_key, request_hash,
    operation_result)
VALUES ('74000000-0000-4000-8000-000000002001', '74000000-0000-4000-8000-000000000001',
    '74000000-0000-4000-8000-000000001001', 'delivery_failed_closed', 'fixture',
    '{"restockItems":[{"sku":"TEST","quantity":0,"note":"Fixture goods note"}]}', 'Fixture event note',
    'fixture-retention-key', decode(repeat('12',32),'hex'),
    '{"orderId":"74000000-0000-4000-8000-000000000001","restockItems":[{"sku":"TEST","quantity":0,"note":"Fixture goods note"}]}');

INSERT INTO notification_outbox (id, kind, order_id, event_id, payment_id, channel, recipient)
VALUES ('74000000-0000-4000-8000-000000003001', 'refunded', '74000000-0000-4000-8000-000000000001',
    '74000000-0000-4000-8000-000000002001', '74000000-0000-4000-8000-000000001001',
    'email', 'customer@example.invalid');

DO $$
DECLARE
    target uuid := '74000000-0000-4000-8000-000000000001';
    open_cod uuid := '74000000-0000-4000-8000-000000000002';
    closed_at timestamptz := now() - interval '100 days';
BEGIN
    ASSERT process_recipient_retention(open_cod, closed_at, 'verified_request', false) = 'open_order_or_balance';
    ASSERT process_recipient_retention(target, closed_at - interval '1 minute', 'scheduled', false) =
        'order_changed_after_review';
    ASSERT process_recipient_retention(target, now() - interval '89 days', 'scheduled', false) = 'not_due';
    ASSERT process_recipient_retention(target, now() - interval '89 days', 'verified_request', false) = 'eligible';
    ASSERT process_recipient_retention(target, closed_at, 'scheduled', false) = 'eligible';
    ASSERT (SELECT recipient_email IS NOT NULL FROM orders WHERE id = target);
    UPDATE notification_outbox SET status = 'processing', attempts = 1, locked_at = now() WHERE order_id = target;
    ASSERT process_recipient_retention(target, closed_at, 'scheduled', true) = 'notification_in_flight';
    UPDATE notification_outbox SET status = 'pending', locked_at = NULL WHERE order_id = target;
    ASSERT NOT has_function_privilege('cane_api', 'cane.process_recipient_retention(uuid,timestamptz,text,boolean)', 'EXECUTE');
    ASSERT NOT has_function_privilege('cane_worker', 'cane.process_recipient_retention(uuid,timestamptz,text,boolean)', 'EXECUTE');
END;
$$;

SAVEPOINT before_redaction;
DO $$
DECLARE
    target uuid := '74000000-0000-4000-8000-000000000001';
    closed_at timestamptz := now() - interval '100 days';
BEGIN
    ASSERT process_recipient_retention(target, closed_at, 'scheduled', true) = 'redacted';
    ASSERT process_recipient_retention(target, closed_at, 'scheduled', true) = 'already_redacted';
    ASSERT (SELECT recipient_redacted_at IS NOT NULL AND recipient_name IS NULL AND recipient_email IS NULL
        AND recipient_phone IS NULL AND note IS NULL AND NOT address ? 'line1' AND address->>'wardCode' = '1'
        AND total_vnd = 80000 AND link_expires_at <= statement_timestamp() FROM orders WHERE id = target);
    ASSERT (SELECT status = 'skipped' AND attempts = 1 AND recipient LIKE 'redacted-%@example.invalid'
        FROM notification_outbox WHERE order_id = target);
    ASSERT (SELECT amount_vnd = 80000 AND bank_receipt = '{"amount":80000}' AND status = 'refunded'
        AND refund_reference = 'refund-fixture' AND note IS NULL FROM payments WHERE order_id = target);
    ASSERT (SELECT note IS NULL AND data#>'{restockItems,0,note}' = 'null'::jsonb
        AND operation_result#>>'{restockItems,0,sku}' = 'TEST' AND request_hash = decode(repeat('12',32),'hex')
        AND operation_key = 'fixture-retention-key' FROM business_events WHERE order_id = target);
    BEGIN
        UPDATE business_events SET kind = 'changed_fixture' WHERE order_id = target;
        RAISE EXCEPTION 'An audit fact could be overwritten';
    EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$$;

ROLLBACK TO before_redaction;
DO $$
DECLARE
    target uuid := '74000000-0000-4000-8000-000000000001';
BEGIN
    ASSERT (SELECT recipient_email IS NOT NULL FROM orders WHERE id = target);
    ASSERT process_recipient_retention(target, now() - interval '100 days', 'scheduled', true) = 'redacted';
    ASSERT (SELECT recipient_email IS NULL FROM orders WHERE id = target);
END;
$$;

ROLLBACK;
