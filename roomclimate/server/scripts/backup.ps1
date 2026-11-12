# Резервная копия базы SQLite из Docker-тома (сервер может работать — копия консистентна).
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\backup.ps1
# Восстановление из копии:
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\backup.ps1 -Restore backups\roomclimate_20261112_1200.db
param([string]$Restore, [int]$Keep = 20)

$server = Split-Path $PSScriptRoot -Parent
$project = "roomclimate"
$backups = Join-Path $server "backups"
New-Item -ItemType Directory -Force $backups | Out-Null
$compose = @('compose', '-p', $project, '--project-directory', $server, '-f', (Join-Path $server 'docker-compose.yml'))

$running = docker @compose ps --status running --services 2>$null
if ($running -notcontains 'api') {
    Write-Host "Контейнер api не запущен. Сначала: scripts\start.ps1" -ForegroundColor Red
    exit 1
}

if ($Restore) {
    if (-not (Test-Path $Restore)) { Write-Host "Нет файла $Restore" -ForegroundColor Red; exit 1 }
    Write-Host "Восстанавливаю базу из $Restore (текущая будет сохранена как резервная копия)..."
    & $PSCommandPath -Keep $Keep
    docker @compose stop api worker | Out-Null
    docker @compose cp $Restore api:/data/roomclimate.db
    docker @compose start api worker | Out-Null
    Write-Host "Готово. Сервер перезапущен с восстановленной базой." -ForegroundColor Green
    exit 0
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmm'
$py = "import sqlite3; s=sqlite3.connect('/data/roomclimate.db'); d=sqlite3.connect('/data/backup.db'); s.backup(d); d.close(); s.close()"
docker @compose exec -T api python -c $py
if ($LASTEXITCODE -ne 0) { Write-Host "Не удалось сделать копию" -ForegroundColor Red; exit 1 }
$file = Join-Path $backups "roomclimate_$stamp.db"
docker @compose cp api:/data/backup.db $file
docker @compose exec -T api rm -f /data/backup.db
Write-Host ("Копия: {0} ({1:N1} КБ)" -f $file, ((Get-Item $file).Length / 1KB)) -ForegroundColor Green

# Храним только последние $Keep копий.
Get-ChildItem $backups -Filter 'roomclimate_*.db' | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip $Keep | Remove-Item -Force
