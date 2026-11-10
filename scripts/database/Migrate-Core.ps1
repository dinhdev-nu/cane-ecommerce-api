$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach ($name in @('CANE_MIGRATION_URL', 'CANE_MIGRATION_USER', 'CANE_MIGRATION_PASSWORD')) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Contains('<') -or $value.StartsWith('CHANGE_ME')) {
        throw "Missing migration configuration: $name"
    }
}
if (-not $env:CANE_MIGRATION_URL.StartsWith('jdbc:postgresql://')) {
    throw 'CANE_MIGRATION_URL must use PostgreSQL JDBC.'
}
$coreLocation = Join-Path $serverRoot 'src/main/resources/db/migration/core'
if (Get-ChildItem -LiteralPath $coreLocation -Recurse -File -Force |
    Where-Object { $_.Name -match '^R__.*\.sql$' } | Select-Object -First 1) {
    throw 'Repeatable migrations are not allowed in core. Use a new versioned migration.'
}
Push-Location -LiteralPath $serverRoot
try {
    $wrapper = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
    # Flyway 12.4 needs *:pending for an absent schema. Core rejects repeatables above.
    # Missing/future applied versions and checksum mismatches still fail validation.
    & $wrapper '-B' '-ntp' '-Dflyway.ignoreMigrationPatterns=*:pending' 'flyway:validate'
    if ($LASTEXITCODE -ne 0) { throw 'Pre-migration validation failed. Migration was not started.' }
    & $wrapper '-B' '-ntp' '-Dflyway.ignoreMigrationPatterns=' 'flyway:migrate'
    if ($LASTEXITCODE -ne 0) { throw 'Migration failed. Stop deployment and inspect rollback/history.' }
    & $wrapper '-B' '-ntp' '-Dflyway.ignoreMigrationPatterns=' 'flyway:validate'
    if ($LASTEXITCODE -ne 0) { throw 'Post-migration validation failed. Do not start API/worker.' }
    Write-Output 'Core migration and post-migration validation completed.'
} finally { Pop-Location }
