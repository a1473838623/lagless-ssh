# Download an unmodified official Oracle JDK emergency update for JVM packages.
param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
$version = '25.0.5.0.2'
if (!$Destination) { $Destination = Join-Path (Split-Path -Parent $PSScriptRoot) 'target/toolchains' }
$platform = if ($IsWindows) { 'windows' } elseif ($IsMacOS) { 'macos' } else { 'linux' }
$arch = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
if ($arch -eq 'arm64') { $arch = 'aarch64' }
$extension = if ($IsWindows) { 'zip' } else { 'tar.gz' }
$name = "jdk-${version}_${platform}-${arch}_bin.$extension"
$url = "https://download.oracle.com/java/25/archive/$name"
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$archive = Join-Path $Destination $name
$expected = ([string](Invoke-WebRequest -Uri "$url.sha256").Content).Trim().Split(' ')[0].ToLowerInvariant()
if ($expected -notmatch '^[a-f0-9]{64}$') { throw 'Official JDK checksum is invalid' }
Invoke-WebRequest -Uri $url -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Official JDK archive checksum mismatch' }
if ($IsWindows) { Expand-Archive -LiteralPath $archive -DestinationPath $Destination -Force }
else { & tar -xzf $archive -C $Destination; if ($LASTEXITCODE -ne 0) { throw 'JDK extraction failed' } }
$jdkHome = Join-Path $Destination "jdk-$version"
if ($IsMacOS) { $jdkHome = Join-Path $Destination "jdk-$version.jdk/Contents/Home" }
$env:JAVA_HOME = [IO.Path]::GetFullPath($jdkHome)
. (Join-Path $PSScriptRoot 'release-jdk.ps1')
$provenance = Get-ReleaseJdk
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
if ($env:GITHUB_ENV) { "JAVA_HOME=$env:JAVA_HOME" | Add-Content -LiteralPath $env:GITHUB_ENV }
if ($env:GITHUB_PATH) { (Join-Path $env:JAVA_HOME 'bin') | Add-Content -LiteralPath $env:GITHUB_PATH }
Write-Host "Official Oracle JDK $($provenance.runtimeVersion); archive SHA-256 $expected"
