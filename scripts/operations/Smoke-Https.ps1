param(
    [Parameter(Mandatory)][uri]$Origin,
    [Parameter(Mandatory)][string]$Username,
    [Parameter(Mandatory)][ValidateSet('owner', 'operator')][string]$ExpectedRole,
    [string]$ReportPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ExpectedRole = $ExpectedRole.ToLowerInvariant()
# No password argument, transcript, certificate callback, redirect or HTTP fallback.
if (-not $Origin.IsAbsoluteUri -or $Origin.Scheme -cne 'https' -or
    $Origin.UserInfo -or $Origin.AbsolutePath -ne '/' -or $Origin.Query -or $Origin.Fragment) {
    throw 'Origin must be an HTTPS origin without credentials, path, query or fragment.'
}
if ([string]::IsNullOrWhiteSpace($Username)) { throw 'Username is required.' }
$originText = $Origin.GetLeftPart([UriPartial]::Authority)
if (-not $ReportPath) {
    $ReportPath = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) `
        ('.local/https-smoke-' + [Guid]::NewGuid().ToString('N') + '.json')
}
# Reserve a new evidence file before transmitting credentials; never overwrite evidence.
$null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($ReportPath)))
$reportFile = [IO.File]::Open($ReportPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
$steps = [Collections.Generic.List[object]]::new()
$state = @{ step = 'initialize'; failedAt = $null; csrf = $null; passed = $false }
$started = [DateTimeOffset]::UtcNow
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.CookieContainer = [Net.CookieContainer]::new()
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)

function Require([bool]$Condition) {
    if (-not $Condition) { throw 'HTTPS smoke assertion failed.' }
}

function Header($Response, [string]$Name) {
    if ($Response.Headers.Contains($Name)) { return ($Response.Headers.GetValues($Name) -join ',') }
    return ''
}

function Invoke-Api([string]$Step, [string]$Method, [string]$Path, [int]$Status,
    [string]$Body, [string]$Csrf, [string]$RequestOrigin = $originText, [string]$ErrorCode) {
    $state.step = $Step
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), ($originText + $Path))
    $response = $null
    try {
        if ($Method -ne 'GET') {
            [void]$request.Headers.TryAddWithoutValidation('Origin', $RequestOrigin)
            [void]$request.Headers.TryAddWithoutValidation('Sec-Fetch-Site', 'same-origin')
            if ($Csrf) { [void]$request.Headers.TryAddWithoutValidation('X-CSRF-Token', $Csrf) }
            $request.Content = [Net.Http.StringContent]::new($Body, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        Require ([int]$response.StatusCode -eq $Status)
        Require ((Header $response 'Cache-Control') -match '(^|[,\s])no-store($|[,\s])')
        $requestId = Header $response 'X-Request-ID'
        Require ($requestId -cmatch '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
        Require ((Header $response 'X-Content-Type-Options') -eq 'nosniff')
        Require ((Header $response 'Referrer-Policy') -eq 'no-referrer')
        Require ((Header $response 'Strict-Transport-Security') -match '(^|[;\s])max-age=[1-9][0-9]*($|[;\s])')
        $csp = Header $response 'Content-Security-Policy'
        Require ($csp -match "(^|;\s*)frame-ancestors 'none'(;|$)")
        Require ($csp -match "(^|;\s*)object-src 'none'(;|$)")
        Require ($csp -match "(^|;\s*)base-uri 'self'(;|$)")
        Require (-not $response.Headers.Contains('Access-Control-Allow-Origin'))
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = $null
        if ($Status -eq 204) { Require ($raw.Length -eq 0) }
        else {
            Require ($response.Content.Headers.ContentType.MediaType -eq 'application/json')
            Require ($raw.Length -le 65536)
            $json = ConvertFrom-Json -InputObject $raw -AsHashtable -Depth 12
            if ($ErrorCode) {
                Require ($json.error.code -ceq $ErrorCode)
                Require ($json.error.requestId -ceq $requestId)
            }
        }
        $setCookies = @()
        if ($response.Headers.Contains('Set-Cookie')) { $setCookies = @($response.Headers.GetValues('Set-Cookie')) }
        Require (-not ($setCookies | Where-Object { $_ -match '^JSESSIONID=' }))
        $token = Header $response 'X-CSRF-Token'
        $steps.Add([ordered]@{ step = $Step; status = $Status; requestId = $requestId; passed = $true })
        return @{ json = $json; csrf = $token; setCookies = $setCookies }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Require-Cookie($Reply, [string]$Name, [bool]$HttpOnly) {
    $values = @($Reply.setCookies | Where-Object { $_.StartsWith($Name + '=', [StringComparison]::Ordinal) })
    Require ($values.Count -eq 1)
    $line = $values[0]
    Require ($line -match ';\s*Secure(;|$)')
    Require ($line -match ';\s*Path=/(;|$)')
    Require ($line -match ';\s*SameSite=Lax(;|$)')
    Require ($line -notmatch ';\s*Domain=')
    Require (($line -match ';\s*HttpOnly(;|$)') -eq $HttpOnly)
}

function Require-Csrf($Reply) {
    Require ($Reply.csrf -cmatch '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
    $cookie = $handler.CookieContainer.GetCookies($Origin)['__Host-cane-csrf']
    Require ($null -ne $cookie -and $cookie.Value -ceq $Reply.csrf)
}

function Require-User($Json, [bool]$Me) {
    $expected = if ($Me) { @('user', 'permissions', 'csrfToken', 'sessionExpiresAt') }
        else { @('user', 'permissions', 'sessionExpiresAt') }
    Require (@(Compare-Object @($Json.Keys | Sort-Object) @($expected | Sort-Object)).Count -eq 0)
    Require (@(Compare-Object @($Json.user.Keys | Sort-Object) @('id', 'role', 'username')).Count -eq 0)
    Require (@(Compare-Object @($Json.permissions.Keys) @('canConfirmBank')).Count -eq 0)
    Require ($Json.user.role -ceq $ExpectedRole)
    Require ($Json.user.username -ceq $Username.Trim().ToLowerInvariant())
    Require ($Json.permissions.canConfirmBank -is [bool])
    if ($ExpectedRole -eq 'owner') { Require $Json.permissions.canConfirmBank }
}

try {
    $bootstrap = Invoke-Api -Step 'anonymous-me' -Method GET -Path '/api/v1/admin/me' -Status 401 -ErrorCode AUTH_REQUIRED
    Require-Cookie $bootstrap '__Host-cane-csrf' $false
    Require-Csrf $bootstrap
    $state.csrf = $bootstrap.csrf
    $oldCsrf = $state.csrf
    $null = Invoke-Api -Step 'cross-origin-rejected' -Method POST -Path '/api/v1/admin/auth/logout' -Status 403 `
        -Body '{}' -Csrf $state.csrf -RequestOrigin 'https://invalid.example' -ErrorCode FORBIDDEN
    $null = Invoke-Api -Step 'missing-csrf-rejected' -Method POST -Path '/api/v1/admin/auth/logout' -Status 403 `
        -Body '{}' -ErrorCode FORBIDDEN
    $state.step = 'password-prompt'
    $securePassword = Read-Host 'Staging test account password (hidden)' -AsSecureString
    try {
        $password = [Net.NetworkCredential]::new('', $securePassword).Password
        $loginBody = @{ username = $Username; password = $password } | ConvertTo-Json -Compress
        $login = Invoke-Api -Step 'login' -Method POST -Path '/api/v1/admin/auth/login' -Status 200 `
            -Body $loginBody -Csrf $state.csrf
    } finally {
        $password = $null
        $loginBody = $null
        $securePassword.Dispose()
    }
    $state.csrf = $login.csrf
    Require-Cookie $login '__Host-cane-session' $true
    Require-Cookie $login '__Host-cane-csrf' $false
    Require-Csrf $login
    Require ($state.csrf -cne $oldCsrf)
    Require-User $login.json $false
    $expires = [DateTimeOffset]::Parse($login.json.sessionExpiresAt)
    Require ($expires -gt [DateTimeOffset]::UtcNow -and $expires -le [DateTimeOffset]::UtcNow.AddHours(8))
    $session = $handler.CookieContainer.GetCookies($Origin)['__Host-cane-session']
    Require ($null -ne $session -and $session.Value -cmatch '^[A-Za-z0-9_-]{43}$')
    Require ($session.Expires.ToUniversalTime() -le $expires.UtcDateTime)
    $me = Invoke-Api -Step 'authenticated-me' -Method GET -Path '/api/v1/admin/me' -Status 200
    Require-User $me.json $true
    Require ($me.json.sessionExpiresAt -ceq $login.json.sessionExpiresAt)
    Require ($me.json.csrfToken -ceq $state.csrf)
    $null = Invoke-Api -Step 'old-csrf-rejected' -Method POST -Path '/api/v1/admin/auth/logout' -Status 403 `
        -Body '{}' -Csrf $oldCsrf -ErrorCode FORBIDDEN
    $null = Invoke-Api -Step 'session-survives-rejected-logout' -Method GET -Path '/api/v1/admin/me' -Status 200
    $logout = Invoke-Api -Step 'logout' -Method POST -Path '/api/v1/admin/auth/logout' -Status 204 -Body '{}' -Csrf $state.csrf
    Require-Cookie $logout '__Host-cane-session' $true
    Require-Cookie $logout '__Host-cane-csrf' $false
    Require ($null -eq $handler.CookieContainer.GetCookies($Origin)['__Host-cane-session'])
    Require ($null -eq $handler.CookieContainer.GetCookies($Origin)['__Host-cane-csrf'])
    $again = Invoke-Api -Step 'me-after-logout' -Method GET -Path '/api/v1/admin/me' -Status 401 -ErrorCode AUTH_REQUIRED
    Require-Csrf $again
    $state.csrf = $again.csrf
    $null = Invoke-Api -Step 'repeated-logout' -Method POST -Path '/api/v1/admin/auth/logout' -Status 204 -Body '{}' -Csrf $state.csrf
    $state.passed = $true
} catch {
    # Do not print the response, request, exception, headers, password or tokens.
    $state.failedAt = $state.step
    $steps.Add([ordered]@{ step = $state.step; passed = $false })
} finally {
    # A response may have issued cookies before a later assertion failed.
    $cleanupSession = $handler.CookieContainer.GetCookies($Origin)['__Host-cane-session']
    $cleanupCsrf = $handler.CookieContainer.GetCookies($Origin)['__Host-cane-csrf']
    if ($null -ne $cleanupSession -and $null -ne $cleanupCsrf) {
        try {
            $null = Invoke-Api -Step 'cleanup-own-session' -Method POST -Path '/api/v1/admin/auth/logout' -Status 204 `
                -Body '{}' -Csrf $cleanupCsrf.Value
        } catch { $steps.Add([ordered]@{ step = 'cleanup-own-session'; passed = $false }) }
    }
    $client.Dispose()
    $report = [ordered]@{ origin = $originText; expectedRole = $ExpectedRole; failedAt = $state.failedAt; startedAt = $started.ToString('o');
        finishedAt = [DateTimeOffset]::UtcNow.ToString('o'); passed = $state.passed; steps = $steps.ToArray() }
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($report | ConvertTo-Json -Depth 6))
        $reportFile.Write($bytes, 0, $bytes.Length)
    } finally { $reportFile.Dispose() }
}
if (-not $state.passed) {
    Write-Error ('HTTPS smoke failed at step: ' + $state.failedAt + '. Inspect the redacted report and server requestId.') -ErrorAction Continue
    exit 1
}
Write-Output ('HTTPS smoke passed for role ' + $ExpectedRole + '; redacted report saved.')
