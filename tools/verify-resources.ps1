$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$resources=Join-Path $project 'src/main/resources'
$jsonFiles=Get-ChildItem -LiteralPath $resources -Recurse -File | Where-Object { $_.Extension -eq '.json' -or $_.Name -eq 'pack.mcmeta' }
foreach($file in $jsonFiles) { Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null }
$pack=Get-Content -LiteralPath (Join-Path $resources 'pack.mcmeta') -Raw | ConvertFrom-Json
if($pack.pack.pack_format -ne 15){ throw 'pack.mcmeta must contain pack.pack_format=15' }
$model=Get-Content -LiteralPath (Join-Path $resources 'assets/super_disc/models/item/super_disc.json') -Raw | ConvertFrom-Json
if($model.parent -ne 'minecraft:item/generated'){throw 'Unexpected item model parent'}
$texture=Join-Path $resources ('assets/'+$model.textures.layer0.Replace(':','/textures/')+'.png')
if(!(Test-Path -LiteralPath $texture)){throw "Missing texture: $texture"}
$recipe=Get-Content -LiteralPath (Join-Path $resources 'data/super_disc/recipes/super_disc.json') -Raw | ConvertFrom-Json
if(($recipe.pattern -join '/') -ne 'GGG/GEG/GGG' -or $recipe.result.item -ne 'super_disc:super_disc'){throw 'Unexpected recipe'}
Add-Type -AssemblyName System.Drawing
$bitmap=[System.Drawing.Bitmap]::new($texture)
try { if($bitmap.Width -ne 16 -or $bitmap.Height -ne 16 -or $bitmap.GetPixel(0,0).A -ne 0){throw 'Expected a transparent 16x16 item sprite'} } finally {$bitmap.Dispose()}
Write-Output "Validated $($jsonFiles.Count) resource JSON files, item texture reference, transparent 16x16 sprite and recipe."
