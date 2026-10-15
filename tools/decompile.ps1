<#
One-click decompiler for GTO / GTCEu / AE2 classes - the main tool for checking
how GTO is implemented internally.

Background: the instance has no standalone gtceu jar - it is a jar-in-jar inside
gtocore (gtocore-forge-*.jar -> META-INF/jarjar/gtceu-*.jar). Same for AE2.
This script: locate the jar (incl. nested ones) -> extract matching .class files
-> run vineflower.

Usage (from the repo root):
  tools\decompile.cmd -List                     list available jars
  tools\decompile.cmd gtceu fluid               decompile a package (substring match)
  tools\decompile.cmd gtceu com.gregtechceu.gtceu.common.pipelike.fluid.FluidPipeNet
  tools\decompile.cmd ae2 gto_ae.hooks
  tools\decompile.cmd gtceu fluid -Javap        also dump javap -c (descriptors/bytecode)

Output: tools\out\<jar>\<target path>\*.java
        tools\out\_jars\  cached nested jars (-Force re-extracts)

Parameters:
  -Jar      jar name (gtceu / ae2 / gtocore / any mods jar filename prefix)
  -Target   package substring or fully qualified class name; omitted = list packages
  -Mods     instance mods directory (defaults to this machine's GTO instance)
  -Force    ignore the _jars cache and re-extract the nested jar
#>
param(
    [Parameter(Position = 0)][string]$Jar = "",
    [Parameter(Position = 1)][string]$Target = "",
    [string]$Mods = "C:\Users\PC\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.6.0\.minecraft\mods",
    [switch]$List,
    [switch]$Javap,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$out = Join-Path $here "out"
$jarCache = Join-Path $out "_jars"
Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Force -Path $out, $jarCache | Out-Null

$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "C:\Program Files\Java\jdk-17\bin\java.exe" }
$javapExe = Join-Path (Split-Path $javaExe) "javap.exe"
$vine = Join-Path $here "vineflower.jar"
foreach ($f in @($javaExe, $javapExe, $vine)) { if (-not (Test-Path $f)) { throw "missing file: $f" } }
if (-not (Test-Path $Mods)) { throw "mods directory not found: $Mods (pass -Mods)" }

function Get-GtocoreJar {
    $g = Get-ChildItem $Mods -Filter "gtocore-*.jar" | Select-Object -First 1
    if (-not $g) { throw "no gtocore-*.jar in $Mods" }
    return $g.FullName
}

function Get-NestedJar([string]$pattern) {
    $cached = Get-ChildItem $jarCache -Filter $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cached -and -not $Force) { return $cached.FullName }
    $g = Get-GtocoreJar
    $zip = [System.IO.Compression.ZipFile]::OpenRead($g)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -like "META-INF/jarjar/$pattern" } | Select-Object -First 1
        if (-not $entry) {
            $avail = ($zip.Entries | Where-Object { $_.FullName -like "META-INF/jarjar/*.jar" } | ForEach-Object { $_.Name }) -join ", "
            throw "no META-INF/jarjar/$pattern inside gtocore. available: $avail"
        }
        $dest = Join-Path $jarCache $entry.Name
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
        Write-Host "extracted nested jar: $($entry.Name) -> $dest"
        return $dest
    } finally { $zip.Dispose() }
}

function Resolve-Jar([string]$name) {
    if ($name -eq "gtceu") { return Get-NestedJar "gtceu-*.jar" }
    if ($name -eq "ae2") { return Get-NestedJar "appliedenergistics2-*.jar" }
    $direct = Get-ChildItem $Mods -Filter "$name*.jar" | Select-Object -First 1
    if ($direct) { return $direct.FullName }
    return Get-NestedJar "$name*.jar"
}

if ($List) {
    Write-Host "== jars in $Mods ==" -ForegroundColor Cyan
    Get-ChildItem $Mods -Filter "*.jar" | Sort-Object Name | ForEach-Object { "  {0,12}  {1}" -f $_.Length, $_.Name }
    Write-Host ""
    Write-Host "== nested jars inside gtocore ==" -ForegroundColor Cyan
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Get-GtocoreJar))
    try {
        $zip.Entries | Where-Object { $_.FullName -like "META-INF/jarjar/*.jar" } | ForEach-Object { "  {0,12}  {1}" -f $_.Length, $_.Name }
    } finally { $zip.Dispose() }
    return
}

if (-not $Jar) { Write-Host "see the header of tools\decompile.ps1, or run: tools\decompile.cmd -List" -ForegroundColor Yellow; return }

$jarPath = Resolve-Jar $Jar
$jarName = [IO.Path]::GetFileNameWithoutExtension($jarPath)
Write-Host "target jar: $jarPath"

if (-not $Target) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $zip.Entries | Where-Object { $_.FullName -like "*.class" } |
            ForEach-Object { ($_.FullName -split '/')[0..1] -join '/' } |
            Sort-Object -Unique | ForEach-Object { "  $_" }
    } finally { $zip.Dispose() }
    return
}

# Accept both / and \ as separators (jar entries always use /).
$Target = $Target -replace '\\', '/'
# A target containing ".<Uppercase>" is treated as a fully qualified class name.
$isClass = $Target -match '\.[A-Z]'
$entries = @()
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $all = @($zip.Entries | Where-Object { $_.FullName -like "*.class" })
    if ($isClass) {
        $prefix = ($Target -replace '\.', '/')
        $entries = @($all | Where-Object { $_.FullName -eq "$prefix.class" -or $_.FullName -like "$prefix`$*.class" })
    } else {
        $entries = @($all | Where-Object { $_.FullName -like "*$Target*" })
    }
    if ($entries.Count -eq 0) { throw "no match for '$Target' in $jarName (use / as separator: pipelike/fluid, or a full class name)" }

    $srcDir = Join-Path $out ("_src\" + $jarName)
    Remove-Item -Recurse -Force $srcDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $srcDir | Out-Null
    foreach ($e in $entries) {
        $destFile = Join-Path $srcDir ($e.FullName -replace '/', '\')
        New-Item -ItemType Directory -Force -Path (Split-Path $destFile) | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $destFile, $true)
    }
    Write-Host "extracted $($entries.Count) class files"
} finally { $zip.Dispose() }

$destDir = Join-Path $out (Join-Path $jarName ($Target -replace '\.', '\'))
Remove-Item -Recurse -Force $destDir -ErrorAction SilentlyContinue
Write-Host "decompiling with vineflower..."
& $javaExe -jar $vine "-dgs=1" "-e=$jarPath" $srcDir $destDir 2>&1 | Select-Object -Last 3
$made = @(Get-ChildItem -Recurse -File $destDir -ErrorAction SilentlyContinue)
Write-Host "output: $destDir ($($made.Count) files)" -ForegroundColor Green
$made | Select-Object -First 25 | ForEach-Object { "  " + $_.FullName.Replace("$destDir\", "") }

if ($Javap) {
    foreach ($e in $entries) {
        if ($e.FullName -like "*`$*") { continue }
        $cls = ($e.FullName -replace '\.class$', '') -replace '/', '.'
        Write-Host ""
        Write-Host "=== javap -p -c $cls ===" -ForegroundColor Cyan
        & $javapExe -p -c -classpath $jarPath $cls
    }
}
