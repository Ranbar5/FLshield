$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'C:\Users\Soporte\.gemini\antigravity\scratch\AppLocker\app'
.\gradlew.bat assembleDebug --console=plain --no-daemon *>&1 | Tee-Object -FilePath build_debug_output.txt
if ($LASTEXITCODE -eq 0) {
    'BUILD_SUCCESS' | Tee-Object -FilePath build_debug_output.txt -Append
} else {
    ('BUILD_FAIL ' + $LASTEXITCODE) | Tee-Object -FilePath build_debug_output.txt -Append
    exit $LASTEXITCODE
}
