# RoomClimate

Мониторинг температуры и влажности: Raspberry Pi → REST API (Linux, Docker) → Android.

Клонирование:

```text
git clone <URL_репозитория>
cd roomclimate
```

Дальше каждый работает **только в своей папке** и не переписывает чужие модули.

| Папка | Кто | Что внутри |
|---|---|---|
| `server/` | Кобзев Станислав, Крючков Кирилл | REST API, Redis, SQLite, Docker Compose |
| `collector/` | Хабарова Анастасия | Сборщик на Raspberry Pi |
| `android/` | Камалетдинов Максим, Вековшинин Иван | Приложение Java |

Как модули стыкуются: плата и телефон ходят по HTTP на `http://IP_хоста:8000`. IP — машина, где запущен Docker (локально свой ПК, на защите — стенд Кирилла).

## Быстрая проверка сервера без платы и без телефона

Нужен Docker Desktop.

```powershell
cd server
docker compose up --build -d
python smoke_test.py
```

Документация API: http://127.0.0.1:8000/docs

Имитация датчика: `python simulate_collector.py`

## Android

В приложении базовый URL:

- эмулятор на том же ПК, что Docker: `http://10.0.2.2:8000`
- живой телефон в той же Wi‑Fi: `http://IP_компьютера:8000`

## Плата

В `collector` переменная `SERVER_URL=http://IP_компьютера:8000`. На самой Pi не использовать `localhost` — это адрес платы, не сервера.

## Git

Ветки от `main`: `server/...`, `collector/...`, `android/...`. В одном PR не смешивать чужие папки.
