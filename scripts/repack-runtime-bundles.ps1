# Reuse a checksum-verified, patched platform runtime when only the Java application changed.
# This updates ZIP entries in place, preserving Unix launcher permissions and the JDK files.
param([string]$SourceVersion = '0.2.0-rc.8.15')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
$version = (Select-Xml -Path (Join-Path $root 'pom.xml') -XPath "/*[local-name()='project']/*[local-name()='version']").Node.InnerText
if ($version -eq $SourceVersion) { throw 'Source and destination versions must differ' }
$checksumsPath = Join-Path $dist "release-$SourceVersion/SHA256SUMS.txt"
if (!(Test-Path -LiteralPath $checksumsPath)) { throw "Missing prior release checksums: $checksumsPath" }
$checksums = @{}
foreach ($line in Get-Content -LiteralPath $checksumsPath) {
    if ($line -match '^([a-f0-9]{64})\s+(.+)$') { $checksums[$Matches[2]] = $Matches[1] }
}
$jarByEdition = @{ 'lagless' = (Join-Path $dist 'lagless.jar'); 'lagless-terminal' = (Join-Path $dist 'lagless-terminal.jar') }
$common = @{ 'lagless/README.md' = (Join-Path $root 'README.md'); 'lagless/docs/core.md' = (Join-Path $root 'docs/core.md') }
foreach ($name in @($jarByEdition.Values) + @($common.Values)) {
    if (!(Test-Path -LiteralPath $name)) { throw "Missing new application file: $name" }
}
function Replace-Entry([IO.Compression.ZipArchive]$zip, [string]$entryName, [string]$file) {
    $old = $zip.GetEntry($entryName)
    if (!$old) { throw "Missing entry in prior bundle: $entryName" }
    $attributes = $old.ExternalAttributes
    $old.Delete()
    $newEntry = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file, $entryName, [IO.Compression.CompressionLevel]::Optimal)
    $newEntry.ExternalAttributes = $attributes
}
function Mark-ZipAsUnix([string]$archivePath) {
    # ZipArchive writes the DOS creator flag on Windows even when the external
    # attributes carry Unix mode bits. Unzip ignores 0755 until this flag is 3.
    $bytes = [IO.File]::ReadAllBytes($archivePath)
    $eocd = -1
    for ($i = $bytes.Length - 22; $i -ge [Math]::Max(0, $bytes.Length - 65557); $i--) {
        if ([BitConverter]::ToUInt32($bytes, $i) -eq 0x06054b50) { $eocd = $i; break }
    }
    if ($eocd -lt 0) { throw "ZIP end record missing: $archivePath" }
    $count = [BitConverter]::ToUInt16($bytes, $eocd + 10)
    $position = [int][BitConverter]::ToUInt32($bytes, $eocd + 16)
    for ($i = 0; $i -lt $count; $i++) {
        if ([BitConverter]::ToUInt32($bytes, $position) -ne 0x02014b50) { throw "ZIP central entry missing: $archivePath" }
        $bytes[$position + 5] = 3
        $nameLength = [BitConverter]::ToUInt16($bytes, $position + 28)
        $extraLength = [BitConverter]::ToUInt16($bytes, $position + 30)
        $commentLength = [BitConverter]::ToUInt16($bytes, $position + 32)
        $position += 46 + $nameLength + $extraLength + $commentLength
    }
    [IO.File]::WriteAllBytes($archivePath, $bytes)
}
foreach ($platform in @('linux-x64', 'macos-arm64')) {
    foreach ($edition in @('lagless', 'lagless-terminal')) {
        $sourceName = "$edition-$SourceVersion-$platform.zip"
        $source = Join-Path $dist $sourceName
        if (!(Test-Path -LiteralPath $source) -or !$checksums.ContainsKey($sourceName)) { throw "Missing verified prior bundle: $sourceName" }
        $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $checksums[$sourceName]) { throw "Prior bundle checksum mismatch: $sourceName" }
        $destination = Join-Path $dist "$edition-$version-$platform.zip"
        if (Test-Path -LiteralPath $destination) { throw "Destination already exists: $destination" }
        $read = [IO.Compression.ZipFile]::OpenRead($source)
        try {
            $jdkEntry = $read.GetEntry('lagless/build-jdk.json')
            if (!$jdkEntry) { throw "Missing JDK provenance: $sourceName" }
            $reader = [IO.StreamReader]::new($jdkEntry.Open())
            try { $jdk = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            if ($jdk.vendor -ne 'Oracle Corporation' -or $jdk.javaVersion -ne '25.0.5.0.2' -or !$jdk.fullJdkSecurityBaselineSatisfied) {
                throw "Prior bundle does not contain the approved patched JDK: $sourceName"
            }
        } finally { $read.Dispose() }
        Copy-Item -LiteralPath $source -Destination $destination
        try {
            $zip = [IO.Compression.ZipFile]::Open($destination, [IO.Compression.ZipArchiveMode]::Update)
            try {
                Replace-Entry $zip 'lagless/lagless.jar' $jarByEdition[$edition]
                foreach ($entry in $common.Keys) { Replace-Entry $zip $entry $common[$entry] }
                if ($edition -eq 'lagless') {
                    foreach ($doc in @('desktop.md', 'plugins.md', 'native-image.md')) {
                        Replace-Entry $zip "lagless/docs/$doc" (Join-Path $root "docs/$doc")
                    }
                }
                $releaseNote = Join-Path $root "docs/releases/$version.md"
                if (!(Test-Path -LiteralPath $releaseNote)) { throw "Missing release note: $releaseNote" }
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $releaseNote, "lagless/docs/releases/$version.md", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
                # Compress-Archive in older releases marked Unix launchers 0644.
                # Give the bundled runtime its own executable Java instead of
                # silently falling back to an unrelated system installation.
                foreach ($item in $zip.Entries) {
                    $path = $item.FullName
                    if ($path -eq 'lagless/lagless' -or
                        ($path.StartsWith('lagless/runtime/bin/') -and !$path.EndsWith('/')) -or
                        $path -in @('lagless/runtime/lib/jexec', 'lagless/runtime/lib/jspawnhelper')) {
                        $item.ExternalAttributes = -2115174400 # Unix regular file 0755
                    }
                }
            } finally { $zip.Dispose() }
            Mark-ZipAsUnix $destination
        } catch {
            Remove-Item -LiteralPath $destination -Force
            throw
        }
        Write-Host "Repacked $destination from verified $sourceName with the $version application"
    }
}
