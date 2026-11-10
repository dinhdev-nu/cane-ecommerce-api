# Core migrations

`V001__cane_core.sql` implements the approved database design: 10 core tables,
122 columns, constraints/indexes, 3 functions, 11 triggers and 16 initial settings.
Flyway owns the transaction and schema/history creation. No SKU, staff or bank inbox
is created by this migration.

Run migrations separately using the Maven Flyway plugin and the migration role.
API/worker startup never runs Flyway. See
[the migration runbook](../../../../../../docs/core-migration.md) for commands,
validation and failure handling. Runtime grants and trigger behavior are checked
in WP01-02 before this schema is used in a long-lived environment.
