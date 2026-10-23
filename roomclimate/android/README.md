## 🏗 Архитектура Android-приложения

Проект написан на **Java** (Android SDK). Код разделен по слоям для удобства масштабирования и командной работы:

*   **`ui/`** — Слой интерфейса. Содержит экраны `MainActivity`, `RegisterActivity`, `AccountActivity`. Отвечает за отображение и навигацию. *(В работе у Максима)*.
*   **`network/`** — Сетевой слой. Содержит `ApiClient` (настройка Retrofit), `ApiService` (эндпоинты сервера) и `Repository` (связующее звено для запросов).
*   **`models/`** — Слой данных. Java-классы для парсинга JSON от сервера (`LoginRequest`, `LoginResponse`, `Telemetry`).
*   **`notify/`** — Слой уведомлений (заготовка для будущих push-сообщений).

###  Как это запустить (Инструкция)
1. Склонируйте репозиторий: `git clone https://github.com/Helnex/Smart-House.git`
2. Откройте в Android Studio папку: `roomclimate/android/app`
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
