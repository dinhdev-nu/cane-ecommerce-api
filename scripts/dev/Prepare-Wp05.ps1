param(
    [Parameter(Mandatory)][string]$InputFile,
    [Parameter(Mandatory)][string]$Destination,
    [string]$PublicOrigin = 'http://localhost:3000'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$destinationPath = [IO.Path]::GetFullPath($Destination)
if ($destinationPath.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $destinationPath.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Place private configuration and captures outside the code repository.'
}
$inputs = @{}
foreach ($line in [IO.File]::ReadAllLines([IO.Path]::GetFullPath($InputFile))) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    if ($line -notmatch '^(WP05_INPUT_[A-Z_]+)=(.*)$') { throw 'Invalid intake line; values are not printed.' }
    $name = $Matches[1]; $value = $Matches[2].Trim()
    if ($inputs.ContainsKey($name)) { throw 'Duplicate intake field.' }
    if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) { $value = $value.Substring(1, $value.Length - 2) }
    if ($value.Contains("`r") -or $value.Contains("`n")) { throw 'Multiline intake fields are unsupported.' }
    $inputs[$name] = $value
}
function Read-Input([string]$Name) {
    $value = $inputs['WP05_INPUT_' + $Name]
    if ([string]::IsNullOrWhiteSpace($value)) { throw ('Missing intake field: ' + $Name) }
    return $value
}
$origin = [Uri]$PublicOrigin
if ($origin.Scheme -ne 'http' -or -not $origin.IsLoopback -or $origin.AbsolutePath -ne '/' -or $origin.Query -or $origin.Fragment -or $origin.UserInfo) { throw 'This helper is for loopback development only.' }
$fixture = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts/dev/wp02-settings.json') -Raw | ConvertFrom-Json -AsHashtable
$fixture['dataKind'] = 'fixture'
$fixture['values']['owner_notify'] = @{email = (Read-Input 'OWNER_EMAIL'); telegramChatId = (Read-Input 'TELEGRAM_CHAT_ID')}
$fixture['values']['store'] = @{name = (Read-Input 'STORE_NAME'); address = (Read-Input 'STORE_ADDRESS'); openingHours = (Read-Input 'STORE_OPENING_HOURS'); pickupGuidance = (Read-Input 'PICKUP_GUIDANCE')}
$fixture['values']['support'] = @{phone = (Read-Input 'SUPPORT_PHONE'); zaloUrl = (Read-Input 'SUPPORT_ZALO_URL')}
$language = Read-Input 'EMAIL_LANGUAGE'
if ($language -notin @('vi','en')) { throw 'Template language must be vi or en.' }
$runtime = @(
    '# MOCK DEV ONLY: SMTP capture and Telegram mock. Never deploy this file.',
    'NOTIFICATIONS_ENABLED=true', 'SMTP_MODE=capture', 'SMTP_HOST=127.0.0.1', 'SMTP_PORT=1025',
    'EMAIL_FROM=dev@cane.invalid', ('EMAIL_FROM_NAME=' + (Read-Input 'EMAIL_FROM_NAME')),
    ('EMAIL_LANGUAGE=' + $language), 'TELEGRAM_MODE=mock',
    ('NOTIFICATION_CAPTURE_DIRECTORY=' + (Join-Path $destinationPath 'captures')),
    ('PUBLIC_ORIGIN=' + $origin.GetLeftPart([UriPartial]::Authority))
)
# Never overwrite a private file silently; bot secrets remain in the intake, unused by mock mode.
$null = New-Item -ItemType Directory -Path $destinationPath -Force
foreach ($name in @('settings.dev.json','worker.dev.env','copy-notes.txt')) {
    if (Test-Path -LiteralPath (Join-Path $destinationPath $name)) { throw ('Destination already contains ' + $name) }
}
$fixture | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $destinationPath 'settings.dev.json') -Encoding utf8
$runtime | Set-Content -LiteralPath (Join-Path $destinationPath 'worker.dev.env') -Encoding utf8
(Read-Input 'COPY_NOTES') | Set-Content -LiteralPath (Join-Path $destinationPath 'copy-notes.txt') -Encoding utf8
Write-Output 'Prepared private MOCK settings, worker env and copy notes. No messages sent, no database changed.'
