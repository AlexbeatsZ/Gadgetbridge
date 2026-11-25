# Isolated diagnostic resource build only; never installs or flashes a watch.
param(
    [Parameter(Mandatory=$true)][string]$LuaPath,
    [Parameter(Mandatory=$true)][string]$TemplateRoot,
    [Parameter(Mandatory=$true)][string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $TemplateRoot 'watchface\tools\Compiler.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Existing LuaDevTemplate Compiler.exe required' }
Copy-Item -LiteralPath $LuaPath -Destination (Join-Path $TemplateRoot 'watchface\fprj\app\lua\main.lua') -Force
$project = Join-Path $TemplateRoot 'watchface\fprj\NativeReadProbe.fprj'
$xml = '<?xml version="1.0" encoding="utf-16"?><FaceProject DeviceType="362"><Screen Title="NativeReadProbe" Bitmap="preview.png"><Widget Shape="34" Name="app_lua%2Fmain.lua" X="0" Y="0" Width="466" Height="466" Alpha="0" /></Screen></FaceProject>'
[System.IO.File]::WriteAllText($project, $xml, [System.Text.Encoding]::Unicode)
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
& $compiler -b $project $OutputDir 'NativeReadProbe.face' '362991121'
# This compiler version can throw after reporting No Errors. Validate the
# produced format and fixed ID, then let the watch install result decide.
$output = Join-Path $OutputDir 'NativeReadProbe.face'
if (-not (Test-Path -LiteralPath $output)) { throw 'Compiler produced no face file' }
$bytes = [System.IO.File]::ReadAllBytes($output)
if ($bytes.Length -lt 64 -or [BitConverter]::ToUInt32($bytes,0) -ne 0x1234a55a) { throw 'Unexpected face format' }
$bytes[5] = 10
[byte[]]$id = [System.Text.Encoding]::ASCII.GetBytes("362991121`0")
[Array]::Copy($id, 0, $bytes, 40, 10)
[System.IO.File]::WriteAllBytes($output, $bytes)
Write-Output ('Diagnostic face created: ' + $output)
