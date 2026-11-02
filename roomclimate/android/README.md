## Архитектура Android-приложения

Проект написан на **Java** (Android SDK). Код разделен по слоям:

*   **`ui/`** — экраны: вход, регистрация, дашборд, история, настройки, аккаунт.
*   **`network/`** — Retrofit-клиент к REST API (`ApiClient`, `ApiService`, `ServerConfig`).
*   **`models/`** — Java-классы для JSON от сервера.
*   **`notify/`** — сравнение температуры с порогами и уведомление на телефоне.

### Как запустить

1. Откройте в Android Studio папку: `roomclimate/android/RoomClimateApp`
2. Дождитесь синхронизации Gradle.
3. Убедитесь, что сервер уже запущен: `cd roomclimate/server` и `docker compose up --build -d`
4. Нажмите **Run ▶** для запуска на эмуляторе.

На Android 17 (targetSdk 37) приложению нужно разрешение **Доступ к локальной сети** (`ACCESS_LOCAL_NETWORK`), иначе запросы на `10.0.2.2` и `192.168.x.x` зависают.

Базовый URL сервера задаётся в экране **Настройки** (не хардкод навсегда):

- эмулятор и Docker на одном ПК: `http://10.0.2.2:8000`
- телефон и Docker в одной Wi‑Fi: `http://IP_компьютера:8000`

Запросы приложения:

- `POST /auth/register`, `POST /auth/login`, `GET /auth/me`, `POST /auth/logout`
- `GET /health`
- `GET /telemetry/current?device_id=pi-1`
- `GET /telemetry/history?device_id=pi-1`
- `GET /settings?device_id=pi-1`
- `PUT /settings?device_id=pi-1` тело `{"temp_min":18,"temp_max":26}`

Пользователи хранятся в той же SQLite сервера (`users`, `sessions`).  
Токен после входа/регистрации сохраняется в SharedPreferences — приложение помнит аккаунт.  
`POST /telemetry` вызывает только сборщик на Raspberry Pi.  
Сравнение с порогом и показ уведомления — на телефоне, не в Redis.
