## 🏗 Архитектура Android-приложения

Проект написан на **Java** (Android SDK). Код разделен по слоям для удобства масштабирования и командной работы:

*   **`ui/`** — Слой интерфейса.
*   **`network/`** — Сетевой слой.
*   **`models/`** — Слой данных. Java-классы для парсинга JSON от сервера
*   **`notify/`** — Слой уведомлений

###  Как это запустить (Инструкция)
1. Склонируйте репозиторий: `git clone https://github.com/Helnex/Room-Climate.git`
2. Откройте в Android Studio папку: `roomclimate/android/RoomClimateApp`
3. Дождитесь синхронизации Gradle.
4. Нажмите **Run ▶** для запуска на эмуляторе.

Базовый URL сервера (настройки приложения, не хардкод навсегда):

- эмулятор и Docker на одном ПК: `http://10.0.2.2:8000`
- телефон и Docker в одной Wi‑Fi: `http://192.168.x.x:8000`

Запросы:

- `GET /telemetry/current?device_id=pi-1`
- `GET /telemetry/history?device_id=pi-1`
- `GET /settings?device_id=pi-1`
- `PUT /settings?device_id=pi-1` тело `{"temp_min":18,"temp_max":26}`

Сравнение с порогом и показ уведомления — на телефоне, не в Redis.
