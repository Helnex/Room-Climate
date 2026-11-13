# Автозапуск стенда после входа в Windows: задача «RoomClimate stand» в Планировщике заданий.
# Через минуту после входа запускает start.ps1 (Docker Desktop -> сервер -> проверка) без
# пересборки и без браузера; вывод — в roomclimate\server\logs\autostart.log.
# Права администратора не нужны: задача работает от текущего пользователя.
#
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\install-autostart.ps1            # установить
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\install-autostart.ps1 -RunNow     # установить и сразу проверить
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\install-autostart.ps1 -Uninstall  # удалить
param([switch]$Uninstall, [switch]$RunNow)

$taskName = 'RoomClimate stand'
$server = Split-Path $PSScriptRoot -Parent
$startScript = Join-Path $PSScriptRoot 'start.ps1'
$logDir = Join-Path $server 'logs'
$logFile = Join-Path $logDir 'autostart.log'

if ($Uninstall) {
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Host "Задача «$taskName» удалена. Стенд больше не запускается сам." -ForegroundColor Green
    } else {
        Write-Host "Задачи «$taskName» нет." -ForegroundColor Yellow
    }
    exit 0
}

if (-not (Test-Path $startScript)) { Write-Host "Не найден $startScript" -ForegroundColor Red; exit 1 }
New-Item -ItemType Directory -Force $logDir | Out-Null

# Команда задачи: start.ps1 без сборки и браузера; весь вывод (с датой) — в лог.
$command = "& { '==== ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' автозапуск ===='; " +
           "& '$startScript' -NoBuild -NoBrowser } *>> '$logFile'"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command `"$command`"" `
    -WorkingDirectory $server

$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$trigger.Delay = 'PT1M'   # дать Windows и сети подняться
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 15) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Description 'RoomClimate: запуск Docker-стенда после входа в Windows (install-autostart.ps1)' `
    -Force | Out-Null

Write-Host "Задача «$taskName» установлена: стенд будет подниматься через 1 мин после входа в Windows." -ForegroundColor Green
Write-Host "Лог: $logFile"
Write-Host "Контейнеры и так перезапускаются сами (restart: unless-stopped) — задача поднимает Docker Desktop и проверяет сервер."

if ($RunNow) {
    Write-Host "Запускаю задачу для проверки..."
    Start-ScheduledTask -TaskName $taskName
}
