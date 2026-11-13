# Настройка почты стенда (коды восстановления пароля) — Яндекс по умолчанию.
# Пароль приложения вводится скрыто и сохраняется только в server\.env (в git не попадает),
# доступ к файлу — только у текущего пользователя Windows.
#
#   powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\set-smtp.ps1
#
# Пароль приложения Яндекса: id.yandex.ru -> Безопасность -> Пароли приложений -> «Почта».
# Обычный пароль от ящика не подойдёт.
param(
    [string]$SmtpHost = 'smtp.yandex.ru',
    [int]$Port = 465,
    [switch]$Remove
)

$server = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $server '.env'
$keys = 'SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASSWORD', 'SMTP_FROM'

$lines = if (Test-Path $envFile) { [IO.File]::ReadAllLines($envFile) } else { @() }
$lines = $lines | Where-Object { $_ -notmatch "^($($keys -join '|'))=" }

if ($Remove) {
    [IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding $false))
    Write-Host "Настройки почты удалены из server\.env. Коды снова пишутся в журнал сервера." -ForegroundColor Green
    exit 0
}

$user = Read-Host "Адрес ящика Яндекса, с которого слать письма (например, roomclimate@yandex.ru)"
if ($user -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') { Write-Host "Это не похоже на email" -ForegroundColor Red; exit 1 }
$secure = Read-Host "Пароль приложения (ввод скрыт)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
if (-not $password) { Write-Host "Пароль пустой" -ForegroundColor Red; exit 1 }
if ($password -match '\s') { Write-Host "В пароле приложения не должно быть пробелов" -ForegroundColor Red; exit 1 }

$lines += "SMTP_HOST=$SmtpHost", "SMTP_PORT=$Port", "SMTP_USER=$user", "SMTP_PASSWORD=$password", "SMTP_FROM=$user"
[IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding $false))
$password = $null

# .env теперь хранит пароль от почты — читать и менять его может только текущий пользователь.
icacls $envFile /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null

Write-Host "Почта сохранена в server\.env. Перезапускаю сервер..." -ForegroundColor Green
docker compose -p roomclimate --project-directory $server -f (Join-Path $server 'docker-compose.yml') up -d api api-tls 2>&1 |
    Select-String 'Started|Error' | ForEach-Object { $_.Line }

$to = Read-Host "Куда отправить пробное письмо (Enter — на $user)"
if (-not $to) { $to = $user }
Start-Sleep 5
docker exec roomclimate-api-1 python -m app.recovery --test $to
if ($LASTEXITCODE -eq 0) {
    Write-Host "Готово: проверьте ящик $to. Коды восстановления теперь приходят письмом." -ForegroundColor Green
} else {
    Write-Host "Письмо не ушло. Проверьте адрес и пароль приложения; журнал: docker logs roomclimate-api-1 --tail 20" -ForegroundColor Red
}
