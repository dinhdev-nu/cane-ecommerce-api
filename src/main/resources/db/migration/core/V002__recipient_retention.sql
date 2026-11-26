SET LOCAL search_path = cane, pg_catalog;

ALTER TABLE orders
    ADD COLUMN recipient_redacted_at timestamptz,
    ALTER COLUMN recipient_name DROP NOT NULL,
    ALTER COLUMN recipient_phone DROP NOT NULL,
    ALTER COLUMN recipient_email DROP NOT NULL,
    ADD CONSTRAINT order_recipient_retention CHECK (
        (recipient_redacted_at IS NULL AND recipient_name IS NOT NULL
            AND recipient_phone IS NOT NULL AND recipient_email IS NOT NULL)
        OR (recipient_redacted_at IS NOT NULL AND recipient_name IS NULL
            AND recipient_phone IS NULL AND recipient_email IS NULL
            AND note IS NULL AND status IN ('completed', 'cancelled'))
    );

CREATE FUNCTION without_restock_notes(event_data jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS $$
    SELECT CASE WHEN jsonb_typeof(event_data->'restockItems') = 'array' THEN
        jsonb_set(event_data, '{restockItems}', coalesce((
            SELECT jsonb_agg(
                CASE WHEN item ? 'note' THEN jsonb_set(item, '{note}', 'null'::jsonb) ELSE item END ORDER BY position
            ) FROM jsonb_array_elements(event_data->'restockItems') WITH ORDINALITY AS items(item, position)
        ), '[]'::jsonb)) ELSE event_data END;
$$;
REVOKE ALL ON FUNCTION without_restock_notes(jsonb) FROM PUBLIC;

-- The table owner can erase free text on a redacted order. The event facts,
-- order-action receipt keys and stock movements remain verifiable.
CREATE FUNCTION protect_business_event() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF current_user = pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid = TG_RELID)) THEN
            IF NEW.note IS NULL
                AND NEW.data = cane.without_restock_notes(OLD.data)
                AND NEW.operation_result IS NOT DISTINCT FROM cane.without_restock_notes(OLD.operation_result)
                AND (to_jsonb(NEW) - ARRAY['note', 'data', 'operation_result']) =
                    (to_jsonb(OLD) - ARRAY['note', 'data', 'operation_result'])
                AND EXISTS (
                SELECT 1
                FROM cane.orders o
                WHERE o.recipient_redacted_at IS NOT NULL
                    AND (o.id = OLD.order_id
                        OR (OLD.order_id IS NULL AND EXISTS (
                            SELECT 1 FROM cane.payments p WHERE p.id = OLD.payment_id AND p.order_id = o.id
                        )))
                ) THEN
                RETURN NEW;
            END IF;
        END IF;
    END IF;
    RAISE EXCEPTION 'Operation % is not allowed on %', TG_OP, TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;
REVOKE ALL ON FUNCTION protect_business_event() FROM PUBLIC;

DROP TRIGGER immutable_business_events ON business_events;
CREATE TRIGGER immutable_business_events BEFORE UPDATE OR DELETE ON business_events
FOR EACH ROW EXECUTE FUNCTION protect_business_event();

CREATE FUNCTION protect_stock_movement() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND current_user = pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid = TG_RELID))
        AND OLD.note IS NOT NULL AND NEW.note IS NULL
        AND (to_jsonb(NEW) - 'note') = (to_jsonb(OLD) - 'note')
        AND EXISTS (SELECT 1 FROM cane.orders WHERE id = OLD.order_id AND recipient_redacted_at IS NOT NULL)
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Operation % is not allowed on %', TG_OP, TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;
REVOKE ALL ON FUNCTION protect_stock_movement() FROM PUBLIC;

DROP TRIGGER immutable_stock_movements ON stock_movements;
CREATE TRIGGER immutable_stock_movements BEFORE UPDATE OR DELETE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION protect_stock_movement();

-- The operator supplies the latest case-closure timestamp after checking external
-- disputes and records. Run preview in a read-only transaction before applying.
CREATE FUNCTION process_recipient_retention(
    target_id uuid, case_closed_at timestamptz, decision text, apply_change boolean
) RETURNS text
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    target cane.orders%ROWTYPE;
    now_at timestamptz := statement_timestamp();
    payment_activity_at timestamptz;
    order_closed_at timestamptz;
BEGIN
    IF target_id IS NULL OR case_closed_at IS NULL OR case_closed_at > now_at
        OR decision IS NULL OR decision NOT IN ('scheduled', 'verified_request')
        OR apply_change IS NULL THEN
        RAISE EXCEPTION 'Invalid retention decision' USING ERRCODE = '22023';
    END IF;
    IF apply_change THEN
        SELECT * INTO target FROM cane.orders WHERE id = target_id FOR UPDATE;
    ELSE
        SELECT * INTO target FROM cane.orders WHERE id = target_id;
    END IF;
    IF target.id IS NULL THEN RETURN 'not_found'; END IF;
    IF target.recipient_redacted_at IS NOT NULL THEN RETURN 'already_redacted'; END IF;
    IF target.status NOT IN ('completed', 'cancelled')
        OR (target.status = 'completed' AND target.payment_status <> 'paid')
        OR (target.status = 'cancelled' AND target.payment_status NOT IN ('unpaid', 'refunded')) THEN
        RETURN 'open_order_or_balance';
    END IF;
    IF apply_change THEN
        PERFORM 1 FROM cane.payments WHERE order_id = target_id ORDER BY id FOR UPDATE;
        PERFORM 1 FROM cane.notification_outbox WHERE order_id = target_id ORDER BY id FOR UPDATE;
    END IF;
    IF EXISTS (
        SELECT 1 FROM cane.payments
        WHERE order_id = target_id AND (needs_review OR NOT order_link_verified
            OR (NOT applied_to_order AND status = 'succeeded'))
    ) THEN RETURN 'open_payment_review'; END IF;
    SELECT max(greatest(confirmed_at, refunded_at, reviewed_at))
        INTO payment_activity_at FROM cane.payments WHERE order_id = target_id;
    SELECT coalesce(target.cancelled_at, max(created_at), target.updated_at)
        INTO order_closed_at FROM cane.business_events
        WHERE order_id = target_id AND kind = 'order_completed';
    IF case_closed_at < greatest(order_closed_at, payment_activity_at) THEN
        RETURN 'order_changed_after_review';
    END IF;
    IF decision = 'scheduled' AND case_closed_at > now_at - interval '90 days' THEN
        RETURN 'not_due';
    END IF;
    IF EXISTS (
        SELECT 1 FROM cane.notification_outbox
        WHERE order_id = target_id AND channel = 'email' AND NOT starts_with(kind, 'owner_')
            AND status = 'processing'
    ) THEN RETURN 'notification_in_flight'; END IF;
    IF NOT apply_change THEN RETURN 'eligible'; END IF;

    UPDATE cane.orders SET
        recipient_redacted_at = now_at,
        recipient_name = NULL, recipient_phone = NULL, recipient_email = NULL, note = NULL,
        address = CASE WHEN address IS NULL THEN NULL ELSE
            jsonb_build_object('wardCode', address->'wardCode', 'wardName', address->'wardName') END,
        cancel_reason = CASE WHEN cancel_reason IS NULL THEN NULL ELSE 'Đã ẩn theo chính sách lưu dữ liệu' END,
        link_expires_at = least(link_expires_at, now_at)
    WHERE id = target_id;

    UPDATE cane.notification_outbox SET
        recipient = concat('redacted-', id, '@example.invalid'),
        status = CASE WHEN status = 'pending' THEN 'skipped' ELSE status END,
        last_error = NULL
    WHERE order_id = target_id AND channel = 'email' AND NOT starts_with(kind, 'owner_');

    UPDATE cane.payments SET note = NULL WHERE order_id = target_id AND note IS NOT NULL;
    UPDATE cane.stock_movements SET note = NULL WHERE order_id = target_id AND note IS NOT NULL;
    UPDATE cane.business_events SET
        note = NULL, data = cane.without_restock_notes(data),
        operation_result = cane.without_restock_notes(operation_result)
    WHERE (note IS NOT NULL OR data IS DISTINCT FROM cane.without_restock_notes(data)
        OR operation_result IS DISTINCT FROM cane.without_restock_notes(operation_result))
        AND (order_id = target_id OR (order_id IS NULL AND payment_id IN (
        SELECT id FROM cane.payments WHERE order_id = target_id
    )));
    RETURN 'redacted';
END;
$$;
REVOKE ALL ON FUNCTION process_recipient_retention(uuid, timestamptz, text, boolean) FROM PUBLIC;
