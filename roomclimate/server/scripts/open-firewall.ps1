# Открывает входящий TCP-порт сервера в брандмауэре Windows для всех профилей сети
# (раздача с телефона обычно определяется как «Общедоступная» сеть, где входящие закрыты).
# Нужны права администратора:
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\open-firewall.ps1
param([int]$Port = 8000)

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Write-Host "Нужны права администратора. Открой PowerShell «от имени администратора» и запусти скрипт снова." -ForegroundColor Red
    exit 1
}

$name = "RoomClimate $Port"
$existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($existing) {
    Set-NetFirewallRule -DisplayName $name -Enabled True -Action Allow -Profile Any | Out-Null
    Write-Host "Правило «$name» уже было — включено для всех профилей." -ForegroundColor Green
} else {
    New-NetFirewallRule -DisplayName $name -Direction Inbound -Protocol TCP -LocalPort $Port `
        -Action Allow -Profile Any -Description "RoomClimate: доступ телефона к Docker-серверу" | Out-Null
    Write-Host "Добавлено правило «$name»: входящий TCP $Port разрешён." -ForegroundColor Green
}
Write-Host "Удалить правило: Remove-NetFirewallRule -DisplayName '$name'"
