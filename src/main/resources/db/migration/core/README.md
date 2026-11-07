# Core migrations

WP01 will add `V001__cane_core.sql` from the approved database design.
PREP-02 contains no business migration, schema fixture or seed in this directory.

Run migrations separately using the Maven Flyway plugin and the migration role.
API/worker startup never runs Flyway. See the server README for commands and scope.
