# server

REST API на Linux в Docker. Владельцы: Кобзев (код API и БД), Крючков (запуск Compose и сеть).

Поток: `POST /telemetry` → Redis (`current`, `stream`) → обработчик → SQLite.  
Клиент: `POST /auth/register`, `POST /auth/login`, `GET /telemetry/current`, `GET /telemetry/history`, `GET/PUT /settings`.

Пользователи и сессии лежат в той же SQLite (`users`, `sessions`), отдельная БД не нужна.

## Безопасность

- `GET /telemetry/current`, `GET /telemetry/history`, `GET/PUT /settings`, `GET /auth/me` требуют
  заголовок `Authorization: Bearer <token>` (токен выдают `/auth/register` и `/auth/login`).
- В БД хранится только SHA-256 токена; сессия живёт `SESSION_TTL_DAYS` дней (по умолчанию 30).
- После 10 неудачных попыток входа на один email вход блокируется на 15 минут (ответ 429).
- `POST /telemetry` (плата) защищается общим секретом. Создайте `server/.env`:

  ```text
  INGEST_TOKEN=длинная-случайная-строка
  ```

  Сгенерировать: `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
  Тот же `INGEST_TOKEN` задайте сборщику на Raspberry Pi. Если переменная пустая, сервер
  принимает показания от любого устройства в сети и пишет предупреждение в лог.
- Все ошибки API возвращаются строкой `detail` на русском языке.

```powershell
cd server
docker compose up --build -d
python smoke_test.py
```

Если задан `INGEST_TOKEN`, перед `smoke_test.py` выполните `$env:INGEST_TOKEN="..."` (PowerShell).

API: http://127.0.0.1:8000  
Swagger: http://127.0.0.1:8000/docs  

Остановка: `docker compose down`

## Подключение телефона: Wi‑Fi, раздача интернета, VPN

Приложение само ищет сервер в таком порядке: сохранённый адрес → USB (`127.0.0.1:8000`) →
шлюз сети (компьютер раздаёт интернет) → перебор подсетей Wi‑Fi **и точки доступа телефона**.

| Расклад | Что сделать |
|---|---|
| Телефон и компьютер в одной Wi‑Fi | Ничего, найдётся сам |
| **Телефон раздаёт интернет, компьютер подключён к нему** | Ничего, приложение ищет и в подсети точки доступа |
| Компьютер раздаёт интернет (мобильный хот-спот Windows) | Ничего, сервер = шлюз телефона |
| VPN на компьютере (FlClash, Clash, v2ray …) | Включить в VPN «обход локальной сети» (bypass LAN / private IPs); `network-check.ps1` покажет, уходит ли LAN в туннель |
| VPN на телефоне | Добавить RoomClimate в исключения VPN или включить «обход локальной сети». Если VPN так не умеет — USB |
| Ничего не помогает | USB-кабель: `scripts\usb-connect.ps1` — работает при любом VPN |

Скрипты (из корня репозитория):

```powershell
# диагностика: порт, сети, VPN, брандмауэр, адрес для приложения
powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\network-check.ps1
# один раз, PowerShell от имени администратора: открыть порт 8000 (раздача = «Общедоступная» сеть)
powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\open-firewall.ps1
# телефон по USB (adb reverse), после каждого подключения кабеля
powershell -ExecutionPolicy Bypass -File roomclimate\server\scripts\usb-connect.ps1
```

Если на компьютере остались старые контейнеры с тем же именем проекта `server`, запускайте
со своим именем: `docker compose -p roomclimate up -d --build`.