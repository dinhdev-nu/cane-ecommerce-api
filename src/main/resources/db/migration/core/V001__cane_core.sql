-- CANE core schema, database design 1.3 (07/11/2026).
-- Adapted from cane-mvp/cane-mvp.sql; Flyway owns schema creation and transaction.
-- Core only: no bank inbox, runtime grants, SKU or staff fixtures.
SET LOCAL search_path = cane, pg_catalog;

-- 04 section 2.1: immutable staff IDs identify audit actors.
CREATE TABLE staff_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username text NOT NULL CHECK (btrim(username) <> ''),
    password_hash text NOT NULL CHECK (btrim(password_hash) <> ''),
    role text NOT NULL CHECK (role IN ('owner', 'operator')),
    is_active boolean NOT NULL DEFAULT true,
    auth_version integer NOT NULL DEFAULT 1 CHECK (auth_version > 0),
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    locked_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX staff_users_username_unique ON staff_users (lower(username));

CREATE TABLE staff_sessions (
    token_hash bytea PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    staff_id uuid NOT NULL REFERENCES staff_users(id),
    auth_version integer NOT NULL CHECK (auth_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '8 hours'),
    CONSTRAINT staff_sessions_absolute_expiry CHECK (
        expires_at > created_at AND expires_at <= created_at + interval '8 hours'
    )
);
CREATE INDEX staff_sessions_expiry ON staff_sessions (expires_at);
CREATE INDEX staff_sessions_staff ON staff_sessions (staff_id);

CREATE TABLE products (
    sku text PRIMARY KEY CHECK (btrim(sku) <> ''),
    name text NOT NULL CHECK (btrim(name) <> ''),
    unit_label text NOT NULL CHECK (btrim(unit_label) <> ''),
    price_vnd bigint NOT NULL CHECK (price_vnd > 0),
    stock_qty integer NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    is_active boolean NOT NULL DEFAULT true,
    delivery_scope text NOT NULL CHECK (delivery_scope IN ('city', 'near')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES staff_users(id)
);

CREATE TABLE settings (
    key text PRIMARY KEY CHECK (btrim(key) <> ''),
    value jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES staff_users(id),
    CONSTRAINT settings_boolean_value CHECK (
        key NOT IN ('accepting_orders', 'bank_auto_confirm', 'operator_can_confirm_bank')
        OR jsonb_typeof(value) = 'boolean'
    ),
    CONSTRAINT settings_integer_value CHECK (
        CASE WHEN key IN ('shipping_fee_vnd', 'free_ship_threshold_vnd',
            'low_stock_threshold', 'max_qty_per_sku', 'bank_hold_min',
            'link_ttl_days', 'stale_alert_hours') THEN
            CASE jsonb_typeof(value)
                WHEN 'null' THEN key IN ('shipping_fee_vnd', 'free_ship_threshold_vnd')
                WHEN 'number' THEN
                    (value::text)::numeric = trunc((value::text)::numeric)
                    AND (value::text)::numeric >= CASE
                        WHEN key IN ('shipping_fee_vnd', 'free_ship_threshold_vnd', 'low_stock_threshold')
                        THEN 0 ELSE 1 END
                    AND (value::text)::numeric <= CASE
                        WHEN key IN ('shipping_fee_vnd', 'free_ship_threshold_vnd')
                        THEN 9223372036854775807::numeric ELSE 2147483647::numeric END
                ELSE false
            END
        ELSE true END
    ),
    CONSTRAINT settings_payment_methods_value CHECK (
        key <> 'enabled_payment_methods' OR CASE
            WHEN jsonb_typeof(value) = 'array'
            THEN value <@ '["cod", "bank_transfer"]'::jsonb
            ELSE false END
    )
);

-- 04 sections 2.6, 3; 01 sections 4, 7, 8.
-- The service generates CANE + six uppercase alphanumeric characters and retries
-- number collisions. The order number is never an authentication credential.
CREATE TABLE orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number text NOT NULL UNIQUE CHECK (order_number ~ '^CANE[A-Z0-9]{6}$'),
    status text NOT NULL CHECK (status IN (
        'awaiting_payment', 'confirmed', 'preparing', 'ready_for_pickup',
        'shipping', 'completed', 'cancelled'
    )),
    payment_status text NOT NULL DEFAULT 'unpaid' CHECK (payment_status IN (
        'unpaid', 'paid', 'refund_pending', 'refunded'
    )),
    fulfillment_method text NOT NULL CHECK (fulfillment_method IN ('delivery', 'pickup')),
    payment_method text NOT NULL CHECK (payment_method IN ('cod', 'bank_transfer')),
    recipient_name text NOT NULL CHECK (btrim(recipient_name) <> ''),
    recipient_phone text NOT NULL CHECK (btrim(recipient_phone) <> ''),
    recipient_email text NOT NULL CHECK (btrim(recipient_email) <> ''),
    address jsonb,
    store_snapshot jsonb,
    note text,
    subtotal_vnd bigint NOT NULL CHECK (subtotal_vnd > 0),
    shipping_fee_vnd bigint NOT NULL CHECK (shipping_fee_vnd >= 0),
    total_vnd bigint GENERATED ALWAYS AS (subtotal_vnd + shipping_fee_vnd) STORED,
    bank_snapshot jsonb,
    bank_confirmation_mode text CHECK (bank_confirmation_mode IN ('manual', 'auto')),
    payment_due_at timestamptz,
    link_version bigint NOT NULL DEFAULT 1 CHECK (link_version > 0),
    link_expires_at timestamptz NOT NULL,
    idempotency_key text NOT NULL UNIQUE CHECK (length(idempotency_key) BETWEEN 16 AND 200),
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    delivery_issue boolean NOT NULL DEFAULT false,
    delivery_issue_reason text,
    cancel_requested_at timestamptz,
    cancel_request_note text,
    cancel_kind text CHECK (cancel_kind IN ('before_handover', 'delivery_failed')),
    cancel_reason text,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT order_fulfillment_snapshot CHECK (
        (fulfillment_method = 'delivery' AND address IS NOT NULL
            AND jsonb_typeof(address) = 'object' AND store_snapshot IS NULL)
        OR (fulfillment_method = 'pickup' AND address IS NULL
            AND store_snapshot IS NOT NULL AND jsonb_typeof(store_snapshot) = 'object'
            AND shipping_fee_vnd = 0)
    ),
    CONSTRAINT order_fulfillment_state CHECK (
        (status <> 'ready_for_pickup' OR fulfillment_method = 'pickup')
        AND (status <> 'shipping' OR fulfillment_method = 'delivery')
        AND (fulfillment_method <> 'pickup' OR status <> 'completed' OR payment_status = 'paid')
    ),
    CONSTRAINT order_bank_snapshot CHECK (
        (payment_method = 'cod' AND bank_snapshot IS NULL AND bank_confirmation_mode IS NULL)
        OR (payment_method = 'bank_transfer' AND bank_snapshot IS NOT NULL
            AND jsonb_typeof(bank_snapshot) = 'object' AND bank_confirmation_mode IS NOT NULL)
    ),
    CONSTRAINT order_payment_deadlines CHECK (
        (status = 'awaiting_payment' AND payment_method = 'bank_transfer'
            AND payment_status = 'unpaid' AND payment_due_at IS NOT NULL
            AND payment_due_at > created_at AND bank_confirmation_mode IS NOT NULL)
        OR (status <> 'awaiting_payment' AND payment_due_at IS NULL)
    ),
    CONSTRAINT order_refund_state CHECK (
        (payment_status NOT IN ('refund_pending', 'refunded') OR status = 'cancelled')
        AND (status <> 'cancelled' OR payment_status <> 'paid')
        AND (payment_method <> 'bank_transfer' OR status IN ('awaiting_payment', 'cancelled')
            OR payment_status = 'paid')
    ),
    CONSTRAINT order_link_expiry CHECK (link_expires_at > created_at),
    CONSTRAINT order_cancellation CHECK (
        (status = 'cancelled' AND cancel_kind IS NOT NULL AND cancel_reason IS NOT NULL
            AND btrim(cancel_reason) <> '' AND cancelled_at IS NOT NULL)
        OR (status <> 'cancelled' AND cancel_kind IS NULL AND cancel_reason IS NULL
            AND cancelled_at IS NULL)
    ),
    CONSTRAINT order_delivery_failure_kind CHECK (
        cancel_kind IS NULL OR cancel_kind <> 'delivery_failed' OR fulfillment_method = 'delivery'
    ),
    CONSTRAINT order_delivery_issue CHECK (
        (delivery_issue AND fulfillment_method = 'delivery' AND status = 'shipping'
            AND delivery_issue_reason IS NOT NULL AND btrim(delivery_issue_reason) <> '')
        OR (NOT delivery_issue AND delivery_issue_reason IS NULL)
    ),
    CONSTRAINT order_cancel_request CHECK (
        cancel_requested_at IS NOT NULL OR cancel_request_note IS NULL
    ),
    CONSTRAINT order_terminal_flags CHECK (
        status NOT IN ('completed', 'cancelled')
        OR (NOT delivery_issue AND cancel_requested_at IS NULL AND cancel_request_note IS NULL)
    )
);
CREATE INDEX orders_queue ON orders (status, created_at);
CREATE INDEX orders_updated ON orders (updated_at);
CREATE INDEX orders_payment_due ON orders (payment_due_at, id) WHERE status = 'awaiting_payment';

CREATE TABLE order_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES orders(id),
    sku text NOT NULL REFERENCES products(sku),
    name_snapshot text NOT NULL CHECK (btrim(name_snapshot) <> ''),
    unit_label_snapshot text NOT NULL CHECK (btrim(unit_label_snapshot) <> ''),
    unit_price_vnd bigint NOT NULL CHECK (unit_price_vnd > 0),
    quantity integer NOT NULL CHECK (quantity > 0),
    line_total_vnd bigint GENERATED ALWAYS AS (unit_price_vnd * quantity) STORED,
    delivery_scope_snapshot text NOT NULL CHECK (delivery_scope_snapshot IN ('city', 'near')),
    UNIQUE (order_id, sku)
);
CREATE INDEX order_items_sku ON order_items (sku);

-- Receipts record real money received, including unresolved and additional money.
-- External refund happens before marking a receipt refunded. No payment intents.
CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid REFERENCES orders(id),
    method text NOT NULL CHECK (method IN ('cash', 'bank')),
    amount_vnd bigint NOT NULL CHECK (amount_vnd > 0),
    status text NOT NULL DEFAULT 'succeeded' CHECK (status IN ('succeeded', 'refunded')),
    provider_ref text CHECK (provider_ref IS NULL OR btrim(provider_ref) <> ''),
    applied_to_order boolean NOT NULL DEFAULT false,
    order_link_verified boolean NOT NULL DEFAULT false,
    confirmed_by text NOT NULL CHECK (btrim(confirmed_by) <> ''),
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    refunded_at timestamptz,
    refund_reference text,
    bank_receipt jsonb,
    needs_review boolean NOT NULL DEFAULT false,
    reviewed_by text,
    reviewed_at timestamptz,
    note text,
    CONSTRAINT payment_verified_link CHECK (NOT order_link_verified OR order_id IS NOT NULL),
    CONSTRAINT payment_applied_link CHECK (
        NOT applied_to_order OR (order_id IS NOT NULL AND order_link_verified)
    ),
    CONSTRAINT payment_method_evidence CHECK (
        (method = 'bank' AND provider_ref IS NOT NULL AND bank_receipt IS NOT NULL
            AND jsonb_typeof(bank_receipt) = 'object')
        OR (method = 'cash' AND order_id IS NOT NULL AND provider_ref IS NULL AND bank_receipt IS NULL)
    ),
    CONSTRAINT payment_refund_evidence CHECK (
        (status = 'succeeded' AND refunded_at IS NULL AND refund_reference IS NULL)
        OR (status = 'refunded' AND refunded_at IS NOT NULL AND refund_reference IS NOT NULL
            AND btrim(refund_reference) <> '')
    )
);
CREATE INDEX payments_order ON payments (order_id);
CREATE INDEX payments_review ON payments (needs_review, confirmed_at);
CREATE UNIQUE INDEX payments_provider_ref_unique ON payments (provider_ref) WHERE provider_ref IS NOT NULL;
CREATE UNIQUE INDEX payments_one_applied ON payments (order_id) WHERE applied_to_order;

-- Events and operation receipts are immutable. A successful admin POST, including
-- a no-op with a new key, creates exactly one anchor with key/hash/result.
-- Authorization/CSRF and canonical hashes are enforced by the service, on replay too.
CREATE TABLE business_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid REFERENCES orders(id),
    payment_id uuid REFERENCES payments(id),
    kind text NOT NULL CHECK (btrim(kind) <> ''),
    from_status text,
    to_status text,
    actor text NOT NULL CHECK (btrim(actor) <> ''),
    public_label text,
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    data jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(data) = 'object'),
    source_key text CHECK (source_key IS NULL OR btrim(source_key) <> ''),
    operation_key text,
    request_hash bytea,
    operation_result jsonb,
    CONSTRAINT event_operation_receipt CHECK (
        (operation_key IS NULL AND request_hash IS NULL AND operation_result IS NULL)
        OR (operation_key IS NOT NULL AND length(operation_key) BETWEEN 16 AND 200
            AND request_hash IS NOT NULL AND octet_length(request_hash) = 32
            AND operation_result IS NOT NULL AND jsonb_typeof(operation_result) = 'object'
            AND actor NOT IN ('system', 'guest'))
    ),
    CONSTRAINT event_public_order CHECK (public_label IS NULL OR order_id IS NOT NULL)
);
CREATE INDEX business_events_order ON business_events (order_id, created_at);
CREATE INDEX business_events_payment ON business_events (payment_id, created_at) WHERE payment_id IS NOT NULL;
CREATE UNIQUE INDEX business_events_source_unique ON business_events (source_key);
CREATE UNIQUE INDEX business_events_operation_unique ON business_events (actor, operation_key) WHERE operation_key IS NOT NULL;

CREATE TABLE stock_movements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sku text NOT NULL REFERENCES products(sku),
    delta integer NOT NULL,
    reason text NOT NULL CHECK (reason IN ('order', 'cancel', 'delivery_return', 'restock', 'adjust')),
    order_id uuid REFERENCES orders(id),
    event_id uuid NOT NULL REFERENCES business_events(id),
    actor text NOT NULL CHECK (btrim(actor) <> ''),
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT movement_direction CHECK (
        (reason = 'order' AND delta < 0)
        OR (reason IN ('cancel', 'delivery_return', 'restock') AND delta > 0)
        OR reason = 'adjust'
    ),
    CONSTRAINT movement_order_link CHECK (
        (reason IN ('order', 'cancel', 'delivery_return') AND order_id IS NOT NULL)
        OR (reason IN ('restock', 'adjust') AND order_id IS NULL)
    )
);
CREATE INDEX stock_movements_sku ON stock_movements (sku, created_at);
CREATE INDEX stock_movements_order ON stock_movements (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX stock_movements_event ON stock_movements (event_id);
CREATE UNIQUE INDEX stock_movements_order_once ON stock_movements (order_id, sku) WHERE reason = 'order';
CREATE UNIQUE INDEX stock_movements_return_once ON stock_movements (order_id, sku) WHERE reason IN ('cancel', 'delivery_return');

-- Delivery is at least once; uniqueness prevents duplicate queue rows, not a
-- duplicate provider send after a crash. Provider calls happen outside transactions.
CREATE TABLE notification_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind text NOT NULL CHECK (kind IN (
        'order_placed', 'payment_received', 'shipped', 'ready_pickup', 'completed',
        'cancelled', 'delivery_failed_closed', 'refunded', 'link_resent',
        'owner_new_order', 'owner_payment_due', 'owner_alert'
    )),
    order_id uuid REFERENCES orders(id),
    event_id uuid NOT NULL REFERENCES business_events(id),
    payment_id uuid REFERENCES payments(id),
    channel text NOT NULL CHECK (channel IN ('email', 'telegram')),
    recipient text NOT NULL CHECK (recipient = btrim(recipient) AND recipient <> ''),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'sent', 'exhausted', 'skipped')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    locked_at timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    UNIQUE (event_id, channel, recipient),
    CONSTRAINT outbox_payment_source CHECK (
        kind NOT IN ('payment_received', 'refunded') OR payment_id IS NOT NULL
    ),
    CONSTRAINT outbox_order_source CHECK (
        kind IN ('payment_received', 'refunded', 'owner_alert') OR order_id IS NOT NULL
    ),
    CONSTRAINT outbox_lease CHECK (
        (status = 'processing' AND locked_at IS NOT NULL AND attempts > 0)
        OR (status <> 'processing' AND locked_at IS NULL)
    ),
    CONSTRAINT outbox_attempt_state CHECK (
        (status <> 'pending' OR attempts < 8)
        AND (status <> 'exhausted' OR attempts = 8)
        AND (status <> 'sent' OR attempts > 0)
    ),
    CONSTRAINT outbox_sent_timestamp CHECK (
        (status = 'sent' AND sent_at IS NOT NULL)
        OR (status <> 'sent' AND sent_at IS NULL)
    )
);
CREATE INDEX notification_outbox_queue ON notification_outbox (status, next_attempt_at);
CREATE INDEX notification_outbox_lease ON notification_outbox (locked_at) WHERE status = 'processing';
CREATE INDEX notification_outbox_order ON notification_outbox (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX notification_outbox_payment ON notification_outbox (payment_id) WHERE payment_id IS NOT NULL;

CREATE FUNCTION touch_updated_at() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
CREATE TRIGGER touch_updated_at BEFORE UPDATE ON products
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER touch_updated_at BEFORE UPDATE ON settings
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER touch_updated_at BEFORE UPDATE ON orders
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE FUNCTION reject_audit_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'Operation % is not allowed on %', TG_OP, TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

-- Receipt facts are fixed at insertion. Review and refund updates remain possible.
-- Authorization, account/amount matching and order/payment consistency stay in the service.
CREATE FUNCTION protect_payment_receipt() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment receipts cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.id, NEW.method, NEW.amount_vnd, NEW.provider_ref, NEW.bank_receipt,
        NEW.confirmed_by, NEW.confirmed_at) IS DISTINCT FROM
       ROW(OLD.id, OLD.method, OLD.amount_vnd, OLD.provider_ref, OLD.bank_receipt,
        OLD.confirmed_by, OLD.confirmed_at) THEN
        RAISE EXCEPTION 'Recorded payment facts are immutable' USING ERRCODE = '55000';
    END IF;
    IF (OLD.method = 'cash' OR OLD.applied_to_order OR OLD.status = 'refunded')
        AND ROW(NEW.order_id, NEW.order_link_verified) IS DISTINCT FROM
            ROW(OLD.order_id, OLD.order_link_verified) THEN
        RAISE EXCEPTION 'Cash, applied or refunded receipts keep their verified order link'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.applied_to_order AND NOT NEW.applied_to_order THEN
        RAISE EXCEPTION 'A principal receipt cannot become supplemental' USING ERRCODE = '55000';
    END IF;
    IF NOT OLD.applied_to_order AND NEW.applied_to_order AND NEW.status <> 'succeeded' THEN
        RAISE EXCEPTION 'Only an unrefunded receipt can become principal' USING ERRCODE = '55000';
    END IF;
    IF OLD.status = 'refunded' AND
        ROW(NEW.status, NEW.refunded_at, NEW.refund_reference) IS DISTINCT FROM
        ROW(OLD.status, OLD.refunded_at, OLD.refund_reference) THEN
        RAISE EXCEPTION 'Completed refund facts are immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.order_id IS DISTINCT FROM OLD.order_id
        AND NEW.order_id IS NOT NULL AND NOT NEW.order_link_verified THEN
        RAISE EXCEPTION 'A new bank order link must be verified in the same operation'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER immutable_order_items BEFORE UPDATE OR DELETE ON order_items
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER no_truncate_order_items BEFORE TRUNCATE ON order_items
FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER protect_payment_receipt BEFORE UPDATE OR DELETE ON payments
FOR EACH ROW EXECUTE FUNCTION protect_payment_receipt();
CREATE TRIGGER no_truncate_payments BEFORE TRUNCATE ON payments
FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER immutable_business_events BEFORE UPDATE OR DELETE ON business_events
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER immutable_stock_movements BEFORE UPDATE OR DELETE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER no_truncate_business_events BEFORE TRUNCATE ON business_events
FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER no_truncate_stock_movements BEFORE TRUNCATE ON stock_movements
FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();

-- Explicitly unconfigured values; the service locks unavailable features (05 section 1).
-- Seed the nine real SKUs and the first Owner with deployment scripts and real data.
INSERT INTO settings (key, value) VALUES
    ('store', 'null'),
    ('accepting_orders', 'true'),
    ('enabled_payment_methods', '[]'),
    ('shipping_fee_vnd', 'null'),
    ('free_ship_threshold_vnd', 'null'),
    ('delivery_zones', '[]'),
    ('bank_details', 'null'),
    ('bank_auto_confirm', 'false'),
    ('operator_can_confirm_bank', 'false'),
    ('support', 'null'),
    ('owner_notify', 'null'),
    ('low_stock_threshold', '5'),
    ('max_qty_per_sku', '99'),
    ('bank_hold_min', '60'),
    ('link_ttl_days', '90'),
    ('stale_alert_hours', '24');

COMMENT ON TABLE products IS 'One product per SKU. Stores short name, unit, price, saleable stock, sale status and delivery scope; display catalog content stays in the client.';
COMMENT ON TABLE settings IS 'CHECKs protect boolean, integer and payment-method settings. JSON null is allowed for the two shipping fee settings; nested configuration and the editable key allowlist are validated by the service.';
COMMENT ON TABLE order_items IS 'Purchase snapshots are insert-only; UPDATE, DELETE and TRUNCATE are rejected. Cancellation, returns and refunds preserve these rows.';
COMMENT ON TABLE payments IS 'Receipt facts and completed refund evidence are immutable. Only unrefunded supplemental bank receipts may be relinked after verification. Review and valid apply/refund updates use the shared money service.';
COMMENT ON COLUMN products.stock_qty IS 'Saleable stock: deduct on placement; restore once on pre-handover cancellation or inspected delivery return. Handover does not change stock.';
COMMENT ON TABLE staff_users IS 'Create staff by deployment script. The service increments auth_version on password/role/active changes and prevents disabling or demoting the last active Owner.';
COMMENT ON TABLE staff_sessions IS 'Hash-only absolute sessions; check current staff is_active/role/auth_version on every request. No sliding refresh; delete on logout or expiry.';
COMMENT ON COLUMN orders.bank_snapshot IS 'Immutable account snapshot; QR and receipt matching use this account, not current settings.';
COMMENT ON COLUMN orders.payment_due_at IS 'Manual: reminder only. Auto: cancellation deadline. Clear on confirmation/cancellation. The service exposes this as paymentReminderAt for manual or expiresAt for auto while awaiting payment.';
COMMENT ON COLUMN orders.link_version IS 'HMAC link version; sign outside DB. Replay never rotates or extends a link.';
COMMENT ON TABLE business_events IS 'Append-only business events and successful admin operation receipts, including order, stock, payment and general alert events. Non-order events may have no order_id.';
COMMENT ON COLUMN business_events.actor IS 'Immutable staff UUID as text, system or guest; authorization is checked in the service before every request including replay.';
COMMENT ON COLUMN business_events.data IS 'Immutable event-time facts. No HMAC token, private URL or full request. Render public messages with an explicit field allowlist.';
COMMENT ON COLUMN business_events.operation_result IS 'Minimal successful admin receipt; no token/PII. Reuse actor/key before volatile state/stock checks; hash covers HTTP method, target route and body.';
COMMENT ON COLUMN payments.provider_ref IS 'Bank/account/real transaction reference, canonicalized identically for manual and webhook inputs; never the order number alone.';
COMMENT ON COLUMN payments.order_link_verified IS 'Only verified links can notify a customer. A guessed order ID from transfer content is insufficient.';
COMMENT ON TABLE notification_outbox IS 'Email and Telegram notifications. Event/payment/channel/recipient are fixed at enqueue. Validate their cross-links in the service, preserving historical links after receipt reconciliation.';
COMMENT ON COLUMN notification_outbox.id IS 'Stable provider idempotency key when supported; reuse on every send retry. Enqueue using ON CONFLICT(event_id,channel,recipient) DO NOTHING; prefer customer copy if Owner email is identical.';
COMMENT ON COLUMN notification_outbox.locked_at IS 'Five-minute lease. Claim using FOR UPDATE SKIP LOCKED; completion checks id/locked_at/attempts. Clear lease on pending/sent/exhausted/skipped.';

-- Service transaction requirements (04 sections 4.1-4.7):
-- READ COMMITTED; authenticate/authorize, lookup key/hash, advisory-lock and recheck.
-- New operations lock orders by ID, then receipts by ID, then SKUs by SKU.
-- Placement atomically writes orders/items, stock decrement, event, ledger, outbox.
-- Admin success atomically writes data, one operation receipt, audit and outbox.
-- Check receipt method/amount/account, applied receipt vs payment_status, and event
-- cross-links under those locks. These cross-table rules are not row CHECKs.
-- Cancel before handover returns full quantities once; close delivery failure
-- returns only inspected quantities, records zero quantities in event.data, and
-- changes paid to refund_pending. Refund includes the whole principal + shipping.
-- Refund changes money only. Never reopen cancelled orders on late money.
-- Manual reminders leave orders/stock unchanged; only auto snapshots can expire.
-- Outbox sends outside locks: success on attempt 8 is sent; failure is exhausted.
-- Reclaim expired leases, exhaust crashed attempt-8 leases, skip obsolete alerts,
-- and never recursively alert on a notification_exhausted alert's own failure.
