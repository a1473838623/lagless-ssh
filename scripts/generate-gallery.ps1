# Render README images from the actual desktop components and sample data.
# Run from any folder with PowerShell, JDK 21+, and Maven.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & mvn -q -pl desktop -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed: $LASTEXITCODE" }
    $classpath = 'desktop/target/test-classes;desktop/target/classes;cli/target/classes;core/target/classes;desktop/target/lagless-desktop-standalone.jar'
    & java --enable-native-access=ALL-UNNAMED -cp $classpath dev.lagless.desktop.GalleryCapture docs/media
    if ($LASTEXITCODE -ne 0) { throw "Gallery rendering failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
