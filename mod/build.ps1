# Runs Gradle with the settings this machine needs.
# Usage: .\build.ps1 build | .\build.ps1 runClient | .\build.ps1 runServer
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks = @("build"))

# Gradle 9 needs Java 17+ to run (the mod itself is compiled with a Java 21 toolchain that Gradle downloads).
if ($env:BLOCKLAND_JAVA_HOME) { $env:JAVA_HOME = $env:BLOCKLAND_JAVA_HOME }
elseif (-not $env:JAVA_HOME -or $env:JAVA_HOME -match "1\.8|jdk8") { $env:JAVA_HOME = "C:\Program Files\Java\jdk-26.0.1" }
# JDK 26 on Windows fails to open NIO selectors (AF_UNIX) in some temp dirs; use the Gradle home instead.
$javaOpts = @("-Djdk.net.unixdomain.tmpdir=$($env:USERPROFILE -replace '\\','/')/.gradle")
# Java ignores HTTP(S)_PROXY; forward it so Minecraft asset/library downloads work behind a local proxy.
$proxy = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
if ($proxy -and $proxy -match '^\w+://([^:/]+):(\d+)') {
    $javaOpts += "-Dhttp.proxyHost=$($Matches[1])", "-Dhttp.proxyPort=$($Matches[2])",
                 "-Dhttps.proxyHost=$($Matches[1])", "-Dhttps.proxyPort=$($Matches[2])",
                 "-Dhttp.nonProxyHosts=localhost|127.*|[::1]"
}
$env:JAVA_TOOL_OPTIONS = $javaOpts -join ' '
& "$PSScriptRoot\gradlew.bat" @Tasks --console=plain
exit $LASTEXITCODE
