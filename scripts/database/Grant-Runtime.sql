-- Run with psql as the core schema owner, after Migrate-Core.ps1.
-- Role names are psql variables, never passwords. Identifiers are quoted below.
\set ON_ERROR_STOP on
\if :{?cane_api_role}
\else
\echo 'Required psql variable: cane_api_role'
\quit 1
\endif
\if :{?cane_worker_role}
\else
\echo 'Required psql variable: cane_worker_role'
\quit 1
\endif

BEGIN;
SELECT set_config('cane.grants_api', :'cane_api_role', true) AS api_setting,
       set_config('cane.grants_worker', :'cane_worker_role', true) AS worker_setting
\gset

DO $$
DECLARE runtime_role text; runtime_oid oid;
BEGIN
    IF current_setting('cane.grants_api') = current_setting('cane.grants_worker') THEN
        RAISE EXCEPTION 'API and worker require distinct database roles';
    END IF;
    IF (SELECT nspowner FROM pg_namespace WHERE nspname = 'cane') IS DISTINCT FROM
        (SELECT oid FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'Run grants as the core schema owner';
    END IF;
    FOREACH runtime_role IN ARRAY ARRAY[current_setting('cane.grants_api'), current_setting('cane.grants_worker')]
    LOOP
        SELECT oid INTO runtime_oid FROM pg_roles WHERE rolname = runtime_role;
        IF runtime_oid IS NULL OR runtime_role = current_user THEN
            RAISE EXCEPTION 'Runtime role must exist and differ from the migration role';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE oid = runtime_oid AND
            (NOT rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
            OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = runtime_oid)
            OR has_database_privilege(runtime_oid, current_database(), 'CREATE,TEMP')
            OR EXISTS (SELECT 1 FROM pg_database WHERE datname = current_database() AND datdba = runtime_oid)
            OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname <> 'information_schema'
                AND (nspowner = runtime_oid OR has_schema_privilege(runtime_oid, oid, 'CREATE')))
            OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relowner = runtime_oid)
            OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND p.proowner = runtime_oid)
        THEN
            RAISE EXCEPTION 'Runtime roles must be isolated login roles without ownership, membership or DDL/TEMP privileges';
        END IF;
    END LOOP;
END;
$$;

-- Reconcile direct grants, including old column grants; never grant future tables.
REVOKE ALL ON SCHEMA cane FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
REVOKE ALL ON ALL TABLES IN SCHEMA cane FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
REVOKE ALL ON ALL SEQUENCES IN SCHEMA cane FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA cane FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
DO $$
DECLARE target record;
BEGIN
    FOR target IN
        SELECT c.relname, string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) AS columns
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE n.nspname = 'cane' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
        GROUP BY c.relname
    LOOP
        EXECUTE format('REVOKE ALL PRIVILEGES (%s) ON TABLE cane.%I FROM PUBLIC, %I, %I',
            target.columns, target.relname, current_setting('cane.grants_api'), current_setting('cane.grants_worker'));
    END LOOP;
END;
$$;
-- Schema-local REVOKE cannot cancel PostgreSQL's global default EXECUTE grant.
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
ALTER DEFAULT PRIVILEGES REVOKE ALL ON SEQUENCES FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA cane REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA cane REVOKE ALL ON TABLES FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA cane REVOKE ALL ON SEQUENCES FROM PUBLIC, :"cane_api_role", :"cane_worker_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA cane REVOKE EXECUTE ON FUNCTIONS FROM :"cane_api_role", :"cane_worker_role";

GRANT USAGE ON SCHEMA cane TO :"cane_api_role", :"cane_worker_role";
-- Readiness may inspect the applied schema version; runtime never writes history.
GRANT SELECT ON cane.flyway_schema_history TO :"cane_api_role", :"cane_worker_role";
GRANT SELECT ON cane.staff_users, cane.staff_sessions, cane.products, cane.settings,
    cane.orders, cane.order_items, cane.payments, cane.business_events, cane.stock_movements,
    cane.notification_outbox TO :"cane_api_role";
GRANT INSERT ON cane.staff_sessions, cane.orders, cane.order_items, cane.payments,
    cane.business_events, cane.stock_movements, cane.notification_outbox TO :"cane_api_role";
GRANT DELETE ON cane.staff_sessions TO :"cane_api_role";
GRANT UPDATE (failed_count, locked_until) ON cane.staff_users TO :"cane_api_role";
GRANT UPDATE (price_vnd, stock_qty, is_active, delivery_scope, updated_at, updated_by)
    ON cane.products TO :"cane_api_role";
GRANT UPDATE (value, updated_at, updated_by) ON cane.settings TO :"cane_api_role";
GRANT UPDATE (status, payment_status, payment_due_at, link_version, link_expires_at,
    delivery_issue, delivery_issue_reason, cancel_requested_at, cancel_request_note,
    cancel_kind, cancel_reason, cancelled_at, updated_at) ON cane.orders TO :"cane_api_role";
GRANT UPDATE (order_id, status, applied_to_order, order_link_verified, refunded_at,
    refund_reference, needs_review, reviewed_by, reviewed_at, note) ON cane.payments TO :"cane_api_role";

-- Manual-release worker: dispatch, reminders/alerts and expiry cleanup only.
GRANT SELECT ON cane.products, cane.settings, cane.orders, cane.order_items, cane.payments,
    cane.business_events, cane.stock_movements, cane.notification_outbox TO :"cane_worker_role";
GRANT SELECT (expires_at), DELETE ON cane.staff_sessions TO :"cane_worker_role";
GRANT INSERT ON cane.business_events, cane.notification_outbox TO :"cane_worker_role";
GRANT UPDATE (status, attempts, next_attempt_at, locked_at, last_error, sent_at)
    ON cane.notification_outbox TO :"cane_worker_role";
-- PostgreSQL requires UPDATE on at least one column for SELECT ... FOR UPDATE.
-- Orders' timestamp is controlled by touch_updated_at; receipt ID changes are
-- rejected by protect_payment_receipt. Neither grant permits money/state changes.
GRANT UPDATE (updated_at) ON cane.orders TO :"cane_worker_role";
GRANT UPDATE (id) ON cane.payments TO :"cane_worker_role";
COMMIT;
