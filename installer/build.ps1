# Builds the self-contained layout, the Inno Setup installer and the zip. Run from any directory.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$layout = Join-Path $root 'artifacts\layout'
$output = Join-Path $root 'artifacts\installer'
$version = (Get-Content (Join-Path $root 'version.txt') -Raw).Trim()
Remove-Item -Recurse -Force $layout -ErrorAction SilentlyContinue
# global.json lives in windows\; dotnet picks the SDK from the current directory.
Push-Location (Join-Path $root 'windows')
try {
    dotnet publish src\KeyRelay.Service -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -o $layout
    if ($LASTEXITCODE -ne 0) { throw 'service publish failed' }
    dotnet publish src\KeyRelay.Tray -c Release -r win-x64 --self-contained -o (Join-Path $layout 'Tray')
    if ($LASTEXITCODE -ne 0) { throw 'tray publish failed' }
} finally { Pop-Location }
Remove-Item (Join-Path $layout '*.pdb') -ErrorAction SilentlyContinue
# --smoke builds every page without showing a window; a missing resources.pri or XAML error fails it.
$smoke = Start-Process (Join-Path $layout 'Tray\KeyRelay.Tray.exe') -ArgumentList '--smoke' -PassThru
if (-not $smoke.WaitForExit(60000)) { $smoke.Kill(); throw 'tray smoke timed out' }
if ($smoke.ExitCode -ne 0) { throw "tray smoke failed ($($smoke.ExitCode))" }
$iscc = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup 6 not found (winget install --id JRSoftware.InnoSetup -e)' }
& $iscc "/DAppVersion=$version" (Join-Path $PSScriptRoot 'KeyRelay.iss')
if ($LASTEXITCODE -ne 0) { throw 'ISCC failed' }
Compress-Archive -Path (Join-Path $layout '*') -DestinationPath (Join-Path $output "KeyRelay-$version.zip") -Force
Get-ChildItem $output | Select-Object Name, @{ n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }
