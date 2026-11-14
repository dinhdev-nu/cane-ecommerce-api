param(
    [Parameter(Mandatory)][string]$Manifest,
    [Parameter(Mandatory)][ValidateSet('local', 'test', 'staging', 'production')][string]$Environment,
    [string]$Jar
)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($Jar)) { $Jar = Join-Path $serverRoot 'target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar' }
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
& java '-Dloader.main=vn.cane.catalog.infrastructure.cli.InitialStockCli' '-cp' $jarPath `
    'org.springframework.boot.loader.launch.PropertiesLauncher' '--manifest' $manifestPath '--environment' $Environment
if ($LASTEXITCODE -ne 0) { throw 'Initial stock failed or outcome is unknown. Preserve Owner/key/body for retry; do not generate a new key.' }
