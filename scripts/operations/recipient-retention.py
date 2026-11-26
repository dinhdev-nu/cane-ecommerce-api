#!/usr/bin/env python3
"""Review and apply Owner-approved recipient erasure on the CANE VM."""

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import subprocess
from uuid import UUID


def decision_rows(path, approved_digest):
    contents = path.read_bytes()
    if approved_digest and hashlib.sha256(contents).hexdigest() != approved_digest:
        raise ValueError("The approval SHA-256 differs from the journal")

    records = json.loads(contents)
    if not isinstance(records, list) or not records:
        raise ValueError("The journal must have at least one decision")

    seen = set()
    rows = []
    for record in records:
        if not isinstance(record, dict) or set(record) != {"orderId", "caseClosedAt", "decision"}:
            raise ValueError("Each record requires orderId, caseClosedAt and decision")
        order_id = str(UUID(record["orderId"]))
        if order_id in seen:
            raise ValueError("There is more than one decision for an order")
        seen.add(order_id)
        case_closed_at = datetime.fromisoformat(record["caseClosedAt"])
        if case_closed_at.utcoffset() is None:
            raise ValueError("caseClosedAt needs a UTC offset")
        decision = record["decision"]
        if decision not in ("scheduled", "verified_request"):
            raise ValueError("Unsupported decision")
        rows.append(f"('{order_id}', '{case_closed_at.isoformat()}', '{decision}')")
    return ",\n".join(rows)


def transaction(mode, values=None):
    preamble = """SET LOCAL ROLE cane_migrator;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET LOCAL TIME ZONE 'UTC';"""
    if mode == "candidates":
        return f"""BEGIN READ ONLY;
{preamble}
WITH closures AS MATERIALIZED (
    SELECT o.id, greatest(
        coalesce(o.cancelled_at, completed.at, o.updated_at), money.at
    ) AS closed_at
    FROM cane.orders o
    LEFT JOIN LATERAL (
        SELECT max(e.created_at) AS at FROM cane.business_events e
        WHERE e.order_id = o.id AND e.kind = 'order_completed'
    ) completed ON true
    LEFT JOIN LATERAL (
        SELECT max(greatest(p.confirmed_at, p.refunded_at, p.reviewed_at)) AS at
        FROM cane.payments p WHERE p.order_id = o.id
    ) money ON true
    WHERE o.status IN ('completed', 'cancelled') AND o.recipient_redacted_at IS NULL
)
SELECT id, closed_at FROM closures
WHERE closed_at <= statement_timestamp() - interval '90 days'
    AND cane.process_recipient_retention(id, closed_at, 'scheduled', false) = 'eligible'
ORDER BY closed_at, id;
COMMIT;"""
    if mode == "preview":
        return f"""BEGIN READ ONLY;
{preamble}
SELECT order_id, cane.process_recipient_retention(order_id::uuid, closed_at::timestamptz, decision, false)
FROM (VALUES {values}) AS decisions(order_id, closed_at, decision)
ORDER BY order_id;
COMMIT;"""

    missing = ", 'not_found'" if mode == "replay" else ""
    return f"""BEGIN;
{preamble}
DO $retention$
DECLARE
    reviewed record;
    result text;
    report jsonb := '{{}}';
BEGIN
    FOR reviewed IN SELECT * FROM (VALUES {values}) AS decisions(order_id, closed_at, decision)
        ORDER BY order_id
    LOOP
        result := cane.process_recipient_retention(
            reviewed.order_id::uuid, reviewed.closed_at::timestamptz, reviewed.decision, true);
        IF result NOT IN ('redacted', 'already_redacted'{missing}) THEN
            RAISE EXCEPTION 'Order %: %', reviewed.order_id, result;
        END IF;
        report := report || jsonb_build_object(reviewed.order_id, result);
    END LOOP;
    RAISE NOTICE 'Retention results (commit must succeed): %', report;
END $retention$;
COMMIT;"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("candidates", "preview", "apply", "replay"))
    parser.add_argument("--journal", type=Path)
    parser.add_argument("--sha256", help="SHA-256 of the approved copy held outside the restored database")
    options = parser.parse_args()
    writing = options.mode in ("apply", "replay")
    if options.mode != "candidates" and not options.journal:
        parser.error("--journal is required")
    if writing and not options.sha256:
        parser.error("--sha256 is required to apply or replay")
    try:
        values = decision_rows(options.journal, options.sha256) if options.journal else None
    except (OSError, TypeError, ValueError, AttributeError):
        parser.error("Invalid journal or approval digest")

    result = subprocess.run(
        ["docker", "exec", "-i", "cane-production-postgres-1", "psql", "-XAtq", "-v", "ON_ERROR_STOP=1",
         "-U", "cane_production_admin", "-d", "cane_production"],
        input=transaction(options.mode, values),
        text=True,
        check=False,
    )
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
