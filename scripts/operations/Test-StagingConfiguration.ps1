# Local-only component check of the shipping DB/Redis templates and the built JAR.
# No cloud resources, DNS, public listeners, production data or TLS acceptance.
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$project = 'cane-wp01-16-check-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$work = Join-Path $serverRoot ('.local/' + $project)
$null = New-Item -ItemType Directory -Path $work
$envFile = Join-Path $work 'infrastructure.env'
$overrideFile = Join-Path $work 'override.yaml'
$compose = @('compose', '--project-name', $project, '--env-file', $envFile, '-f',
    (Join-Path $PSScriptRoot 'staging/compose.yaml'), '-f', $overrideFile)
$secrets = @{}
foreach ($name in @('CANE_STAGING_POSTGRES_ADMIN_PASSWORD', 'CANE_STAGING_MIGRATION_PASSWORD', 'CANE_STAGING_API_DB_PASSWORD', 'CANE_STAGING_WORKER_DB_PASSWORD', 'CANE_STAGING_REDIS_PASSWORD')) {
    if ($null -ne [Environment]::GetEnvironmentVariable($name, 'Process')) {
        throw 'Unset staging infrastructure process variables before this isolated local check.'
    }
    $secrets[$name] = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
}
($secrets.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) | Set-Content -LiteralPath $envFile -Encoding utf8
@'
services:
  postgres:
    ports: !override ["127.0.0.1::5432"]
    labels: {vn.cane.scope: wp01-16-local-check}
  redis:
    ports: !override ["127.0.0.1::6379"]
    labels: {vn.cane.scope: wp01-16-local-check}
volumes:
  postgres-data:
    labels: {vn.cane.scope: wp01-16-local-check}
networks:
  default:
    labels: {vn.cane.scope: wp01-16-local-check}
'@ | Set-Content -LiteralPath $overrideFile -Encoding utf8
$processes = [Collections.Generic.List[Diagnostics.Process]]::new()
$previousMigration = @{}
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(20)
$report = [ordered]@{ project = $project; localOnly = $true; secretValidation = $false; configuration = $false; migration = $false;
    runtime = $false; redisFallback = $false; databaseFailClosed = $false; cleaned = $false }

function Compose-Run([string[]]$Arguments) {
    & docker @compose @Arguments >> (Join-Path $work 'infrastructure.log') 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Local configuration container command failed.' }
}
function Start-Runtime([string]$Mode, [hashtable]$Variables) {
    $options = @{ FilePath = (Get-Command java).Source; WorkingDirectory = $serverRoot; ArgumentList = @('-jar',
        ('"' + (Join-Path $serverRoot 'target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar') + '"'),
        ('--spring.profiles.active=staging,' + $Mode)); PassThru = $true; Environment = $Variables;
        RedirectStandardOutput = (Join-Path $work ($Mode + '.log'));
        RedirectStandardError = (Join-Path $work ($Mode + '-stderr.log')) }
    if ($IsWindows) { $options.WindowStyle = 'Hidden' }
    $process = Start-Process @options
    $processes.Add($process)
    return $process
}
function Probe([string]$Method, [int]$Expected, [string]$Code) {
    $path = if ($Method -eq 'GET') { '/api/v1/admin/me' } else { '/api/v1/admin/auth/login' }
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), ($apiOrigin + $path))
    $response = $null
    try {
        if ($Method -eq 'POST') {
            [void]$request.Headers.TryAddWithoutValidation('Origin', 'https://cane.local.test')
            [void]$request.Headers.TryAddWithoutValidation('X-CSRF-Token', $csrf)
            # Explicit cookie for an HTTP loopback harness, not browser/Secure-cookie evidence.
            [void]$request.Headers.TryAddWithoutValidation('Cookie', ('__Host-cane-csrf=' + $csrf))
            $request.Content = [Net.Http.StringContent]::new('{"username":"missing-local-config-fixture","password":"local-only-invalid-password"}', [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ([int]$response.StatusCode -ne $Expected -or $body.error.code -cne $Code -or -not $response.Headers.CacheControl.NoStore) {
            throw 'Local configuration HTTP assertion failed.'
        }
        if ($Method -eq 'GET') { return ($response.Headers.GetValues('X-CSRF-Token') -join '') }
    } finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}

Push-Location -LiteralPath $serverRoot
try {
    $stagingDirectory = Join-Path $PSScriptRoot 'staging'
    $validatorImage = 'postgres:18.6-bookworm@sha256:5c4783a8cc3f0074912164f94dfa1be4e043438f0f707cf968d7af9a0d31a2c8'
    & docker run --rm --network none --label vn.cane.scope=wp01-16-local-check --entrypoint /bin/sh `
        -v ($stagingDirectory + ':/opt/cane:ro') -v ($envFile + ':/tmp/infra.env:ro') `
        $validatorImage /opt/cane/Start-Infrastructure.sh validate /tmp/infra.env *> (Join-Path $work 'validator.log')
    if ($LASTEXITCODE -ne 0) { throw 'Valid local infrastructure configuration was rejected.' }
    $invalidEnv = Join-Path $work 'invalid.env'
    try {
        foreach ($invalidKind in @('placeholder', 'duplicate-secret')) {
            $invalidSecrets = $secrets.Clone()
            $invalidSecrets.CANE_STAGING_API_DB_PASSWORD = if ($invalidKind -eq 'placeholder') { '<placeholder>' }
                else { $secrets.CANE_STAGING_MIGRATION_PASSWORD }
            ($invalidSecrets.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) |
                Set-Content -LiteralPath $invalidEnv -Encoding utf8
            & docker run --rm --network none --label vn.cane.scope=wp01-16-local-check --entrypoint /bin/sh `
                -v ($stagingDirectory + ':/opt/cane:ro') -v ($invalidEnv + ':/tmp/infra.env:ro') `
                $validatorImage /opt/cane/Start-Infrastructure.sh validate /tmp/infra.env >> (Join-Path $work 'validator.log') 2>&1
            if ($LASTEXITCODE -eq 0) { throw 'Invalid local infrastructure configuration was accepted.' }
        }
    } finally { if (Test-Path -LiteralPath $invalidEnv) { Remove-Item -LiteralPath $invalidEnv } }
    $report.secretValidation = $true
    Compose-Run @('config', '--quiet')
    Compose-Run @('up', '--detach', '--wait', '--wait-timeout', '120')
    $report.configuration = $true
    $pgAddress = (& docker @compose port postgres 5432).Trim()
    if ($LASTEXITCODE -ne 0 -or $pgAddress -notmatch '^127\.0\.0\.1:([0-9]+)$') { throw 'Invalid local PostgreSQL binding.' }
    $pgPort = $Matches[1]
    $redisAddress = (& docker @compose port redis 6379).Trim()
    if ($LASTEXITCODE -ne 0 -or $redisAddress -notmatch '^127\.0\.0\.1:([0-9]+)$') { throw 'Invalid local Redis binding.' }
    $redisPort = $Matches[1]
    $migrationVariables = @{ CANE_MIGRATION_URL = "jdbc:postgresql://127.0.0.1:$pgPort/cane_staging";
        CANE_MIGRATION_USER = 'cane_migrator'; CANE_MIGRATION_PASSWORD = $secrets.CANE_STAGING_MIGRATION_PASSWORD }
    foreach ($name in $migrationVariables.Keys) {
        $previousMigration[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $migrationVariables[$name], 'Process')
    }
    & ./scripts/database/Migrate-Core.ps1 *> (Join-Path $work 'migration.log')
    Get-Content -LiteralPath 'scripts/database/Grant-Runtime.sql' -Raw | & docker @compose exec -T postgres `
        psql -U cane_migrator -d cane_staging -v cane_api_role=cane_api -v cane_worker_role=cane_worker -f - `
        *> (Join-Path $work 'grants.log')
    if ($LASTEXITCODE -ne 0) { throw 'Local runtime grants failed.' }
    $report.migration = $true
    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $portProbe.Start(); $apiPort = $portProbe.LocalEndpoint.Port; $portProbe.Stop()
    $apiOrigin = 'http://127.0.0.1:' + $apiPort
    $key = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $variables = @{ CANE_DB_URL = $migrationVariables.CANE_MIGRATION_URL; CANE_DB_USER = 'cane_api';
        CANE_DB_PASSWORD = $secrets.CANE_STAGING_API_DB_PASSWORD; CANE_REDIS_HOST = '127.0.0.1'; CANE_REDIS_PORT = $redisPort;
        CANE_REDIS_USER = 'cane_runtime'; CANE_REDIS_PASSWORD = $secrets.CANE_STAGING_REDIS_PASSWORD;
        CANE_API_PORT = [string]$apiPort; PUBLIC_ORIGIN = 'https://cane.local.test'; CANE_TRUSTED_PROXIES = '127.0.0.1/32';
        LINK_SECRETS = (@{ 'local-check' = $key } | ConvertTo-Json -Compress); LINK_ACTIVE_KID = 'local-check';
        CANE_MIGRATION_URL = ''; CANE_MIGRATION_USER = ''; CANE_MIGRATION_PASSWORD = '';
        CANE_OPERATIONS_DB_URL = ''; CANE_OPERATIONS_DB_USER = ''; CANE_OPERATIONS_DB_PASSWORD = '' }
    $api = Start-Runtime 'api' $variables
    $workerVariables = $variables.Clone()
    $workerVariables.CANE_DB_USER = 'cane_worker'; $workerVariables.CANE_DB_PASSWORD = $secrets.CANE_STAGING_WORKER_DB_PASSWORD
    $worker = Start-Runtime 'worker' $workerVariables
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
    do {
        if ($api.HasExited -or $worker.HasExited) { throw 'Local runtime exited before readiness.' }
        $apiReady = Select-String -LiteralPath (Join-Path $work 'api.log') -SimpleMatch 'Runtime infrastructure verified' -Quiet
        $workerReady = Select-String -LiteralPath (Join-Path $work 'worker.log') -SimpleMatch 'Runtime infrastructure verified' -Quiet
        if ($apiReady -and $workerReady) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if (-not ($apiReady -and $workerReady)) { throw 'Local runtime readiness timed out.' }
    $csrf = Probe 'GET' 401 'AUTH_REQUIRED'
    $null = Probe 'POST' 401 'AUTH_REQUIRED'
    $report.runtime = $true
    Compose-Run @('pause', 'redis')
    try {
        $null = Probe 'POST' 401 'AUTH_REQUIRED'
        if (-not (Select-String -LiteralPath (Join-Path $work 'api.log') -SimpleMatch 'Rate limiter using process fallback' -Quiet)) {
            throw 'Local Redis fallback transition was not observed.'
        }
        $report.redisFallback = $true
    } finally { Compose-Run @('unpause', 'redis') }
    Compose-Run @('stop', 'postgres')
    try { $null = Probe 'POST' 503 'SERVICE_UNAVAILABLE'; $report.databaseFailClosed = $true }
    finally { Compose-Run @('up', '--detach', '--wait', '--wait-timeout', '120', 'postgres') }
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id; $null = $process.WaitForExit(10000) }
        $process.Dispose()
    }
    $client.Dispose()
    foreach ($name in $previousMigration.Keys) { [Environment]::SetEnvironmentVariable($name, $previousMigration[$name], 'Process') }
    try {
        Compose-Run @('down', '--volumes', '--remove-orphans')
        $report.cleaned = $true
        Remove-Item -LiteralPath $envFile
    } finally {
        $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'result.json') -Encoding utf8
        Pop-Location
    }
}
Write-Output ('Local staging-configuration check passed; owned resources cleaned: ' + $project)
