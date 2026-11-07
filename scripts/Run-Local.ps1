param([Parameter(Mandatory)][ValidateSet('api', 'worker')][string]$Mode)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $serverRoot '.local/dev.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Run scripts/New-LocalEnvironment.ps1 first.' }
$allowed = @('CANE_POSTGRES_ADMIN_PASSWORD', 'CANE_MIGRATION_PASSWORD', 'CANE_API_DB_PASSWORD', 'CANE_WORKER_DB_PASSWORD', 'CANE_REDIS_PASSWORD', 'CANE_POSTGRES_PORT', 'CANE_REDIS_PORT', 'CANE_API_PORT')
$values = @{}
foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
    if ($line -match '^([A-Z_]+)=(.*)$' -and $allowed -contains $Matches[1]) { $values[$Matches[1]] = $Matches[2] }
}
$runtimeSecret = $values[('CANE_' + $Mode.ToUpperInvariant() + '_DB_PASSWORD')]
if ([string]::IsNullOrWhiteSpace($runtimeSecret) -or [string]::IsNullOrWhiteSpace($values['CANE_REDIS_PASSWORD'])) { throw 'Local runtime secrets are missing.' }
$runtimeEnvironment = @{
    CANE_DB_URL = "jdbc:postgresql://127.0.0.1:$($values['CANE_POSTGRES_PORT'])/cane_local"
    CANE_DB_USER = "cane_$Mode"
    CANE_DB_PASSWORD = $runtimeSecret
    CANE_REDIS_HOST = '127.0.0.1'
    CANE_REDIS_PORT = $values['CANE_REDIS_PORT']
    CANE_REDIS_USER = 'cane_runtime'
    CANE_REDIS_PASSWORD = $values['CANE_REDIS_PASSWORD']
    CANE_API_PORT = $values['CANE_API_PORT']
}
$previous = @{}
try {
    foreach ($name in $runtimeEnvironment.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $runtimeEnvironment[$name], 'Process')
    }
    Push-Location -LiteralPath $serverRoot
    try {
        $wrapper = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
        & $wrapper '-B' '-ntp' "-Dspring-boot.run.profiles=local,$Mode" 'spring-boot:run'
        $result = $LASTEXITCODE
    }
    finally { Pop-Location }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $result
