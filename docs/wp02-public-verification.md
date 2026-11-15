# WP02 public storefront verification

2026-11-15, branch `wp02-catalog-storefront-cart`. V001 unchanged. WP01 staging,
HTTPS, representative load and owner acceptance remain separate.

- Standard `scripts/Test-Backend.ps1 -AllowCurrentLocalDocker -EnvFile
  .local/wp02-verification/test.env` executed clean verify: 175 unit and 245 IT,
  with six errors in the new public suite. Existing 233 IT passed.
- Corrected test infrastructure: assertions use ACL-allowed GET (not EXISTS),
  isolated database per case respects immutable ledgers, and the Redis restart
  test uses a free explicit host binding (Docker Desktop reassigns random ports).
  Runtime ACL, migration, immutable triggers and business semantics were preserved.
- `mvnw.cmd -B -ntp spotless:apply verify -Dit.test=PublicStorefrontIT` then passed:
  175 unit, all 12 public IT, package/format/checks. Aggregated XML reports retain
  all 245 passing IT. Raw logs are ignored under `.local/wp02-verification/`.

Public tests use real Spring HTTP, runtime DB roles, PostgreSQL and Redis ACL.
They cover exact GET/400/deny/no-store/allowlists, settings defaults/changes,
availability/missing products, TTL/deadline/late loader races, malformed cache,
timeout/refused connection/OOM/restart fallback, SQL failure, after-commit versus
rollback, real seed/initial-stock writers and replay, and the dev settings loader.

Local measurements in tests are diagnostic bounds, not NFR-001 staging p95 or a
production benchmark. Future order/stock/admin/worker writers must wire and test
invalidation in their owning WP; the harness does not mark those routes verified.

Data/setup: [operator data](operator-data.md), [public runbook](public-storefront.md).
Real FE/BE browser evidence and final package acceptance are recorded in cane-mvp.
