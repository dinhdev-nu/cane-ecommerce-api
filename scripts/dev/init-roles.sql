-- Local PREP-02 infrastructure only: no cane schema, business objects or seeds.
-- psql reads passwords from container environment rather than command arguments.
\set ON_ERROR_STOP on
\getenv migration_password CANE_MIGRATION_PASSWORD
\getenv api_password CANE_API_DB_PASSWORD
\getenv worker_password CANE_WORKER_DB_PASSWORD
SELECT format('CREATE ROLE cane_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'migration_password') \gexec
SELECT format('CREATE ROLE cane_api LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'api_password') \gexec
SELECT format('CREATE ROLE cane_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'worker_password') \gexec
REVOKE ALL ON DATABASE cane_local FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE cane_local TO cane_migrator, cane_api, cane_worker;
GRANT CREATE ON DATABASE cane_local TO cane_migrator;
-- WP01 supplies explicit grants on cane objects after the core migration.
