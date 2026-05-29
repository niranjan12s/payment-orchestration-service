$toolsDir = Join-Path $PSScriptRoot ".tools"
if (-not (Test-Path $toolsDir)) {
    New-Item -ItemType Directory -Path $toolsDir | Out-Null
}

# 1. Download and extract OpenJDK 21
$jdkExtractPath = Join-Path $toolsDir "jdk"
if (-not (Test-Path $jdkExtractPath)) {
    $jdkZip = Join-Path $toolsDir "jdk21.zip"
    Write-Host "Downloading Temurin OpenJDK 21 (Windows x64)..."
    Invoke-WebRequest -Uri "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip" -OutFile $jdkZip
    Write-Host "Extracting OpenJDK 21..."
    Expand-Archive -Path $jdkZip -DestinationPath $jdkExtractPath
    Remove-Item $jdkZip
    Write-Host "OpenJDK 21 installed successfully."
} else {
    Write-Host "OpenJDK 21 already installed."
}

# 2. Download and extract Maven 3.9.6
$mvnExtractPath = Join-Path $toolsDir "maven"
if (-not (Test-Path $mvnExtractPath)) {
    $mvnZip = Join-Path $toolsDir "maven.zip"
    Write-Host "Downloading Apache Maven 3.9.6..."
    Invoke-WebRequest -Uri "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip" -OutFile $mvnZip
    Write-Host "Extracting Maven..."
    Expand-Archive -Path $mvnZip -DestinationPath $mvnExtractPath
    Remove-Item $mvnZip
    Write-Host "Maven installed successfully."
} else {
    Write-Host "Maven already installed."
}

Write-Host "Local toolchain is ready under .tools/"
