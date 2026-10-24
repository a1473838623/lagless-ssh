param([Parameter(Mandatory)][string]$Bundle)
$ErrorActionPreference='Stop'
$source=(Resolve-Path -LiteralPath $Bundle).Path
$testRoot=Join-Path (Split-Path -Parent $source) ('update-test-'+[guid]::NewGuid().ToString('N'))
$installed=Join-Path $testRoot 'installed'
$candidate=Join-Path $testRoot 'candidate'
New-Item -ItemType Directory -Force $installed,$candidate | Out-Null
$manifest=Get-Content -LiteralPath (Join-Path $source 'bundle.json') -Raw | ConvertFrom-Json -AsHashtable
foreach($target in @($installed,$candidate)){
    foreach($file in @('bundle.json')+@($manifest.files.path)){
        $dest=Join-Path $target $file
        New-Item -ItemType Directory -Force (Split-Path -Parent $dest) | Out-Null
        Copy-Item -LiteralPath (Join-Path $source $file) -Destination $dest
    }
}
$manifest.version='0.0.0-native-update-verification'
if($IsWindows){
    # A valid PE overlay changes the executable checksum so the helper must
    # really replace the EXE, rather than skip an unchanged application file.
    $changed=Join-Path $candidate $manifest.executable
    $stream=[IO.File]::Open($changed,[IO.FileMode]::Append,[IO.FileAccess]::Write)
    try{$stream.Write([Text.Encoding]::UTF8.GetBytes('LAGLESS-NATIVE-UPDATE-VERIFICATION'))}finally{$stream.Dispose()}
    foreach($file in $manifest.files){if($file.path -eq $manifest.executable){$file.size=(Get-Item -LiteralPath $changed).Length;$file.sha256=(Get-FileHash -LiteralPath $changed -Algorithm SHA256).Hash.ToLowerInvariant()}}
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $candidate 'bundle.json') -Encoding utf8NoBOM
$archive=Join-Path $testRoot 'candidate.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open($archive,[IO.Compression.ZipArchiveMode]::Create)
try{foreach($file in @('bundle.json')+@($manifest.files.path)){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $candidate $file),$file) | Out-Null}}finally{$zip.Dispose()}
$executable=Join-Path $installed $manifest.executable
if(!$IsWindows){& chmod +x $executable}
function Wait-Update([string]$version){
    $result=Join-Path $installed '.updates/result.json'
    $deadline=[datetime]::UtcNow.AddSeconds(30)
    while(!(Test-Path -LiteralPath $result)){
        if([datetime]::UtcNow -gt $deadline){throw "Native helper timed out. Inspect $installed/.updates"}
        Start-Sleep -Milliseconds 100
    }
    $status=Get-Content -LiteralPath $result -Raw | ConvertFrom-Json
    if(!$status.ok -or $status.version -ne $version){throw "Native update failed: $($status|ConvertTo-Json -Compress)"}
    $current=Get-Content -LiteralPath (Join-Path $installed 'bundle.json') -Raw | ConvertFrom-Json
    if($current.version -ne $version){throw 'Native manifest was not installed'}
    foreach($file in $current.files){if((Get-FileHash -LiteralPath (Join-Path $installed $file.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256){throw 'Installed file checksum mismatch'}}
    Remove-Item -LiteralPath $result
}
function Run-Native([string[]]$Arguments){
    $options=@{FilePath=$executable;ArgumentList=$Arguments;PassThru=$true;RedirectStandardOutput=(Join-Path $testRoot 'stage.out');RedirectStandardError=(Join-Path $testRoot 'stage.err')}
    if($IsWindows){$options.WindowStyle='Hidden'}
    $process=Start-Process @options
    if(!$process.WaitForExit(10000)){$process.Kill();throw 'Native update staging timed out'}
    if($process.ExitCode -ne 0){throw "Cannot stage native update: $(Get-Content (Join-Path $testRoot 'stage.err') -Raw)"}
}
Run-Native -Arguments @('--install-update',('"'+$archive+'"'))
Wait-Update '0.0.0-native-update-verification'
Run-Native -Arguments @('--rollback')
$original=Get-Content -LiteralPath (Join-Path $source 'bundle.json') -Raw | ConvertFrom-Json
Wait-Update $original.version
Write-Host 'Native copied helper, installation and rollback: OK'
