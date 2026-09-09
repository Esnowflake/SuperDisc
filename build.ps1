param([string]$JavaHome = 'D:\Program Files\JDK-17')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw "JDK 17 not found: $JavaHome" }
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
& "$PSScriptRoot/tools/verify-resources.ps1"
& "$PSScriptRoot/gradlew.bat" clean jarJar --no-daemon --console=plain 2>&1 | Tee-Object -FilePath "$PSScriptRoot/build-output.log"
if($LASTEXITCODE -ne 0) { throw 'Build failed. See build-output.log.' }
$artifact=Join-Path $PSScriptRoot 'build/libs/super-disc-forge-1.20.1-1.0.0-all.jar'
if(!(Test-Path -LiteralPath $artifact)) { throw 'Bundled JAR not produced.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[System.IO.Compression.ZipFile]::OpenRead($artifact)
try {
    foreach($entry in @('META-INF/mods.toml','META-INF/jarjar/metadata.json','dev/superdisc/SuperDisc.class','assets/super_disc/models/item/super_disc.json','assets/super_disc/textures/item/super_disc.png','data/super_disc/recipes/super_disc.json','pack.mcmeta')) {
        if(!$zip.GetEntry($entry)){throw "Missing JAR entry: $entry"}
    }
    if(!($zip.Entries | Where-Object { $_.FullName -match 'META-INF/jarjar/.*jlayer.*\.jar$' })) { throw 'JLayer MP3 decoder is missing from the bundled JAR.' }
    $reader=[System.IO.StreamReader]::new($zip.GetEntry('pack.mcmeta').Open())
    try { $pack=$reader.ReadToEnd() | ConvertFrom-Json; if($pack.pack.pack_format -ne 15){throw 'Invalid packed metadata'} } finally {$reader.Dispose()}
} finally {$zip.Dispose()}
Write-Host "Verified artifact: $artifact" -ForegroundColor Green
Get-FileHash -LiteralPath $artifact -Algorithm SHA256
