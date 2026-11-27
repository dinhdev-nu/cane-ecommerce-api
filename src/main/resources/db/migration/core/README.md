# Core migrations

[V001](V001__cane_core.sql) creates the core schema, constraints, triggers and 16 initial settings. Product catalog, stock and staff accounts are initialized separately.

[V002](V002__recipient_retention.sql) adds `orders.recipient_redacted_at`, constraints for redacted orders and functions for the approved recipient-data retention process. Applying the migration installs those functions. An authorized operator applies approved redactions in a separate step.

Run Flyway with the migration role before starting the API and worker. The services validate the schema at startup. See the [migration runbook](../../../../../../docs/core-migration.md) for commands, runtime grants and recovery after a restore.
