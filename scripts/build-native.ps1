# Build, exercise and package the real desktop application, without a JRE or JAR.
param([switch]$SkipTests,[switch]$SkipUnitTests,[switch]$SkipNativeChecks,[switch]$ReuseMetadata,[switch]$SkipCompilation,[switch]$UseOfficialCertificateMitigation,[ValidatePattern("^[a-zA-Z0-9-]+$")][string]$OutputFolder="bundle")
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot 'release-jdk.ps1')
$releaseJdk = Get-ReleaseJdk -AllowCertificateMitigation:$UseOfficialCertificateMitigation
if (!$releaseJdk.graalvmVersion) { throw 'Native release requires an official fixed GraalVM distribution, not a plain JDK' }
$version=(Select-Xml -Path pom.xml -XPath "/*[local-name()='project']/*[local-name()='version']").Node.InnerText
$platform=if($IsWindows){'windows'}elseif($IsMacOS){'macos'}else{'linux'}
$arch=[System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
$build=Join-Path $root 'desktop/target/native'
$metadata=Join-Path $build 'metadata'
$stage=Join-Path $build $OutputFolder
if ($SkipCompilation) {
    $previousJdkPath = Join-Path $stage 'build-jdk.json'
    if (!(Test-Path -LiteralPath $previousJdkPath)) { throw 'Cannot repackage an EXE without its original fixed-JDK build provenance' }
    $previousJdk = Get-Content -LiteralPath $previousJdkPath -Raw | ConvertFrom-Json
    if ($previousJdk.releaseSha256 -ne $releaseJdk.releaseSha256 -or $previousJdk.blockedCertificatesSha256 -ne $releaseJdk.blockedCertificatesSha256) {
        throw 'The existing EXE was built with a different JDK; rebuild it before packaging'
    }
    if ($releaseJdk.certificateMitigation) {
        $capture = Get-Content -LiteralPath (Join-Path $stage 'security/native-blocklist-build.json') -Raw | ConvertFrom-Json
        if (!$capture.initializedAtBuildTime -or $capture.sha256 -ne $releaseJdk.blockedCertificatesSha256) { throw 'Existing EXE has no matching certificate capture report' }
    }
}
New-Item -ItemType Directory -Force $build,$metadata,$stage,(Join-Path $root 'dist') | Out-Null
if(!$SkipCompilation){
    if($SkipTests){& mvn -q '-Dmaven.test.skip=true' package}
    elseif($SkipUnitTests){& mvn -q '-DskipTests' install}
    else{& mvn -q verify install}
    if($LASTEXITCODE -ne 0){throw 'Maven build failed'}
    if($IsWindows){
        & (Join-Path $PSScriptRoot 'build-native-renderer.ps1')
        Copy-Item -LiteralPath (Join-Path $root 'desktop/target/native-renderer/lagless-render.dll') -Destination $stage -Force
    }
}
if(!$SkipTests){
& mvn -q -pl desktop dependency:build-classpath '-Dmdep.outputFile=target/native-test-classpath.txt' '-DincludeScope=test'
if($LASTEXITCODE -ne 0){throw 'Cannot resolve fixture classpath'}
$separator=[IO.Path]::PathSeparator
$cp=(Join-Path $root 'desktop/target/test-classes')+$separator+(Join-Path $root 'desktop/target/classes')+$separator+(Get-Content desktop/target/native-test-classpath.txt -Raw).Trim()
}
$jar=Join-Path $root 'desktop/target/lagless-desktop-standalone.jar'
$java=Join-Path $env:JAVA_HOME ('bin/'+$(if($IsWindows){'java.exe'}else{'java'}))
function Run-Verification([bool]$agent){
    $run=Join-Path $build ('verify-'+[guid]::NewGuid().ToString('N'))
    $fixture=Join-Path $run 'fixture'
    New-Item -ItemType Directory -Force $fixture | Out-Null
    $startArgs=@('-cp',('"'+$cp+'"'),'dev.lagless.desktop.NativeTestServer',('"'+$fixture+'"'))
    $options=@{FilePath=$java;ArgumentList=$startArgs;PassThru=$true;RedirectStandardOutput=(Join-Path $run 'server.out');RedirectStandardError=(Join-Path $run 'server.err')}
    if($IsWindows){$options.WindowStyle='Hidden'}
    $server=Start-Process @options
    try{
        $ready=Join-Path $fixture 'fixture.json'
        $deadline=[datetime]::UtcNow.AddSeconds(30)
        while(!(Test-Path -LiteralPath $ready)){
            if($server.HasExited -or [datetime]::UtcNow -gt $deadline){throw "Fixture startup failed: $(Get-Content (Join-Path $run 'server.err') -Raw)"}
            Start-Sleep -Milliseconds 100
        }
        $config=Get-Content -LiteralPath $ready -Raw | ConvertFrom-Json -AsHashtable
        $config.work=Join-Path $run 'user'
        $scenario=Join-Path $run 'scenario.json'
        $config | ConvertTo-Json | Set-Content -LiteralPath $scenario -Encoding utf8NoBOM
        if($agent){
            & $java "-agentlib:native-image-agent=config-output-dir=$metadata" '--enable-native-access=ALL-UNNAMED' '-Djava.awt.headless=false' -cp $jar dev.lagless.desktop.NativeDesktopMain --verify $scenario
            if($LASTEXITCODE -ne 0){throw "Application verification failed; see $run"}
        }else{
            # A Windows GUI-subsystem EXE is asynchronous when invoked with &.
            # Wait explicitly and capture diagnostics before stopping its fixture.
            $nativeOptions=@{FilePath=(Join-Path $stage $(if($IsWindows){'Lagless.exe'}else{'Lagless'}));ArgumentList=@('--verify',('"'+$scenario+'"'));PassThru=$true;RedirectStandardOutput=(Join-Path $run 'native.out');RedirectStandardError=(Join-Path $run 'native.err')}
            if($IsWindows){$nativeOptions.WindowStyle='Hidden'}
            $native=Start-Process @nativeOptions
            if(!$native.WaitForExit(120000)){$native.Kill();throw "Native verification timeout: $run"}
            Get-Content -LiteralPath (Join-Path $run 'native.out'),(Join-Path $run 'native.err')
            if($native.ExitCode -ne 0){throw "Native verification failed: $run"}
        }
        Copy-Item -LiteralPath (Join-Path $config.work 'report.json') -Destination (Join-Path $build $(if($agent){'jvm-report.json'}else{'native-report.json'}))
        Copy-Item -LiteralPath (Join-Path $config.work 'desktop.png') -Destination (Join-Path $build $(if($agent){'jvm-desktop.png'}else{'native-desktop.png'}))
    }finally{if(!$server.HasExited){$server.Kill();$server.WaitForExit()}}
}
if(!$SkipCompilation){
    if(!$ReuseMetadata -and !$SkipTests){Run-Verification $true}
    # JSch selects algorithms by class name. Include its shipped JCA implementations,
    # including algorithms not negotiated by the fixture; optional BouncyCastle is absent.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($jar)
    try{
        $names=$zip.Entries.FullName | Where-Object {$_ -match '^(META-INF/versions/[0-9]+/)?com/jcraft/jsch/(?!bc/|jzlib/).*\.class$' -and $_ -notmatch '\$'} | ForEach-Object {($_ -replace '^META-INF/versions/[0-9]+/','' -replace '/','.' -replace '\.class$','')} | Sort-Object -Unique
        $reflection=@($names | ForEach-Object {@{name=$_;allDeclaredConstructors=$true}})
        $reflection+=@(@{name="java.rmi.Remote";allDeclaredMethods=$true},@{name="java.rmi.MarshalledObject";allDeclaredConstructors=$true;allDeclaredMethods=$true;allDeclaredFields=$true},@{name="java.lang.String";fields=@(@{name="serialVersionUID"},@{name="serialPersistentFields"})},@{name="javax.swing.plaf.basic.BasicGraphicsUtils";allDeclaredMethods=$true},@{name='javax.swing.PopupFactory';allDeclaredMethods=$true})
        # JDK 25 HarfBuzz uses FFM plus MethodHandles.lookup().findStatic for
        # its callbacks. JNI metadata alone does not register either facility.
        $reflection+=@{name='sun.font.HBShaper';allDeclaredMethods=$true}
        $reflection+=@{name='javax.swing.plaf.basic.BasicOptionPaneUI';allDeclaredMethods=$true}
        ConvertTo-Json -InputObject $reflection -Depth 6 | Set-Content -LiteralPath (Join-Path $metadata 'reflect-config.json') -Encoding utf8NoBOM
        # Apache MINA SSHD serializes Java String fields while constructing a
        # session channel. GraalVM 25 uses the reachability-metadata schema for
        # serialization; reflection-only entries do not register this support.
        $reachabilityPath=Join-Path $metadata 'reachability-metadata.json'
        $reachability=Get-Content -LiteralPath $reachabilityPath -Raw | ConvertFrom-Json
        $reachability.reflection=@($reachability.reflection)+@(@{type='java.lang.String';serializable=$true})
        ConvertTo-Json -InputObject $reachability -Depth 100 | Set-Content -LiteralPath $reachabilityPath -Encoding utf8NoBOM
    }finally{$zip.Dispose()}
    if($IsWindows){
        # Native window messages call peer methods that an offscreen agent run
        # cannot observe. Register the matching JDK's complete peer/event surface,
        # including non-public inherited callbacks such as handleExpose.
        $awtClasses=& (Join-Path $env:JAVA_HOME 'bin/jar.exe') tf (Join-Path $env:JAVA_HOME 'jmods/java.desktop.jmod')
        if($LASTEXITCODE -ne 0){throw 'Cannot enumerate Windows AWT callbacks'}
        $awtJni=@($awtClasses | Where-Object {$_ -match '^classes/(sun/awt/(windows|shell|datatransfer|image|dnd)/|sun/java2d/|sun/font/|java/awt/(event|image|im|font|dnd)/).*\.class$' -or $_ -match '^classes/java/awt/[^/]+\.class$' -or $_ -match '^classes/java/text/Attributed(String|CharacterIterator).*\.class$'} | ForEach-Object {
            @{name=($_ -replace '^classes/','' -replace '/','.' -replace '\.class$','');allDeclaredMethods=$true;allDeclaredConstructors=$true;allDeclaredFields=$true}
        })
        $awtJni+=@{name='dev.lagless.desktop.NativeGraphics';allDeclaredMethods=$true}
        # AwtWindow::Create calls FindClass for Popup$HeavyWeightWindow even
        # when creating another JWindow. Missing JNI registration makes native
        # peer creation return null and every heavyweight menu silently fail.
        # These Swing classes are outside the java.awt/sun.awt patterns above.
        foreach($popupClass in @('javax.swing.Popup','javax.swing.Popup$HeavyWeightWindow','javax.swing.Popup$DefaultFrame','javax.swing.PopupFactory','javax.swing.PopupFactory$HeavyWeightPopup','javax.swing.JWindow','sun.awt.ModalExclude')){
            $awtJni+=@{name=$popupClass;allDeclaredMethods=$true;allDeclaredConstructors=$true;allDeclaredFields=$true}
        }
        ConvertTo-Json -InputObject $awtJni -Depth 6 | Set-Content -LiteralPath (Join-Path $metadata 'jni-config.json') -Encoding utf8NoBOM
    }
    # The JVM edition needs ImageInfo for its false result. The native compiler
    # supplies that class from its own module and rejects a duplicated SDK class.
    $nativeInput=Join-Path $build 'native-input.jar'
    Copy-Item -LiteralPath $jar -Destination $nativeInput -Force
    $inputZip=[IO.Compression.ZipFile]::Open($nativeInput,[IO.Compression.ZipArchiveMode]::Update)
    try{$entry=$inputZip.GetEntry('org/graalvm/nativeimage/ImageInfo.class');if($entry){$entry.Delete()}}finally{$inputZip.Dispose()}
    $image=Join-Path $env:JAVA_HOME ('bin/'+$(if($IsWindows){'native-image.cmd'}else{'native-image'}))
    $flags=@('--no-fallback','--parallelism=4','-J-Xmx4g','-O1','-march=compatibility','-Djava.awt.headless=false',"-H:ConfigurationFileDirectories=$metadata",'--enable-url-protocols=http,https','-H:IncludeResources=.*[.](properties|caps|png|svg)$','-cp',$nativeInput,'dev.lagless.desktop.NativeDesktopMain','-o',(Join-Path $stage 'Lagless'))
    if($IsWindows){$flags=@('-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS','-H:NativeLinkerOption=/ENTRY:mainCRTStartup')+$flags}
    if ($releaseJdk.certificateMitigation) {
        $featureClasses = Join-Path $build 'security-feature-classes'
        New-Item -ItemType Directory -Path $featureClasses -Force | Out-Null
        & (Join-Path $env:JAVA_HOME 'bin/javac.exe') --add-modules org.graalvm.nativeimage -d $featureClasses (Join-Path $PSScriptRoot 'native-image/SecurityBlocklistFeature.java')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot compile native certificate capture check' }
        $flags[$flags.IndexOf('-cp') + 1] = $nativeInput + [IO.Path]::PathSeparator + $featureClasses
        $securityReport = Join-Path $stage 'security/native-blocklist-build.json'
        if (Test-Path -LiteralPath $securityReport) { Remove-Item -LiteralPath $securityReport }
        $flags += @('--features=dev.lagless.build.SecurityBlocklistFeature','-J--add-opens=java.base/sun.security.util=ALL-UNNAMED',"-J-Dlagless.build.blocklist=$(Join-Path $env:JAVA_HOME 'lib/security/blocked.certs')","-J-Dlagless.build.blocklist.sha256=$($releaseJdk.blockedCertificatesSha256)","-J-Dlagless.build.blocklist.report=$securityReport")
    }
    # An argument file also avoids cmd.exe interpreting regular-expression pipes.
    $argumentFile=Join-Path $build 'native-image.args'
    ($flags | ForEach-Object {'"'+($_.Replace('\','/').Replace('"','\"'))+'"'}) | Set-Content -LiteralPath $argumentFile -Encoding utf8NoBOM
    & $image "@$argumentFile"
    if($LASTEXITCODE -ne 0){throw 'Native Image compilation failed'}
    if ($releaseJdk.certificateMitigation) {
        if (!(Test-Path -LiteralPath $securityReport)) { throw 'Native compilation did not confirm certificate blocklist capture' }
        Copy-Item -LiteralPath (Join-Path $root 'security/cve-2026-91403/windows/blocked.certs') -Destination (Join-Path $stage 'security/blocked.certs') -Force
        Copy-Item -LiteralPath (Join-Path $root 'security/cve-2026-91403/policy.json'),(Join-Path $root 'security/cve-2026-91403/README.md') -Destination (Join-Path $stage 'security') -Force
    }
    if($IsWindows){
        $manifestTool=Get-ChildItem -LiteralPath 'C:/Program Files (x86)/Windows Kits/10/bin' -Filter mt.exe -Recurse | Where-Object {$_.Directory.Name -eq $arch} | Sort-Object FullName -Descending | Select-Object -First 1
        if(!$manifestTool){throw 'Windows SDK manifest tool (mt.exe) is required for HiDPI'}
        & $manifestTool.FullName -nologo -manifest (Join-Path $root 'desktop/src/main/native/windows/lagless.manifest') "-outputresource:$(Join-Path $stage 'Lagless.exe');#1"
        if($LASTEXITCODE -ne 0){throw 'Cannot embed the PerMonitorV2 DPI manifest'}
        & (Join-Path $PSScriptRoot 'set-windows-icon.ps1') -Executable (Join-Path $stage 'Lagless.exe')
    }
    New-Item -ItemType Directory -Force (Join-Path $stage 'lib') | Out-Null
    $fontConfig=Join-Path $env:JAVA_HOME 'lib/fontconfig.bfc'
    if(Test-Path -LiteralPath $fontConfig){Copy-Item -LiteralPath $fontConfig -Destination (Join-Path $stage 'lib/fontconfig.bfc')}
}
Copy-Item -LiteralPath (Join-Path $root 'README.md') -Destination $stage
if ($releaseJdk.certificateMitigation) {
    Copy-Item -LiteralPath (Join-Path $root 'security/cve-2026-91403/windows/blocked.certs') -Destination (Join-Path $stage 'security/blocked.certs') -Force
    Copy-Item -LiteralPath (Join-Path $root 'security/cve-2026-91403/policy.json'),(Join-Path $root 'security/cve-2026-91403/README.md') -Destination (Join-Path $stage 'security') -Force
}
$releaseJdk | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $stage 'build-jdk.json') -Encoding utf8NoBOM
New-Item -ItemType Directory -Force (Join-Path $stage 'docs') | Out-Null
foreach($doc in @('desktop.md','plugins.md','native-image.md','core.md','desktop-preview.png')){Copy-Item -LiteralPath (Join-Path $root "docs/$doc") -Destination (Join-Path $stage 'docs')}
if(Test-Path -LiteralPath (Join-Path $root 'docs/releases')){Copy-Item -LiteralPath (Join-Path $root 'docs/releases') -Destination (Join-Path $stage 'docs') -Recurse -Force}
$exe=if($IsWindows){'Lagless.exe'}else{'Lagless'}
$files=@(Get-ChildItem -LiteralPath $stage -File -Recurse | Where-Object {$_.Name -ne 'bundle.json' -and $_.Extension -notin @('.pdb','.lib','.exp') -and !$_.FullName.Contains([IO.Path]::DirectorySeparatorChar+'.updates'+[IO.Path]::DirectorySeparatorChar)} | ForEach-Object {
    $relative=[IO.Path]::GetRelativePath($stage,$_.FullName).Replace('\','/')
    @{path=$relative;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant();size=$_.Length;executable=($relative -eq $exe)}
})
@{format=1;edition='desktop-native';version=$version;platform="$platform-$arch";executable=$exe;files=$files} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $stage 'bundle.json') -Encoding utf8NoBOM
# -SkipNativeChecks keeps the JVM run above (it records the reflection metadata the
# EXE needs) and skips only re-checking the finished EXE and its update/rollback.
if(!$SkipTests -and !$SkipNativeChecks){
    Run-Verification $false
    & (Join-Path $PSScriptRoot 'verify-native-update.ps1') -Bundle $stage
}
# ZIP only the managed files. Debug symbols and compilation artifacts stay outside releases.
$archive=Join-Path $root "dist/lagless-desktop-native-$version-$platform-$arch.zip"
if(Test-Path -LiteralPath $archive){Remove-Item -LiteralPath $archive}
$output=[IO.Compression.ZipFile]::Open($archive,[IO.Compression.ZipArchiveMode]::Create)
try{
    foreach($relative in (@('bundle.json')+@($files.path))){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($output,(Join-Path $stage $relative),$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null}
}finally{$output.Dispose()}
if($SkipTests){Write-Host "Native package built without running tests: $archive"}elseif($SkipNativeChecks){Write-Host "Native package built; the finished EXE was not re-verified: $archive"}else{Write-Host "Verified native release: $archive"}
