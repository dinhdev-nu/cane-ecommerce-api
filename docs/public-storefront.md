# Public storefront — WP02

The API profile opens exactly GET `/api/v1/shop` and GET `/api/v1/prices` in addition
to the existing staff authentication routes. Other methods/routes keep default deny.
Both use request IDs, structured errors and `Cache-Control: no-store`. GET bodies or
query parameters are rejected. No CORS wildcard, session, cookie, reservation, order
or operational write is introduced by storefront reads.

`admin.application.SettingsReader` reads settings in one SQL statement. Public shop
mapping exposes the thirteen contract fields only, never owner notifications,
permissions or bank details. Incomplete bank configuration suppresses bank transfer;
delivery needs a configured fee (zero is valid) and valid zones. Opening hours remain
display text; they do not control accepting orders. SQL/configuration failure is 503.
Missing release configuration keeps null/empty arrays, without free shipping defaults.

`catalog.infrastructure.JdbcPricesReader` reads products plus the stock threshold in
one statement. Paused takes precedence over out, then positive stock below threshold
is low; equality is in_stock. Exact stock, names and sale units stay out of the DTO.
Missing products are omitted. Invalid money fails the read rather than publishing an
unsafe JSON integer. `serverTime` is freshly assigned to every response in UTC.

## Cache and future writers

Only `items` and internal `expiresAt` are stored at
`cane:<environment>:cache:prices:v1`. No response timestamp or private data is cached.
The deadline is the instant immediately before SQL starts plus fifteen seconds.
Serialization consumes that budget; SET uses only remaining milliseconds. A loader
that has consumed the deadline cannot write. Hits do not write or extend TTL, and
the deadline is checked before returning cached data. Slow/concurrent loaders may
replace a newer entry with an older snapshot, but retain the original fixed deadline.
No Redis lock, stale fallback or invalidation queue is required for nine SKUs.

Redis read/malformed payload/timeout/disconnect falls back to SQL. Cache write/OOM
does not fail a successful SQL read. If no usable cache exists and SQL fails, return
503. Debug diagnostics on `vn.cane.catalog.application.PublicPricesService` record
hit/miss/read_fallback/write_failed without logging payloads or secrets. Existing
Redis timeouts and the noeviction/ACL environment namespace remain unchanged.

Writers call `PricesInvalidation.afterCommit()` **inside their write transaction**.
It registers DEL with `Transactions.afterCommit`: no DEL on rollback; failure after
commit cannot undo database success. Existing writers are catalog seed (only inserts)
and initial stock (only positive changes, not replay/no-op). Both runtime services
and operational CLIs use the same hook. CLI uses a separate DB credential and opens
a bounded Redis client only after commit. Provide `CANE_ENVIRONMENT` matching the
explicit `--environment`, plus the existing `CANE_REDIS_HOST/PORT/USER/PASSWORD`.
Missing/failed Redis is best effort, with at most the original fifteen-second cache
age; never retry a committed database operation with a replacement idempotency key.
Both CLI entry points print a fixed warning through their own output if DEL fails,
even with library logging disabled. The warning discloses no exception or connection
details; the database operation remains successful with exit code zero. Check Redis
and environment configuration rather than repeating the committed operation.

WP03/WP06/worker must use this hook for price, active flag, delivery scope, stock and
`low_stock_threshold` changes, including place/reserve, cancellation/return, delivery
failure resolution and manual adjustment. Those routes are not implemented by WP02;
their owning package must verify transaction and replay behavior on the real writer.

## Data and contract export

Approved names/units and researched initial prices are in
`scripts/catalog/catalog-template.json`. Release flags/scopes remain explicitly
unconfirmed there; this template still fails release validation until completed.
Market decisions and source links live in `cane-mvp/docs/pm/wp02-market-prices.md`.
The separately marked `scripts/dev/wp02-catalog.json` is local/test only, with all
cards active and zero initial stock unless test setup explicitly supplies stock.
`scripts/dev/wp02-settings.json` explicitly marks the complete dev configuration as
fixture: owner-supplied shop/contact, mock shipping fee/zones, payment methods and a
VietinBank account marked MOCK / DO NOT TRANSFER. The number is the owner-specified
dev phone, not a verified receiving account. See [operator data and go-live checks](operator-data.md).

With the separate operational DB credential and matching Redis environment loaded
into the process, run `scripts/dev/Initialize-Wp02.ps1 -Environment local`. It seeds
the dev catalog and validates/imports all sixteen settings plus fixture stock. The
Java loader refuses staging/production and non-fixture files. It directly sets mock
stock on a disposable dev/test DB; it is not an audited operational inventory writer.
Do not run it against a database whose real business history must be retained.

Export the machine-readable contract from the **compiled** `CatalogSkus.ALL` and
approved seed metadata, recording the checked-out backend revision:

```powershell
./mvnw.cmd -B -ntp compile dependency:build-classpath -Dmdep.outputFile=target/catalog-classpath.txt
./scripts/catalog/Export-CatalogContract.ps1 -Output target/catalog-contract.json
# After normal verify, use its packaged artifact instead:
./scripts/catalog/Export-CatalogContract.ps1 -Jar target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar -Output target/catalog-contract.json
```

Compile/export alone is not test evidence. Backend CI exports only after the normal
full verification. FE CI checks out a pinned backend revision, compiles its exporter
and compares that manifest with FE IDs/units and actual referenced assets. Missing
source, wrong revision, duplicate/missing/extra SKU or broken assets must fail closed.

## Verification

Review changes first, then run the standard runner:

```powershell
pwsh -File scripts/Test-Backend.ps1 -AllowCurrentLocalDocker -EnvFile .local/wp02-verification/test.env
```

The selected env file is ignored, holds isolated test ports/secrets and is not a
production configuration. `PublicStorefrontIT` uses disposable PostgreSQL/Redis and
the real HTTP security chain. Cache unit tests use a controlled Clock; integration
tests check real TTL, ACL, malformed cache, timeout/OOM, SQL faults, after-commit/
rollback, concurrent late loaders and the existing CLI writers. Auth/security and
the remaining backend suites continue through the same runner. Full results belong
in the WP02 execution report, not inferred from this runbook or previous WP01 tests.
