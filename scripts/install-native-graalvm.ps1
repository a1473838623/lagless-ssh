# Install the officially patched GraalVM toolchain in the repository's private build cache.
param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
if (!$IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() -ne 'X64') {
    throw 'This installer currently supports Windows x64 only'
}
if (!$Destination) { $Destination = Join-Path (Split-Path -Parent $PSScriptRoot) 'target/toolchains' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$archive = Join-Path $Destination 'graalvm-jdk-25.0.5_windows-x64_bin.zip'
$url = 'https://gds.oracle.com/download/graal/25/archive/graalvm-jdk-25.0.5_windows-x64_bin.zip'
$expected = '192d0328f9ce9c19f1e73278d638c06635e2d5ff744f11ba1c37b23f5db402d2'
if (!(Test-Path -LiteralPath $archive)) { Invoke-WebRequest -Uri $url -OutFile $archive }
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'Oracle GraalVM 25.0.5 archive SHA-256 mismatch; the native build was stopped'
}
$graalHome = Get-ChildItem -LiteralPath $Destination -Directory -Filter 'graalvm-jdk-25.0.5*' |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'release') } |
    Select-Object -First 1 -ExpandProperty FullName
if (!$graalHome) {
    Expand-Archive -LiteralPath $archive -DestinationPath $Destination -Force
    $graalHome = Get-ChildItem -LiteralPath $Destination -Directory -Filter 'graalvm-jdk-25.0.5*' |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'release') } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$graalHome) { throw 'Oracle GraalVM archive did not contain its release file' }
$env:JAVA_HOME = [IO.Path]::GetFullPath($graalHome)
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
. (Join-Path $PSScriptRoot 'release-jdk.ps1')
$provenance = Get-ReleaseJdk
if ($provenance.javaVersion -ne '25.0.5' -or $provenance.graalvmVersion -ne '25.0.5') {
    throw "Expected patched Oracle GraalVM 25.0.5.0.2; got $($provenance.vendor) $($provenance.javaVersion), GraalVM $($provenance.graalvmVersion)"
}
if ($env:GITHUB_ENV) { "JAVA_HOME=$env:JAVA_HOME" | Add-Content -LiteralPath $env:GITHUB_ENV }
if ($env:GITHUB_PATH) { (Join-Path $env:JAVA_HOME 'bin') | Add-Content -LiteralPath $env:GITHUB_PATH }
Write-Host "Verified official Oracle GraalVM $($provenance.graalvmVersion), JDK $($provenance.javaVersion); archive SHA-256 $expected"
