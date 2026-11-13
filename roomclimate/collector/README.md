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

Если на сервере задан `INGEST_TOKEN` (см. `server/README.md`), укажите его и сборщику:

```powershell
$env:INGEST_TOKEN="тот-же-секрет-что-на-сервере"
python collector.py
```

GPIO подключается в `collector.py` (сейчас стоит заглушка случайных значений).

## Автозапуск на Raspberry Pi (служба systemd)

Чтобы сборщик запускался сам при включении платы и перезапускался после сбоев:

```bash
cd roomclimate/collector
chmod +x install-service.sh
sudo ./install-service.sh
```

Скрипт спросит адрес сервера (IP компьютера с Docker, например `http://192.168.1.14:8000`),
имя платы (`pi-1`) и `INGEST_TOKEN` — его взять из `roomclimate/server/.env` на компьютере
(создаётся `scripts/start.ps1`). Настройки сохраняются в `/etc/roomclimate/collector.env`
(права 640, токен не попадает в репозиторий).

| Команда | Что делает |
|---|---|
| `systemctl status roomclimate-collector` | Работает ли служба |
| `journalctl -u roomclimate-collector -f` | Журнал отправки показаний в реальном времени |
| `sudo systemctl restart roomclimate-collector` | Перезапуск (например, после правки `collector.env`) |
| `sudo ./install-service.sh --uninstall` | Удалить службу |

Служба ждёт сеть (`network-online.target`) и перезапускается через 5 с после любого падения;
внутри сборщик и так переживает пропадание сервера (повтор с паузой до 60 с).