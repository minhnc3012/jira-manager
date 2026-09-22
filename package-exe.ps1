<#
.SYNOPSIS
    Builds a self-contained Windows desktop package for Jira Manager (no JDK/Maven/Docker
    needed on the target machine) using jpackage. Output: dist\JiraManager\JiraManager.exe
    plus a bundled JRE. Zip the dist\JiraManager folder and hand it to users as-is.

.PARAMETER JavaHome
    Path to a JDK 21 installation. Required because Vaadin 24.6.6's build-frontend plugin
    scans class bytecode with an ASM version that can't parse class files newer than ~23 —
    and JIT-generated classes (lambdas, CGLIB/Spring proxies) always carry the *running*
    JVM's own class-file version, so this holds even though the project's own compiled
    bytecode targets Java 21 (pom.xml java.version) and plain `mvn compile` works fine
    under JDK 25 (see lombok.version and .mvn\jvm.config). Defaults to $env:JAVA_HOME.
#>
param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

if (-not $JavaHome -or -not (Test-Path "$JavaHome\bin\java.exe")) {
    Write-Error "Pass -JavaHome pointing at a JDK 21 install (e.g. -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot')."
    exit 1
}

# java -version writes to stderr; PS5.1 treats a redirected native stderr line as a
# terminating error under $ErrorActionPreference = "Stop", so relax it just for this call.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$versionOutput = & "$JavaHome\bin\java.exe" -version 2>&1 | Out-String
$ErrorActionPreference = $prevEAP
if ($versionOutput -notmatch '"21\.') {
    Write-Error "JDK at '$JavaHome' is not version 21 (got: $($versionOutput.Split("`n")[0])). Vaadin's build-frontend plugin can't run under newer JDKs yet (see script header)."
    exit 1
}

$root = $PSScriptRoot
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

Write-Host "==> Building production jar (mvn package -Pproduction) with $JavaHome ..."
Push-Location $root
try {
    & mvn clean package -Pproduction -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
} finally {
    Pop-Location
}

$jar = Get-ChildItem "$root\target\jira-manager-*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $jar) { throw "Could not find the built jar in target\." }

Write-Host "==> Packaging $($jar.Name) into a Windows app image (jpackage) ..."
$dist = "$root\dist"
$input_ = "$dist\input"
Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
if (Test-Path "$dist\JiraManager") {
    Write-Error "Could not clean '$dist\JiraManager' (a file is still locked). Close any running JiraManager.exe first, then re-run this script."
    exit 1
}
New-Item -ItemType Directory -Force -Path $input_ | Out-Null
Copy-Item $jar.FullName "$input_\$($jar.Name)"

& jpackage `
    --type app-image `
    --input $input_ `
    --main-jar $jar.Name `
    --name "JiraManager" `
    --app-version 1.0.0 `
    --vendor "KeytechX" `
    --dest $dist `
    --java-options "-Djira.desktop.mode=true" `
    --java-options "-Xmx512m" `
    --add-modules ALL-MODULE-PATH

if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

Remove-Item -Recurse -Force $input_

Write-Host "==> Zipping $dist\JiraManager ..."
$zipPath = "$dist\JiraManager.zip"
Remove-Item -Force $zipPath -ErrorAction SilentlyContinue
Compress-Archive -Path "$dist\JiraManager" -DestinationPath $zipPath -CompressionLevel Optimal

Write-Host ""
Write-Host "==> Done: $dist\JiraManager\JiraManager.exe"
Write-Host "    Zipped to: $zipPath - send this to users as-is."
Write-Host "    Double-clicking JiraManager.exe starts the app, opens the browser to"
Write-Host "    http://localhost:8888, and adds a tray icon (Open / Exit). Its H2 database is"
Write-Host "    written to a data\ folder next to the exe, so the extracted folder must be"
Write-Host "    somewhere the user can write to (not Program Files)."
