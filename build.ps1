param(
    [string]$Target = 'forge-1.20.1',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Proxy
)
$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$names = @('JAVA_HOME','PATH','GRADLE_USER_HOME','TEMP','TMP','JAVA_TOOL_OPTIONS','HTTP_PROXY','HTTPS_PROXY')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $PSScriptRoot
try {
    if (!$JavaHome -or !(Test-Path -LiteralPath "$JavaHome/bin/javac.exe")) { throw 'Supply -JavaHome with a full JDK path.' }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome/bin;$env:PATH"
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.gradle-home'
    $env:TEMP = $env:TMP = Join-Path $PSScriptRoot '.tmp'
    foreach ($path in @($env:GRADLE_USER_HOME,$env:TEMP)) {
        if ([IO.Path]::GetPathRoot($path) -eq 'C:\') { throw 'Build cache and temporary directories must not be on C:.' }
        New-Item -ItemType Directory -Force -Path $path | Out-Null
    }
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djava.io.tmpdir=$env:TEMP"
    if ($Proxy) {
        $uri = [uri]$Proxy
        if ($uri.Scheme -ne 'http' -or !$uri.Host -or $uri.UserInfo) { throw 'Use an HTTP proxy URL without credentials.' }
        $env:HTTP_PROXY = $env:HTTPS_PROXY = $Proxy
        $env:JAVA_TOOL_OPTIONS += " -Dhttp.proxyHost=$($uri.Host) -Dhttp.proxyPort=$($uri.Port) -Dhttps.proxyHost=$($uri.Host) -Dhttps.proxyPort=$($uri.Port)"
    }
    $matrix = Get-Content -Raw -Encoding UTF8 "$PSScriptRoot/scripts/targets.json" | ConvertFrom-Json
    $selected = @($matrix.include | Where-Object id -EQ $Target)
    if ($selected.Count -ne 1) { throw "Unsupported target: $Target" }
    $project = $selected[0].project
    $wrapper = "./$project/gradlew.bat"
    & $wrapper --project-dir $project --no-daemon --console=plain clean build
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $Target" }
} finally {
    Pop-Location
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
