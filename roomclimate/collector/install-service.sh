#!/usr/bin/env bash
# Установка сборщика RoomClimate как службы systemd на Raspberry Pi.
# После этого сборщик запускается сам при включении платы и перезапускается при сбоях.
#
#   cd roomclimate/collector
#   sudo ./install-service.sh                 # спросит адрес сервера, имя платы и INGEST_TOKEN
#   sudo ./install-service.sh --uninstall     # удалить службу
#
# Неинтерактивно (например, по SSH-скрипту):
#   sudo SERVER_URL=http://192.168.1.14:8000 DEVICE_ID=pi-1 INGEST_TOKEN=... ./install-service.sh
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
# Доступ к GPIO для датчика DHT22.
if getent group gpio >/dev/null; then
    usermod -aG gpio "$RUN_USER"
fi

ask() {  # ask ПЕРЕМЕННАЯ "вопрос" "значение по умолчанию"
    local name=$1 prompt=$2 default=${3:-}
    if [[ -z "${!name:-}" ]]; then
        read -r -p "$prompt${default:+ [$default]}: " value
        printf -v "$name" '%s' "${value:-$default}"
    fi
}

ask SERVER_URL "Адрес сервера (компьютер с Docker)" "http://192.168.1.14:8000"
ask DEVICE_ID "Имя платы" "pi-1"
ask PERIOD_SEC "Период отправки, секунд" "10"
ask INGEST_TOKEN "INGEST_TOKEN (из server/.env на компьютере)" ""

if [[ ! "$SERVER_URL" =~ ^https?://[A-Za-z0-9.\-]+(:[0-9]+)?$ ]]; then
    echo "Странный адрес сервера: $SERVER_URL (ожидается вида http://192.168.1.14:8000)" >&2
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
if [[ -z "$INGEST_TOKEN" ]]; then
    echo "Внимание: INGEST_TOKEN пустой — сервер со включённым токеном будет отклонять показания (401)." >&2
fi

install -d -m 755 "$ENV_DIR"
umask 077
cat > "$ENV_FILE" <<EOF
SERVER_URL=$SERVER_URL
DEVICE_ID=$DEVICE_ID
PERIOD_SEC=$PERIOD_SEC
INGEST_TOKEN=$INGEST_TOKEN
EOF
chown root:"$RUN_USER" "$ENV_FILE"
chmod 640 "$ENV_FILE"

sed -e "s|@USER@|$RUN_USER|g" -e "s|@DIR@|$DIR|g" "$DIR/${SERVICE}.service" > "$UNIT_DST"
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
