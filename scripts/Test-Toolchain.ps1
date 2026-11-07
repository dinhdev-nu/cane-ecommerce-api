param([switch]$AllowCurrentLocalDocker)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $serverRoot
try {
    $javaVersion = (& java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'Temurin-25\.0\.5\+8') { throw 'Required JDK: Temurin 25.0.5+8.' }
    $wrapper = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
    $mavenVersion = (& $wrapper --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $mavenVersion -notmatch 'Apache Maven 3\.10\.0\b') { throw 'Required Maven: 3.10.0 through the wrapper.' }
    $versions = & docker version --format '{{json .}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker daemon is unavailable.' }
    $versions = $versions | ConvertFrom-Json
    $composeVersion = (& docker compose version --short | Out-String).Trim().TrimStart('v')
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is unavailable.' }
    $matchesLock = $versions.Client.Version -eq '29.9.2' -and $versions.Server.Version -eq '29.9.2' -and $composeVersion -eq '5.6.1'
    $approvedLocal = $AllowCurrentLocalDocker -and $env:GITHUB_ACTIONS -ne 'true' -and
        $versions.Client.Version -eq '27.3.1' -and $versions.Server.Version -eq '27.3.1' -and $composeVersion -eq '2.30.3-desktop.1'
    if (-not $matchesLock -and -not $approvedLocal) { throw 'Required Docker Client/Engine 29.9.2 and Compose 5.6.1; the approved local exception needs -AllowCurrentLocalDocker.' }
    if ($approvedLocal -and -not $matchesLock) { Write-Warning 'Using the current local Docker under explicit owner approval; the target Docker toolchain remains unverified.' }
    Write-Output "Verified Temurin 25.0.5+8; Maven 3.10.0. Actual Docker Client/Engine $($versions.Client.Version)/$($versions.Server.Version); Compose $composeVersion."
} finally { Pop-Location }
