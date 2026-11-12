# Запуск стенда одной командой: Docker Desktop -> сервер -> проверка -> страница подключения.
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\start.ps1
# Ключи: -NoBuild (не пересобирать образ), -NoBrowser (не открывать страницу подключения)
param([switch]$NoBuild, [switch]$NoBrowser, [int]$Port = 8000)

$server = Split-Path $PSScriptRoot -Parent
$project = "roomclimate"

# Секреты стенда в server/.env (в git не попадает): создаём случайные, если их ещё нет.
#  INGEST_TOKEN — пароль платы для POST /telemetry (без него показания может слать кто угодно);
#  NTFY_TOPIC  — секретное имя темы push-уведомлений.
function New-Secret { $b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ([Convert]::ToBase64String($b) -replace '[+/=]', '').Substring(0, 30) }
$envFile = Join-Path $server '.env'
$envText = if (Test-Path $envFile) { [IO.File]::ReadAllText($envFile) } else { '' }
$added = @()
foreach ($name in 'INGEST_TOKEN', 'NTFY_TOPIC') {
    if ($envText -notmatch "(?m)^$name=\S+") {
        $value = if ($name -eq 'NTFY_TOPIC') { "roomclimate-$(New-Secret)" } else { New-Secret }
        $envText = ($envText -replace "(?m)^$name=.*\r?\n?", '').TrimEnd() + "`r`n$name=$value`r`n"
        $added += $name
    }
}
if ($added) {
    [IO.File]::WriteAllText($envFile, $envText.TrimStart(), (New-Object System.Text.UTF8Encoding $false))
    Write-Host "Созданы секреты в server\.env: $($added -join ', ')" -ForegroundColor Yellow
    if ($added -contains 'INGEST_TOKEN') {
        Write-Host "  Тот же INGEST_TOKEN задайте сборщику на Raspberry Pi (переменная окружения INGEST_TOKEN)." -ForegroundColor Yellow
    }
}

function DockerReady { docker info --format '{{.ServerVersion}}' 2>$null | Out-Null; return $LASTEXITCODE -eq 0 }

if (-not (DockerReady)) {
    $exe = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    if (-not (Test-Path $exe)) { Write-Host "Docker Desktop не установлен" -ForegroundColor Red; exit 1 }
    Write-Host "Запускаю Docker Desktop..."
    Start-Process $exe
    for ($i = 0; $i -lt 60 -and -not (DockerReady); $i++) { Start-Sleep 3 }
    if (-not (DockerReady)) { Write-Host "Docker не запустился за 3 минуты" -ForegroundColor Red; exit 1 }
}

# Старые контейнеры с тем же портом (проект «server» из другой папки) мешают запуску.
$busy = docker ps --filter "publish=$Port" --format '{{.Names}}' | Where-Object { $_ -notlike "$project-*" }
if ($busy) {
    Write-Host "Порт $Port занят другими контейнерами: $($busy -join ', ')" -ForegroundColor Red
    Write-Host "Останови их: docker stop $($busy -join ' ')"
    exit 1
}

$composeArgs = @('compose', '-p', $project, '--project-directory', $server, '-f', (Join-Path $server 'docker-compose.yml'), 'up', '-d')
if (-not $NoBuild) { $composeArgs += '--build' }
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { Write-Host "docker compose завершился с ошибкой" -ForegroundColor Red; exit 1 }

Write-Host "Жду, пока сервер ответит..."
$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/health" -TimeoutSec 3
        if ($h.Content -match '"ok"') { $ok = $true; break }
    } catch {}
    Start-Sleep 2
}
if (-not $ok) { Write-Host "Сервер не ответил. Логи: docker compose -p $project logs api" -ForegroundColor Red; exit 1 }
Write-Host "Сервер работает: http://127.0.0.1:$Port" -ForegroundColor Green

& (Join-Path $PSScriptRoot 'network-check.ps1') -Port $Port

if (-not $NoBrowser) {
    $lanIp = Get-NetIPConfiguration | Where-Object {
        $_.IPv4Address -and $_.NetAdapter.Status -eq 'Up' -and $_.IPv4DefaultGateway -and
        $_.InterfaceDescription -notmatch 'VirtualBox|Hyper-V|VMware|TAP-|Wintun|WireGuard|Meta Tunnel|Clash|OpenVPN|TUN'
    } | ForEach-Object { $_.IPv4Address.IPAddress } | Where-Object { $_ -notlike '169.254.*' } | Select-Object -First 1
    $target = if ($lanIp) { "http://${lanIp}:$Port/connect" } else { "http://127.0.0.1:$Port/connect" }
    Write-Host "Открываю страницу подключения с QR-кодом: $target"
    Start-Process $target
}
