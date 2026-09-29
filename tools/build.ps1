param([switch]$Install)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = "C:\gradle"
$env:TEMP = "C:\gradle\tmp"
$env:TMP = "C:\gradle\tmp"
Set-Location C:\DSviewer
& C:\DSviewer\gradlew.bat assembleDebug --console=plain *> C:\gradle\tmp\build-log.txt
$code = $LASTEXITCODE
Select-String -Path C:\gradle\tmp\build-log.txt -Pattern "^e: |^w: .*DSviewer|BUILD |What went wrong" | ForEach-Object { $_.Line }
if ($code -ne 0) { exit $code }
if ($Install) {
    & C:\android\sdk\platform-tools\adb.exe install -r C:\DSviewer\app\build\outputs\apk\debug\app-debug.apk
}
