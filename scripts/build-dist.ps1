# Build standalone editions with their own platform-specific Java runtime.
# -Edition terminal builds only core + CLI; no desktop sources/dependencies.
param(
    [ValidateSet('terminal', 'desktop', 'both')]
    [string]$Edition = 'both',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot 'release-jdk.ps1')
$releaseJdk = Get-ReleaseJdk
$version = (Select-Xml -Path pom.xml -XPath "/*[local-name()='project']/*[local-name()='version']").Node.InnerText
$platform = if ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)) { 'windows' } elseif ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::OSX)) { 'macos' } else { 'linux' }
$arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
$distRoot = [System.IO.Path]::GetFullPath((Join-Path $root 'dist'))
New-Item -ItemType Directory -Force $distRoot | Out-Null

if (-not $SkipBuild) {
    if ($Edition -eq 'terminal') {
        & mvn -q -pl cli -am '-Dmaven.test.skip=true' package
    } else {
        & mvn -q '-Dmaven.test.skip=true' package
    }
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
}
$required = if ($Edition -eq 'terminal') { @('cli/target/lagless-cli-standalone.jar','core/target/lagless-core.jar') } else { @('cli/target/lagless-cli-standalone.jar','desktop/target/lagless-desktop-standalone.jar','core/target/lagless-core.jar') }
foreach ($artifact in $required) { if (-not (Test-Path -LiteralPath (Join-Path $root $artifact))) { throw "Missing build artifact: $artifact" } }
if($platform -eq 'windows' -and $Edition -ne 'terminal'){
    & (Join-Path $PSScriptRoot 'build-native-renderer.ps1')
}

$editions = if ($Edition -eq 'both') { @('terminal', 'desktop') } else { @($Edition) }
foreach ($currentEdition in $editions) {
    # java.desktop is deliberately absent from the terminal runtime. Full
    # integration tests exercise the core with the GUI module unavailable.
    $modules = 'java.base,java.logging,java.management,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.unsupported'
    if ($currentEdition -eq 'desktop') { $modules += ',java.desktop,java.xml' }
    $artifact = if ($currentEdition -eq 'terminal') { 'cli/target/lagless-cli-standalone.jar' } else { 'desktop/target/lagless-desktop-standalone.jar' }
    $assetName = if ($currentEdition -eq 'terminal') { 'lagless-terminal.jar' } else { 'lagless.jar' }
    $bundleName = if ($currentEdition -eq 'terminal') { 'lagless-terminal' } else { 'lagless' }
    $stageRoot = Join-Path $distRoot ('stage-' + [guid]::NewGuid().ToString('N'))
    $stage = Join-Path $stageRoot 'lagless'
    New-Item -ItemType Directory -Force $stage | Out-Null
    try {
        Write-Host "Linking $currentEdition runtime ($modules)"
        & (Join-Path $env:JAVA_HOME ('bin/' + $(if ($platform -eq 'windows') { 'jlink.exe' } else { 'jlink' }))) --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output (Join-Path $stage 'runtime')
        if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }
        $releaseJdk | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'build-jdk.json') -Encoding utf8NoBOM
        Copy-Item -LiteralPath (Join-Path $root $artifact) -Destination (Join-Path $stage 'lagless.jar')
        foreach ($file in @('lagless.cmd', 'lagless', 'README.md')) { Copy-Item -LiteralPath (Join-Path $root $file) -Destination $stage }
        New-Item -ItemType Directory -Force (Join-Path $stage 'docs') | Out-Null
        Copy-Item -LiteralPath (Join-Path $root 'docs/core.md') -Destination (Join-Path $stage 'docs/core.md')
        if(Test-Path -LiteralPath (Join-Path $root 'docs/releases')){Copy-Item -LiteralPath (Join-Path $root 'docs/releases') -Destination (Join-Path $stage 'docs') -Recurse -Force}
        if ($currentEdition -eq 'desktop') {
            if($platform -eq 'windows'){
                Copy-Item -LiteralPath (Join-Path $root 'desktop/target/native-renderer/lagless-render.dll') -Destination $stage
                Copy-Item -LiteralPath (Join-Path $root 'desktop/target/native-renderer/lagless-render.dll') -Destination $distRoot -Force
            }
            foreach ($file in @('desktop.md', 'plugins.md', 'native-image.md', 'desktop-preview.png')) {
                Copy-Item -LiteralPath (Join-Path $root "docs/$file") -Destination (Join-Path $stage "docs/$file")
            }
        }
        $zip = Join-Path $distRoot "$bundleName-$version-$platform-$arch.zip"
        Compress-Archive -LiteralPath $stage -DestinationPath $zip -CompressionLevel Optimal -Force
        Copy-Item -LiteralPath (Join-Path $root $artifact) -Destination (Join-Path $distRoot $assetName)
        Write-Host "Wrote $zip ($([math]::Round((Get-Item -LiteralPath $zip).Length / 1MB, 1)) MB)"
    } finally {
        $resolvedStage = [System.IO.Path]::GetFullPath($stageRoot)
        if (-not $resolvedStage.StartsWith($distRoot + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe staging cleanup path' }
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
Copy-Item -LiteralPath (Join-Path $root 'core/target/lagless-core.jar') -Destination (Join-Path $distRoot 'lagless-core.jar')
