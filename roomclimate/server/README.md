# server

REST API на Linux в Docker. Владельцы: Кобзев (код API и БД), Крючков (запуск Compose и сеть).

Поток: `POST /telemetry` → Redis (`current`, `stream`) → обработчик → SQLite.  
Клиент: `POST /auth/register`, `POST /auth/login`, `GET /telemetry/current`, `GET /telemetry/history`, `GET/PUT /settings`.

Пользователи и сессии лежат в той же SQLite (`users`, `sessions`), отдельная БД не нужна.

```powershell
cd server
docker compose up --build -d
python smoke_test.py
```

API: http://127.0.0.1:8000  
Swagger: http://127.0.0.1:8000/docs  

Остановка: `docker compose down`
