# The Windows SDK supplies DirectWrite/Direct2D; no extra runtime is installed.
param([string]$OutputDirectory='')
$ErrorActionPreference='Stop'
if(!$IsWindows){return}
$taskRoot=Split-Path -Parent $PSScriptRoot
if(!$OutputDirectory){$OutputDirectory=Join-Path $taskRoot 'desktop/target/native-renderer'}
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if(!(Test-Path -LiteralPath $vswhere)){throw 'Visual Studio C++ build tools are required for the Windows text renderer'}
$vs=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if(!$vs){throw 'Cannot locate Visual Studio C++ build tools'}
$vcvars=Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
$source=Join-Path $taskRoot 'desktop/src/main/native/windows/renderer.cpp'
$dll=Join-Path $OutputDirectory 'lagless-render.dll'
$object=Join-Path $OutputDirectory 'renderer.obj'
$command=Join-Path $OutputDirectory 'compile-renderer.cmd'
$script="@echo off`r`ncall `"$vcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /LD /O2 /std:c++17 /EHsc /MT /DUNICODE /D_UNICODE /DNOMINMAX /I`"$env:JAVA_HOME/include`" /I`"$env:JAVA_HOME/include/win32`" `"$source`" /Fo`"$object`" /Fe`"$dll`" /link d2d1.lib dwrite.lib user32.lib gdi32.lib ole32.lib`r`nexit /b %errorlevel%`r`n"
[IO.File]::WriteAllText($command,$script,[Text.Encoding]::Default)
& $env:ComSpec /d /c "`"$command`""
if($LASTEXITCODE -ne 0){throw 'Native text renderer compilation failed'}
Write-Host "Built DirectWrite / Direct2D renderer: $dll"
