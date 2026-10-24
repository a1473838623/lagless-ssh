# Default: official complete emergency update. Native builds may explicitly use
# Oracle's certificate-file mitigation, pinned to a verified Windows toolchain.
function Get-ReleaseJdk {
    param([switch]$AllowCertificateMitigation)
    if (!$env:JAVA_HOME) { throw 'JAVA_HOME must point to the release JDK' }
    $releasePath = Join-Path $env:JAVA_HOME 'release'
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $releasePath) {
        if ($line -match '^([A-Z_]+)="(.*)"$') { $values[$Matches[1]] = $Matches[2] }
    }
    $version = $values['JAVA_VERSION']
    $parts = @($version.Split('.') | ForEach-Object { [int]$_ })
    $baseline = @(25, 0, 5, 0, 2)
    $comparison = 0
    for ($i = 0; $i -lt [Math]::Max($parts.Count, $baseline.Count); $i++) {
        $actual = if ($i -lt $parts.Count) { $parts[$i] } else { 0 }
        $required = if ($i -lt $baseline.Count) { $baseline[$i] } else { 0 }
        if ($actual -ne $required) { $comparison = [Math]::Sign($actual - $required); break }
    }
    # Oracle's fixed GraalVM 25.0.5 archive reports JAVA_VERSION=25.0.5 and
    # GRAALVM_VERSION=25.0.5 in its release file. The Oracle advisory names
    # this same update 25.0.5.0.2; unlike the standalone JDK, GraalVM omits
    # the CPU suffix from the standard release properties.
    $fixedGraal = $values['IMPLEMENTOR'] -eq 'Oracle Corporation' -and
        $values['GRAALVM_VERSION'] -eq '25.0.5' -and $version -eq '25.0.5'
    $mitigation = $null
    $blocked = Join-Path $env:JAVA_HOME 'lib/security/blocked.certs'
    if ($AllowCertificateMitigation -and $comparison -lt 0) {
        $root = Split-Path -Parent $PSScriptRoot
        $policy = Get-Content -LiteralPath (Join-Path $root 'security/cve-2026-91403/policy.json') -Raw | ConvertFrom-Json
        if (!$IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() -ne 'X64' -or $version -ne $policy.graalvmVersion -or $values['GRAALVM_VERSION'] -ne $policy.graalvmVersion) { throw 'Certificate mitigation is restricted to the verified Windows x64 Oracle GraalVM version' }
        if ((Get-FileHash -LiteralPath $releasePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $policy.graalvmReleaseSha256) { throw 'Certificate mitigation requires the verified original GraalVM build' }
        if ((Get-FileHash -LiteralPath $blocked -Algorithm SHA256).Hash.ToLowerInvariant() -ne $policy.blockedCertificatesSha256) { throw 'Official certificate mitigation is missing or has a different checksum' }
        $mitigation = [ordered]@{cve=$policy.cve;mode='official-certificate-blocklist-at-build-time';sourceJdkVersion=$policy.sourceJdkVersion;sourceArchive=$policy.sourceArchive;sourceArchiveSha256=$policy.sourceArchiveSha256;graalvmArchiveSha256=$policy.graalvmArchiveSha256}
    }
    if ($values['IMPLEMENTOR'] -ne 'Oracle Corporation' -or $parts[0] -ne 25 -or ($comparison -lt 0 -and !$mitigation -and !$fixedGraal)) {
        throw "Release requires official Oracle JDK/GraalVM based on JDK 25.0.5.0.2 or a later JDK 25 security update (CVE-2026-91403). Found: $($values['IMPLEMENTOR']) $version."
    }
    if (!(Test-Path -LiteralPath $blocked)) { throw 'Release JDK certificate blocklist is missing' }
    return [ordered]@{
        vendor = $values['IMPLEMENTOR']
        javaVersion = $version
        runtimeVersion = $values['JAVA_RUNTIME_VERSION']
        graalvmVersion = $values['GRAALVM_VERSION']
        releaseSha256 = (Get-FileHash -LiteralPath $releasePath -Algorithm SHA256).Hash.ToLowerInvariant()
        blockedCertificatesSha256 = (Get-FileHash -LiteralPath $blocked -Algorithm SHA256).Hash.ToLowerInvariant()
        securityBaseline = '25.0.5.0.2'
        fullJdkSecurityBaselineSatisfied = ($comparison -ge 0 -or $fixedGraal)
        certificateMitigation = $mitigation
        advisory = 'https://ops.java/emudhra'
    }
}
