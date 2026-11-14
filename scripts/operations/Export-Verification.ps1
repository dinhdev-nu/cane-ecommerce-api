param([Parameter(Mandatory)][DateTimeOffset]$StartedAt)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Read-TestTotals([string]$Directory) {
    $files = @(Get-ChildItem -LiteralPath (Join-Path $serverRoot $Directory) -Filter 'TEST-*.xml' -File)
    if ($files.Count -eq 0) { throw 'Missing test evidence; verification summary was not written.' }
    $totals = [ordered]@{ suites = $files.Count; tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($file in $files) {
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
        try {
            $document = [Xml.XmlDocument]::new()
            $document.XmlResolver = $null
            $document.Load($reader)
            if ($document.DocumentElement.LocalName -cne 'testsuite') { throw 'Unsupported test evidence format.' }
            foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
                $count = $document.DocumentElement.GetAttribute($attribute)
                if ($count -notmatch '^[0-9]+$') { throw 'Invalid test evidence counter.' }
                $totals[$attribute] += [long]$count
            }
        } finally { $reader.Dispose() }
    }
    if ($totals.tests -eq 0 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.skipped -ne 0) {
        throw 'Tests failed, skipped or were not run; verification summary was not written.'
    }
    return $totals
}

Push-Location -LiteralPath $serverRoot
try {
    $unit = Read-TestTotals 'target/surefire-reports'
    $integration = Read-TestTotals 'target/failsafe-reports'
    $revision = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify verification revision.' }
    $changes = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify verification worktree state.' }
    $jar = 'target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar'
    $migration = 'src/main/resources/db/migration/core/V001__cane_core.sql'
    $summary = [ordered]@{
        startedAt = $StartedAt.ToUniversalTime().ToString('o')
        finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
        revision = $revision.Trim()
        worktreeDirty = ($changes.Count -gt 0)
        artifactSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        coreMigrationSha256 = (Get-FileHash -LiteralPath $migration -Algorithm SHA256).Hash.ToLowerInvariant()
        unit = $unit
        integration = $integration
        scope = 'WP01 automated foundations; staging HTTPS and product acceptance are separate evidence'
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath 'target/foundation-verification.json' -Encoding utf8
    Write-Output 'Redacted foundation verification summary written to target/foundation-verification.json.'
} finally { Pop-Location }
