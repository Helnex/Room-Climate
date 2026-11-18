# Публикация новой версии приложения: собрать подписанный APK и выложить на сервер,
# откуда приложения сами узнают об обновлении (GET /app/latest) и скачивают его.
#
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\publish-apk.ps1
#   ... -Changes "Платы и датчики из базы сервера"          # что нового (иначе — из git log)
#   ... -Stand kryuc@192.168.1.12                           # сразу отправить на стенд по SSH
#
# Номер версии = число коммитов (app/build.gradle.kts), поэтому публикуйте после коммита.
# Нужен общий ключ подписи: scripts\new-signing-key.ps1 (один раз на проект).
param(
    [string]$Changes = '',
    [string]$Stand = '',
    [string]$StandKey = (Join-Path $env:USERPROFILE '.ssh\roomclimate_pc'),
    [string]$StandPath = 'C:/Projects/Room-Climate/roomclimate/server/app-release',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$server = Split-Path $PSScriptRoot -Parent
$android = Join-Path (Split-Path $server -Parent) 'android\RoomClimateApp'
$release = Join-Path $server 'app-release'

if (-not (Test-Path (Join-Path $android 'keystore.properties'))) {
    throw 'Нет keystore.properties — сначала scripts\new-signing-key.ps1 (или скопируйте общий ключ проекта).'
}
if (git -C $android status --porcelain -- . 2>$null) {
    Write-Host 'Внимание: в приложении есть незакоммиченные правки — номер версии их не учтёт.' -ForegroundColor Yellow
}

if (-not $SkipBuild) {
    Push-Location $android
    try {
        & .\gradlew.bat --quiet assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Сборка не удалась (gradlew assembleRelease)' }
    } finally { Pop-Location }
}

$out = Join-Path $android 'app\build\outputs\apk\release'
$meta = Get-Content (Join-Path $out 'output-metadata.json') -Raw | ConvertFrom-Json
$element = $meta.elements[0]
$apk = Join-Path $out $element.outputFile
if ($element.outputFile -like '*unsigned*') { throw "APK не подписан ($apk) — проверьте keystore.properties" }

if (-not $Changes) {
    $Changes = (git -C $android log --no-merges -5 --format='• %s' -- . | Out-String).Trim()
}
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()

New-Item -ItemType Directory -Force $release | Out-Null
Copy-Item $apk (Join-Path $release 'roomclimate.apk') -Force
$latest = [ordered]@{
    version_code = [int]$element.versionCode
    version_name = [string]$element.versionName
    sha256       = $sha
    changes      = $Changes
    published_at = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss+00:00')
}
$json = $latest | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $release 'latest.json'), $json, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Опубликовано локально: версия $($latest.version_name) (код $($latest.version_code)) -> $release" -ForegroundColor Green

if ($Stand) {
    & ssh -i $StandKey $Stand "if not exist `"$($StandPath -replace '/', '\')`" mkdir `"$($StandPath -replace '/', '\')`""
    & scp -q -i $StandKey (Join-Path $release 'roomclimate.apk') (Join-Path $release 'latest.json') "${Stand}:$StandPath/"
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось скопировать на стенд' }
    Write-Host "Отправлено на стенд $Stand ($StandPath). Приложения узнают об обновлении в течение 6 часов или сразу в «Настройки -> Обновление приложения»." -ForegroundColor Green
}
