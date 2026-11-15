param(
    [Parameter(Mandatory)][string]$Output,
    [string]$Jar
)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$revision = & git -C $serverRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Backend revision is unavailable.' }
$template = Join-Path $serverRoot 'scripts/catalog/catalog-template.json'
if ([string]::IsNullOrWhiteSpace($Jar)) {
    $dependencyFile = Join-Path $serverRoot 'target/catalog-classpath.txt'
    if (-not (Test-Path -LiteralPath $dependencyFile)) { throw 'Run Maven compile dependency:build-classpath -Dmdep.outputFile=target/catalog-classpath.txt first.' }
    $classpath = (Join-Path $serverRoot 'target/classes') + [IO.Path]::PathSeparator + (Get-Content -Raw -LiteralPath $dependencyFile).Trim()
    & java '-cp' $classpath 'vn.cane.catalog.infrastructure.cli.CatalogContractCli' $template $revision $Output
} else {
    & java '-Dloader.main=vn.cane.catalog.infrastructure.cli.CatalogContractCli' '-cp' $Jar `
        'org.springframework.boot.loader.launch.PropertiesLauncher' $template $revision $Output
}
if ($LASTEXITCODE -ne 0) { throw 'Catalog contract export failed.' }
