param()
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
$localPath = Join-Path $serverRoot '.local'
$envPath = Join-Path $localPath 'dev.env'
if (Test-Path -LiteralPath $envPath) { throw 'Local environment already exists; secrets were not overwritten.' }
New-Item -ItemType Directory -Path $localPath -Force | Out-Null
$lines = foreach ($name in @('CANE_POSTGRES_ADMIN_PASSWORD', 'CANE_MIGRATION_PASSWORD', 'CANE_API_DB_PASSWORD', 'CANE_WORKER_DB_PASSWORD', 'CANE_REDIS_PASSWORD')) {
    $secret = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    "$name=$secret"
}
$lines += @('CANE_POSTGRES_PORT=55432', 'CANE_REDIS_PORT=16379', 'CANE_API_PORT=8080')
[System.IO.File]::WriteAllLines($envPath, $lines, [System.Text.UTF8Encoding]::new($false))
if (-not $IsWindows) {
    [System.IO.File]::SetUnixFileMode($envPath, [System.IO.UnixFileMode]::UserRead -bor [System.IO.UnixFileMode]::UserWrite)
}
Write-Output 'Generated .local/dev.env for isolated local use. Secrets are not printed or tracked by Git.'
