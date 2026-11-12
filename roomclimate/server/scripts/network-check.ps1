# Диагностика связи «телефон -> компьютер с Docker-сервером».
# Запуск (права администратора не нужны):
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\network-check.ps1
param([int]$Port = 8000)

$ErrorActionPreference = 'SilentlyContinue'
function Ok($t)   { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Bad($t)  { Write-Host "  [!!]   $t" -ForegroundColor Red }
function Warn($t) { Write-Host "  [??]   $t" -ForegroundColor Yellow }
function Head($t) { Write-Host ""; Write-Host "== $t" -ForegroundColor Cyan }

$virtual = 'VirtualBox|Hyper-V|vEthernet|VMware|TAP-|Bluetooth|Loopback|Wintun|WireGuard|Meta Tunnel|Clash|OpenVPN|TUN|Npcap|Docker'
$vpnLike = 'TAP-|Wintun|WireGuard|Meta Tunnel|Clash|OpenVPN|TUN|Tunnel|VPN|Outline|Hiddify|sing-box|v2ray|AmneziaWG|Cisco|Fortinet|GlobalProtect'

Head "Docker-сервер"
$listen = Get-NetTCPConnection -LocalPort $Port -State Listen
if (-not $listen) {
    Bad "Порт $Port никто не слушает. Запусти: docker compose -p roomclimate up -d --build (в roomclimate\server)"
} elseif ($listen.LocalAddress -contains '127.0.0.1' -and -not ($listen.LocalAddress -contains '0.0.0.0' -or $listen.LocalAddress -contains '::')) {
    Bad "Порт $Port слушается только на 127.0.0.1 — телефон его не увидит. Проверь ports: в docker-compose.yml и старые контейнеры (docker ps)."
} else {
    Ok "Порт $Port слушается на всех адресах"
}
try {
    $h = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/health" -TimeoutSec 5
    Ok "http://127.0.0.1:$Port/health -> $($h.Content)"
} catch { Bad "http://127.0.0.1:$Port/health не отвечает: $($_.Exception.Message)" }

Head "Сети компьютера"
$cfgs = Get-NetIPConfiguration | Where-Object { $_.IPv4Address -and $_.NetAdapter.Status -eq 'Up' }
$lan = @()
foreach ($c in $cfgs) {
    $ip = $c.IPv4Address.IPAddress | Select-Object -First 1
    $gw = $c.IPv4DefaultGateway.NextHop | Select-Object -First 1
    $desc = "$($c.InterfaceAlias) ($($c.InterfaceDescription))"
    if ($ip -like '169.254.*') { continue }
    if ($c.InterfaceDescription -match $virtual -or $c.InterfaceAlias -match $virtual) {
        if ($c.InterfaceDescription -match $vpnLike -or $c.InterfaceAlias -match $vpnLike) {
            Warn "VPN/туннель: $desc $ip — для телефона не подходит"
        } else {
            Write-Host "         виртуальный адаптер: $desc $ip (не для телефона)"
        }
        continue
    }
    $prof = (Get-NetConnectionProfile -InterfaceIndex $c.InterfaceIndex).NetworkCategory
    Ok "$desc  IP=$ip  шлюз=$gw  профиль=$prof"
    $lan += [pscustomobject]@{ Ip = $ip; Gw = $gw; Index = $c.InterfaceIndex; Alias = $c.InterfaceAlias; Profile = $prof }
}
if (-not $lan) { Bad "Нет ни одной реальной сети (Wi-Fi/Ethernet). Подключись к Wi-Fi или к раздаче с телефона." }

$ssid = (netsh wlan show interfaces) -match '^\s*SSID\s*:' | ForEach-Object { ($_ -split ':', 2)[1].Trim() } | Select-Object -First 1
if ($ssid) { Write-Host "         Wi-Fi сеть (SSID): $ssid" }

Head "VPN на компьютере"
$vpnAdapters = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and ($_.InterfaceDescription -match $vpnLike -or $_.Name -match $vpnLike) }
if ($vpnAdapters) {
    foreach ($v in $vpnAdapters) { Warn "Включён: $($v.Name) ($($v.InterfaceDescription))" }
    foreach ($l in $lan) {
        if (-not $l.Gw) { continue }
        $route = Find-NetRoute -RemoteIPAddress $l.Gw | Where-Object { $_.InterfaceIndex } | Select-Object -First 1
        if ($route.InterfaceIndex -eq $l.Index) {
            Ok "Локальная сеть $($l.Alias) идёт мимо VPN (маршрут к $($l.Gw) через $($l.Alias))"
        } else {
            Bad "Трафик к $($l.Gw) уходит в VPN-адаптер. Включи в VPN «обход локальной сети» (LAN bypass / bypass private IPs)."
        }
    }
} else { Ok "VPN-адаптеров не видно" }

Head "Брандмауэр Windows"
$rule = Get-NetFirewallRule -DisplayName "RoomClimate $Port" -ErrorAction SilentlyContinue | Where-Object { $_.Enabled -eq 'True' }
if ($rule) {
    Ok "Правило «RoomClimate $Port» есть (профили: $($rule.Profile))"
} else {
    Bad "Нет разрешающего правила для порта $Port — телефон будет упираться в брандмауэр."
    Write-Host "         Запусти от имени администратора: powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\open-firewall.ps1"
}

Head "Проверка по адресам локальной сети"
foreach ($l in $lan) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing "http://$($l.Ip):$Port/health" -TimeoutSec 5
        Ok "http://$($l.Ip):$Port/health -> $($r.Content)"
    } catch { Bad "http://$($l.Ip):$Port/health не отвечает: $($_.Exception.Message)" }
}

Head "Что указать в приложении"
foreach ($l in $lan) { Write-Host "  http://$($l.Ip):$Port" -ForegroundColor White }
Write-Host "  Приложение находит сервер само (Wi-Fi, раздача с телефона, раздача с компьютера)."
Write-Host "  Если на телефоне VPN и ничего не помогает — USB: roomclimate\server\scripts\usb-connect.ps1"
Write-Host "  Проверка с телефона: открой в браузере http://<адрес выше>:$Port/health"
