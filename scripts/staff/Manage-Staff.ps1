param(
    [Parameter(Mandatory)][ValidateSet('create-owner', 'create-operator', 'reset-password', 'set-role', 'set-active')][string]$Action,
    [Parameter(Mandatory)][string]$Username,
    [ValidateSet('owner', 'operator')][string]$Role,
    [ValidateSet('true', 'false')][string]$Active,
    [string]$Jar
)
$ErrorActionPreference = 'Stop'
$Action = $Action.ToLowerInvariant()
if ($PSBoundParameters.ContainsKey('Role')) { $Role = $Role.ToLowerInvariant() }
if ($PSBoundParameters.ContainsKey('Active')) { $Active = $Active.ToLowerInvariant() }
if (($Action -eq 'set-role') -ne $PSBoundParameters.ContainsKey('Role')) { throw 'Supply -Role only for set-role.' }
if (($Action -eq 'set-active') -ne $PSBoundParameters.ContainsKey('Active')) { throw 'Supply -Active only for set-active.' }
$serverRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($Jar)) { $Jar = Join-Path $serverRoot 'target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar' }
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$commandArguments = @('--action', $Action, '--username', $Username)
if ($Action -eq 'set-role') { $commandArguments += @('--role', $Role) }
if ($Action -eq 'set-active') { $commandArguments += @('--active', $Active) }
& java '-Dloader.main=vn.cane.admin.infrastructure.cli.StaffCommand' '-cp' $jarPath `
    'org.springframework.boot.loader.launch.PropertiesLauncher' @commandArguments
if ($LASTEXITCODE -ne 0) { throw 'Staff command failed; stop and review. Passwords require an interactive console.' }
