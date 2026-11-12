# Подключение телефона к серверу по USB-кабелю — работает при любом VPN
# на телефоне и на компьютере, без Wi-Fi и без брандмауэра.
# На телефоне: «Для разработчиков» -> «Отладка по USB» включена, подключение разрешено.
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\usb-connect.ps1
# После этого приложение само найдёт сервер на http://127.0.0.1:8000
# (переподключать после каждого отключения кабеля).
param([int]$Port = 8000)

$adb = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $adb) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    $candidate = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path $candidate) { $adb = $candidate }
}
if (-not $adb) {
    Write-Host "adb не найден. Установи Android SDK Platform-Tools (входит в Android Studio)." -ForegroundColor Red
    exit 1
}

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
if (-not $devices) {
    Write-Host "Телефон не найден. Подключи кабель, включи «Отладку по USB» и подтверди запрос на телефоне." -ForegroundColor Red
    & $adb devices
    exit 1
}

& $adb reverse "tcp:$Port" "tcp:$Port"
if ($LASTEXITCODE -ne 0) { Write-Host "adb reverse не сработал" -ForegroundColor Red; exit 1 }
Write-Host "Готово: на телефоне http://127.0.0.1:$Port ведёт на сервер этого компьютера." -ForegroundColor Green
& $adb reverse --list
