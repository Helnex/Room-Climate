# android

Приложение Java, minSdk 26 (Android 8).  
Интерфейс: Камалетдинов Максим. Сеть, уведомления, Git: Вековшинин Иван.

Создать проект в Android Studio в этой папке (Empty Activity, Java, package `com.roomclimate.app`). Дальше код класть так:

```text
app/src/main/java/com/roomclimate/app/
  ui/         экраны, график, пороги     — Максим
  network/    HTTP к серверу             — Иван
  notify/     уведомления по порогам     — Иван
```

Базовый URL сервера (настройки приложения, не хардкод навсегда):

- эмулятор и Docker на одном ПК: `http://10.0.2.2:8000`
- телефон и Docker в одной Wi‑Fi: `http://192.168.x.x:8000`

Запросы:

- `GET /telemetry/current?device_id=pi-1`
- `GET /telemetry/history?device_id=pi-1`
- `GET /settings?device_id=pi-1`
- `PUT /settings?device_id=pi-1` тело `{"temp_min":18,"temp_max":26}`

Сравнение с порогом и показ уведомления — на телефоне, не в Redis.
