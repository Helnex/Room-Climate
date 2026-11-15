#!/usr/bin/env bash
# Установка сборщика RoomClimate как службы systemd на Raspberry Pi.
# После этого сборщик запускается сам при включении платы и перезапускается при сбоях.
#
#   cd roomclimate/collector
#   sudo ./install-service.sh                 # спросит адрес сервера, имя платы и INGEST_TOKEN
#   sudo ./install-service.sh --uninstall     # удалить службу
#
# Неинтерактивно (например, по SSH-скрипту):
#   sudo SERVER_URL=http://192.168.1.12:8000 DEVICE_ID=pi-1 INGEST_TOKEN=... DHT_PIN=D4 ./install-service.sh
# SENSOR=fake — без датчика (тестовые значения), библиотеки тогда не ставятся.
set -euo pipefail

SERVICE=roomclimate-collector
UNIT_DST=/etc/systemd/system/${SERVICE}.service
ENV_DIR=/etc/roomclimate
ENV_FILE=${ENV_DIR}/collector.env
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -ne 0 ]]; then
    echo "Нужны права root: sudo $0 $*" >&2
    exit 1
fi

if [[ "${1:-}" == "--uninstall" ]]; then
    systemctl disable --now "$SERVICE" 2>/dev/null || true
    rm -f "$UNIT_DST"
    systemctl daemon-reload
    echo "Служба $SERVICE удалена (настройки остались в $ENV_FILE)."
    exit 0
fi

# Пользователь, от которого работает служба: тот, кто вызвал sudo (не root).
RUN_USER="${SUDO_USER:-pi}"
if ! id "$RUN_USER" >/dev/null 2>&1; then
    echo "Пользователь $RUN_USER не найден" >&2
    exit 1
fi
# Доступ к GPIO (DHT22, датчик движения), UART (CO₂ MH-Z19) и I2C (свет BH1750).
for group in gpio dialout i2c; do
    if getent group "$group" >/dev/null; then
        usermod -aG "$group" "$RUN_USER"
    fi
done

ask() {  # ask ПЕРЕМЕННАЯ "вопрос" "значение по умолчанию"
    local name=$1 prompt=$2 default=${3:-}
    if [[ -z "${!name:-}" ]]; then
        read -r -p "$prompt${default:+ [$default]}: " value
        printf -v "$name" '%s' "${value:-$default}"
    fi
}

ask SERVER_URL "Адрес сервера (компьютер с Docker)" "http://192.168.1.12:8000"
ask DEVICE_ID "Имя платы" "pi-1"
ask PERIOD_SEC "Период отправки, секунд" "10"
ask INGEST_TOKEN "INGEST_TOKEN (из server/.env на компьютере)" ""
ask DHT_PIN "Вывод DATA датчика DHT22 (BCM: D4 = GPIO4, физический пин 7)" "D4"
ask EXTRA_SENSORS "Доп. датчики через запятую: co2, light, motion (пусто — только DHT22)" ""
EXTRA_SENSORS="$(echo "$EXTRA_SENSORS" | tr 'A-Z;' 'a-z,' | tr -d ' ')"
MOTION_PIN="${MOTION_PIN:-D17}"
SENSOR="${SENSOR:-auto}"

if [[ ! "$SERVER_URL" =~ ^https?://[A-Za-z0-9.\-]+(:[0-9]+)?$ ]]; then
    echo "Странный адрес сервера: $SERVER_URL (ожидается вида http://192.168.1.12:8000)" >&2
    exit 1
fi
if [[ ! "$DEVICE_ID" =~ ^[A-Za-z0-9_.\-]+$ ]]; then
    echo "Имя платы: только латинские буквы, цифры и _ . -" >&2
    exit 1
fi
if [[ ! "$PERIOD_SEC" =~ ^[0-9]+$ ]]; then
    echo "Период — целое число секунд" >&2
    exit 1
fi
if [[ ! "$DHT_PIN" =~ ^D[0-9]{1,2}$ ]]; then
    echo "Вывод датчика: вида D4 (номер GPIO по BCM)" >&2
    exit 1
fi
if [[ ! "$EXTRA_SENSORS" =~ ^((co2|light|motion)(,(co2|light|motion))*)?$ ]]; then
    echo "Доп. датчики: co2, light, motion через запятую" >&2
    exit 1
fi
if [[ ! "$MOTION_PIN" =~ ^D[0-9]{1,2}$ ]]; then
    echo "MOTION_PIN: вида D17 (номер GPIO по BCM)" >&2
    exit 1
fi
if [[ ",$EXTRA_SENSORS," == *",co2,"* ]] && [[ ! -e /dev/serial0 ]]; then
    echo "Внимание: нет /dev/serial0 — включите UART: sudo raspi-config → Interface Options → Serial Port (консоль — нет, порт — да)" >&2
fi
if [[ ",$EXTRA_SENSORS," == *",light,"* ]] && [[ ! -e /dev/i2c-1 ]]; then
    echo "Внимание: нет /dev/i2c-1 — включите I2C: sudo raspi-config → Interface Options → I2C" >&2
fi
if [[ ! "$SENSOR" =~ ^(auto|dht22|fake)$ ]]; then
    echo "SENSOR: auto, dht22 или fake" >&2
    exit 1
fi
if [[ -z "$INGEST_TOKEN" ]]; then
    echo "Внимание: INGEST_TOKEN пустой — сервер со включённым токеном будет отклонять показания (401)." >&2
fi

# Библиотека DHT22 — в отдельном окружении .venv рядом со сборщиком: в Raspberry Pi OS
# (Bookworm) системный pip запрещён (PEP 668). libgpiod нужен для чтения датчика.
VENV="$DIR/.venv"
if [[ "$SENSOR" != "fake" ]]; then
    if command -v apt-get >/dev/null; then
        apt-get install -y python3-venv python3-pip libgpiod2 >/dev/null             || echo "Внимание: apt-get не поставил python3-venv/libgpiod2 — проверьте сеть" >&2
    fi
    if [[ ! -x "$VENV/bin/python" ]]; then
        sudo -u "$RUN_USER" python3 -m venv "$VENV"
    fi
    if sudo -u "$RUN_USER" "$VENV/bin/pip" install -q -r "$DIR/requirements.txt"; then
        echo "Библиотека DHT22 установлена в $VENV"
    else
        echo "Внимание: библиотека DHT22 не установилась — сборщик будет слать тестовые значения" >&2
    fi
fi
PYTHON=/usr/bin/python3
if [[ -x "$VENV/bin/python" ]]; then
    PYTHON="$VENV/bin/python"
fi

install -d -m 755 "$ENV_DIR"
umask 077
cat > "$ENV_FILE" <<EOF
SERVER_URL=$SERVER_URL
DEVICE_ID=$DEVICE_ID
PERIOD_SEC=$PERIOD_SEC
INGEST_TOKEN=$INGEST_TOKEN
SENSOR=$SENSOR
DHT_PIN=$DHT_PIN
EXTRA_SENSORS=$EXTRA_SENSORS
MOTION_PIN=$MOTION_PIN
EOF
chown root:"$RUN_USER" "$ENV_FILE"
chmod 640 "$ENV_FILE"

sed -e "s|@USER@|$RUN_USER|g" -e "s|@DIR@|$DIR|g" -e "s|@PYTHON@|$PYTHON|g" "$DIR/${SERVICE}.service" > "$UNIT_DST"
chmod 644 "$UNIT_DST"

systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null
systemctl restart "$SERVICE"
sleep 2
if systemctl is-active --quiet "$SERVICE"; then
    echo "Служба запущена и будет стартовать при включении платы."
    echo "Статус:  systemctl status $SERVICE"
    echo "Журнал:  journalctl -u $SERVICE -f"
else
    echo "Служба не запустилась. Журнал: journalctl -u $SERVICE -n 50" >&2
    exit 1
fi
