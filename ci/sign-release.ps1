param(
    [Parameter(Mandatory=$true)][string]$UnsignedApk,
    [Parameter(Mandatory=$true)][string]$OutputApk,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$KeyDirectory
)
$ErrorActionPreference='Stop'
$keystore=Join-Path $KeyDirectory 'adownloader-release.jks'
if (!(Test-Path -LiteralPath $keystore)) { throw 'Fixed key missing. Never generate a replacement automatically.' }
$secret=Get-Content -LiteralPath (Join-Path $KeyDirectory 'password.dpapi') | ConvertTo-SecureString
$env:ADOWNLOADER_KEY_PASSWORD=[Net.NetworkCredential]::new('', $secret).Password
$aligned="$OutputApk.aligned"
try {
    & "$AndroidSdk/build-tools/36.0.0/zipalign.exe" -P 16 -f 4 $UnsignedApk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
    & "$AndroidSdk/build-tools/36.0.0/apksigner.bat" sign --ks $keystore --ks-key-alias adownloader --ks-pass env:ADOWNLOADER_KEY_PASSWORD --key-pass env:ADOWNLOADER_KEY_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $OutputApk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Release signing failed' }
    & "$AndroidSdk/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed' }
    & "$AndroidSdk/build-tools/36.0.0/zipalign.exe" -c -P 16 -v 4 $OutputApk | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment check failed' }
    Get-FileHash -Algorithm SHA256 -LiteralPath $OutputApk
} finally {
    Remove-Item Env:ADOWNLOADER_KEY_PASSWORD -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned }
}
