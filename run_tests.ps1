$toolsDir = Join-Path $PSScriptRoot ".tools"
$jdkHome = Get-ChildItem (Join-Path $toolsDir "jdk") | Where-Object { $_.PSIsContainer } | Select-Object -First 1
$jdkPath = $jdkHome.FullName
$mvnHome = Get-ChildItem (Join-Path $toolsDir "maven") | Where-Object { $_.PSIsContainer } | Select-Object -First 1
$mvnBin = Join-Path $mvnHome.FullName "bin"

$env:JAVA_HOME = $jdkPath
$env:PATH = "$jdkPath\bin;$mvnBin;$env:PATH"

& mvn clean test-compile
