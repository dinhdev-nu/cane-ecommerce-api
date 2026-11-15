param(
    [Parameter(Mandatory)][ValidateSet('local', 'test')][string]$Environment,
    [string]$Jar,
    [string]$Settings,
    [string]$Stock
)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($Jar)) { $Jar = Join-Path $serverRoot 'target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar' }
if ([string]::IsNullOrWhiteSpace($Settings)) { $Settings = Join-Path $PSScriptRoot 'wp02-settings.json' }
if ([string]::IsNullOrWhiteSpace($Stock)) { $Stock = Join-Path $serverRoot 'src/test/resources/catalog/wp02/stock-fixture.json' }
& (Join-Path $serverRoot 'scripts/catalog/Seed-Catalog.ps1') -Manifest (Join-Path $PSScriptRoot 'wp02-catalog.json') -Environment $Environment -Jar $Jar
& java '-Dloader.main=vn.cane.bootstrap.cli.StorefrontDevSeedCli' '-cp' $Jar `
    'org.springframework.boot.loader.launch.PropertiesLauncher' '--settings' $Settings '--stock' $Stock '--environment' $Environment
if ($LASTEXITCODE -ne 0) { throw 'WP02 mock setup failed; do not use this loader for live data.' }
