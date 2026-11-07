param([switch]$AllowCurrentLocalDocker)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
$project = 'cane-prep02-check-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$composeArgs = @('compose', '--env-file', '.local/dev.env', '--project-name', $project)
Push-Location -LiteralPath $serverRoot
try {
    & ./scripts/Test-Toolchain.ps1 -AllowCurrentLocalDocker:$AllowCurrentLocalDocker
    if (-not (Test-Path -LiteralPath '.local/dev.env')) { & ./scripts/New-LocalEnvironment.ps1 }
    $beforeImages = @(& docker image ls --no-trunc --quiet | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record Docker image inventory.' }
    @{ project = $project; imageIdsBefore = $beforeImages; startedAt = [DateTime]::UtcNow.ToString('o') } |
        ConvertTo-Json | Set-Content -LiteralPath '.local/last-docker-check.json' -Encoding utf8
    try {
        & docker @composeArgs config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed.' }
        & docker @composeArgs up --detach --wait --wait-timeout 120
        if ($LASTEXITCODE -ne 0) { throw 'Isolated Compose startup failed.' }
        $wrapper = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
        & $wrapper '-B' '-ntp' 'clean' 'verify'
        if ($LASTEXITCODE -ne 0) { throw 'Backend verify failed; inspect target test reports.' }
    } finally {
        & docker @composeArgs down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) { throw "Cleanup failed for owned project $project; manual cleanup is required." }
    }
    Write-Output "Verified backend and removed containers/network/volume of $project."
    Write-Output 'Testcontainers closes its own containers. Review new image IDs against .local/last-docker-check.json before removing newly pulled test-only images.'
} finally { Pop-Location }
