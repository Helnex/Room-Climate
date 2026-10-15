# collector

Программа на Raspberry Pi. Владелец: Хабарова Анастасия.

Сборщик читает DHT22 через GPIO и делает `POST {SERVER_URL}/telemetry`. На плате нет API и нет базы.

Пока нет железа — с ПК, где уже запущен Docker-сервер:

```powershell
cd collector
python collector.py
```

По умолчанию `SERVER_URL=http://127.0.0.1:8000`. На Pi указать IP компьютера с Docker, например:

```powershell
$env:SERVER_URL="http://192.168.1.50:8000"
python collector.py
```

GPIO подключается в `collector.py` (сейчас стоит заглушка случайных значений).
