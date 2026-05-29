$toolsDir = Join-Path $PSScriptRoot ".tools"

# Ensure toolchain is bootstrapped
& (Join-Path $PSScriptRoot "setup_toolchain.ps1")

# Locate dynamically extracted JDK root directory
$jdkHome = Get-ChildItem (Join-Path $toolsDir "jdk") | Where-Object { $_.PSIsContainer } | Select-Object -First 1
$jdkPath = $jdkHome.FullName

# Locate dynamically extracted Maven root directory
$mvnHome = Get-ChildItem (Join-Path $toolsDir "maven") | Where-Object { $_.PSIsContainer } | Select-Object -First 1
$mvnBin = Join-Path $mvnHome.FullName "bin"

# Set environment variables in the current process context
$env:JAVA_HOME = $jdkPath
$env:PATH = "$jdkPath\bin;$mvnBin;$env:PATH"

Write-Host "-------------------------------------------"
Write-Host "Session Toolchain Configured"
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "Maven Bin: $mvnBin"
Write-Host "-------------------------------------------"

# Execute maven commands passed as arguments, default to clean compile
if ($args.Count -gt 0) {
    $mvnArgs = $args -join " "
    Write-Host "Executing: mvn $mvnArgs"
    Invoke-Expression "mvn $mvnArgs"
} else {
    Write-Host "Executing: mvn clean compile"
    mvn clean compile
}
