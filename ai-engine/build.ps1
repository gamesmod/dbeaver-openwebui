# Builds the plugin jar against an installed DBeaver CE (Windows) and optionally installs it.
#
#   .\build.ps1                         - build target\dbeaver.openwebui.ai_1.0.0.jar
#   .\build.ps1 -Install                - build and copy into <DBeaver>\dropins
#   .\build.ps1 -InstallBundles         - build, copy into plugins\ and register in bundles.info
#   .\build.ps1 -DBeaverHome "D:\Tools\DBeaver"
#
# Requires JDK 21+ (javac, jar) in PATH or JAVA_HOME. Run as Administrator if DBeaver is in Program Files.
param(
    [string]$DBeaverHome = $env:DBEAVER_HOME,
    [switch]$Install,
    [switch]$InstallBundles
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Version = "1.0.0"
$Bsn = "dbeaver.openwebui.ai"
$Jar = "target\${Bsn}_${Version}.jar"

if (-not $DBeaverHome) {
    foreach ($d in @("$env:ProgramFiles\DBeaver", "$env:LOCALAPPDATA\DBeaver", "$env:LOCALAPPDATA\Programs\DBeaver")) {
        if (Test-Path "$d\plugins") { $DBeaverHome = $d; break }
    }
}
if (-not $DBeaverHome -or -not (Test-Path "$DBeaverHome\plugins")) {
    throw "DBeaver not found. Pass -DBeaverHome <folder containing 'plugins'>."
}
Write-Host "DBeaver: $DBeaverHome"
if (-not (Get-ChildItem "$DBeaverHome\plugins" -Filter "org.jkiss.dbeaver.model.ai_*.jar")) {
    throw "org.jkiss.dbeaver.model.ai bundle not found - this DBeaver version lacks the AI module API."
}

$bin = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\" } else { "" }
$javac = "${bin}javac"
$jarTool = "${bin}jar"

$cp = (Get-ChildItem "$DBeaverHome\plugins" -Recurse -Depth 1 -Filter *.jar | ForEach-Object FullName) -join ";"
$cpFile = New-TemporaryFile
Set-Content $cpFile -Value ("-cp `"" + $cp.Replace('\', '/') + "`"") -Encoding ascii

if (Test-Path target) { Remove-Item target -Recurse -Force }
New-Item -ItemType Directory target\classes | Out-Null
$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName

& $javac --release 21 -encoding UTF-8 -proc:none -nowarn "@$cpFile" -d target\classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Remove-Item $cpFile

Get-ChildItem src -Recurse -Filter *.properties | ForEach-Object {
    $rel = $_.FullName.Substring((Resolve-Path src).Path.Length + 1)
    $dst = Join-Path target\classes $rel
    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
    Copy-Item $_.FullName $dst
}

& $jarTool --create --file $Jar --manifest META-INF\MANIFEST.MF -C target\classes . plugin.xml icons
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built: $Jar"

if ($Install) {
    New-Item -ItemType Directory -Force "$DBeaverHome\dropins" | Out-Null
    Copy-Item $Jar "$DBeaverHome\dropins\"
    Write-Host "Installed to $DBeaverHome\dropins. Restart DBeaver (first start: dbeaver.exe -clean)."
}
if ($InstallBundles) {
    Copy-Item $Jar "$DBeaverHome\plugins\"
    $bi = "$DBeaverHome\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
    if (-not (Select-String -Path $bi -Pattern "^$([regex]::Escape($Bsn))," -Quiet)) {
        Copy-Item $bi "$bi.bak"
        $raw = Get-Content $bi -Raw
        if ($raw -and -not $raw.EndsWith("`n")) { Add-Content $bi "" }
        Add-Content $bi "$Bsn,$Version,plugins/${Bsn}_${Version}.jar,4,false"
    }
    Write-Host "Installed to plugins\ and registered in bundles.info. Restart DBeaver with -clean."
}
