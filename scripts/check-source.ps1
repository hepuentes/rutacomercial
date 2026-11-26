$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'app\src'
$violations = @()
Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | Where-Object { $_.Extension -in '.kt', '.java', '.xml' } | ForEach-Object {
    $content = [System.IO.File]::ReadAllText($_.FullName)
    if ($content -match '[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]') { $violations += $_.FullName }
}
if ($violations.Count -gt 0) { throw ('Se encontraron controles bidireccionales Unicode: ' + ($violations -join ', ')) }
Write-Output 'Control de texto fuente aprobado: no hay caracteres de control bidireccional Unicode.'
