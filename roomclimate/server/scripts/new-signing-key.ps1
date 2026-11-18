# Общий ключ подписи приложения RoomClimate — создаётся ОДИН раз на весь проект.
#
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\new-signing-key.ps1
#
# Обновление ставится поверх старой версии, только если обе подписаны одним ключом,
# поэтому ключ нельзя терять и нельзя пересоздавать: сохраните копию папки signing и файла
# keystore.properties (флешка / облако), в git они не попадают (.gitignore).
# Пароль генерируется случайный и лежит только в keystore.properties.
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$server = Split-Path $PSScriptRoot -Parent
$android = Join-Path (Split-Path $server -Parent) 'android\RoomClimateApp'
$signingDir = Join-Path $android 'signing'
$keystore = Join-Path $signingDir 'roomclimate-release.jks'
$props = Join-Path $android 'keystore.properties'

if ((Test-Path $keystore) -and -not $Force) {
    Write-Host "Ключ уже есть: $keystore" -ForegroundColor Yellow
    Write-Host "Новый ключ сломает обновление у всех, кто поставил приложение со старым. Если точно нужно: -Force"
    exit 1
}

$keytool = @(
    (Join-Path $env:JAVA_HOME 'bin\keytool.exe'),
    'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $keytool) { $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source }
if (-not $keytool) { throw 'Не найден keytool (JDK). Поставьте Android Studio или JDK 17.' }

# Пароль: 32 случайных символа без спецзнаков (чтобы не ломался в .properties и переменных CI).
$chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$password = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

New-Item -ItemType Directory -Force $signingDir | Out-Null
if (Test-Path $keystore) { Remove-Item $keystore -Force }
# keytool пишет «Generating…» в stderr — в PowerShell 5 это не ошибка, проверяем код выхода.
$ErrorActionPreference = 'Continue'
& $keytool -genkeypair -keystore $keystore -alias roomclimate -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $password -keypass $password -dname 'CN=RoomClimate, O=RoomClimate, C=RU' 2>&1 | Out-Null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) { throw 'keytool не создал ключ' }

$text = "storeFile=signing/roomclimate-release.jks`nstorePassword=$password`nkeyAlias=roomclimate`nkeyPassword=$password`n"
[IO.File]::WriteAllText($props, $text, (New-Object System.Text.UTF8Encoding $false))

# Для GitHub Actions (секреты репозитория): ключ в base64 — в отдельный файл, не на экран.
$b64 = Join-Path $signingDir 'keystore.base64.txt'
[IO.File]::WriteAllText($b64, [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)))

Write-Host "Ключ создан: $keystore" -ForegroundColor Green
Write-Host "Пароль и имя ключа: $props"
Write-Host ''
Write-Host 'Сохраните копию signing\ и keystore.properties — без них обновления перестанут ставиться.' -ForegroundColor Yellow
Write-Host 'Для сборки в GitHub Actions добавьте секреты репозитория (Settings -> Secrets -> Actions):'
Write-Host "  RC_KEYSTORE_BASE64   = содержимое $b64"
Write-Host '  RC_KEYSTORE_PASSWORD = storePassword из keystore.properties (keyPassword такой же)'
