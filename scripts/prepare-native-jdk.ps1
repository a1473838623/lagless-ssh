# Build in a private toolchain copy; never patch the user's installed JDK.
param([string]$Archive = '')
$ErrorActionPreference = 'Stop'
if (!$IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() -ne 'X64') { throw 'This official mitigation archive is Windows x64 only' }
$root = Split-Path -Parent $PSScriptRoot
$policy = Get-Content -LiteralPath (Join-Path $root 'security/cve-2026-91403/policy.json') -Raw | ConvertFrom-Json
$tools = Join-Path $root 'target/toolchains'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
if (!$Archive) {
    $Archive = Join-Path $tools 'oracle-graalvm-25-windows-x64.zip'
    if (!(Test-Path -LiteralPath $Archive)) { Invoke-WebRequest -Uri $policy.graalvmArchive -OutFile $Archive }
}
if ((Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $policy.graalvmArchiveSha256) { throw 'Official GraalVM archive checksum mismatch' }
$certificateFile = Join-Path $root 'security/cve-2026-91403/windows/blocked.certs'
if ((Get-FileHash -LiteralPath $certificateFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $policy.blockedCertificatesSha256) { throw 'Official certificate patch checksum mismatch' }
$destination = Join-Path $tools 'native-cve-2026-91403'
Expand-Archive -LiteralPath $Archive -DestinationPath $destination -Force
$jdkHome = Join-Path $destination 'graalvm-jdk-25.0.4+7.1'
Copy-Item -LiteralPath $certificateFile -Destination (Join-Path $jdkHome 'lib/security/blocked.certs') -Force
$env:JAVA_HOME = [IO.Path]::GetFullPath($jdkHome)
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
. (Join-Path $PSScriptRoot 'release-jdk.ps1')
Get-ReleaseJdk -AllowCertificateMitigation | ConvertTo-Json -Depth 8 | Write-Host
if ($env:GITHUB_ENV) { "JAVA_HOME=$env:JAVA_HOME" | Add-Content -LiteralPath $env:GITHUB_ENV }
if ($env:GITHUB_PATH) { (Join-Path $env:JAVA_HOME 'bin') | Add-Content -LiteralPath $env:GITHUB_PATH }
