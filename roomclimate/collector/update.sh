#!/usr/bin/env bash
# Обновление сборщика RoomClimate на Raspberry Pi одной командой:
# скачать новый код из GitHub, доставить библиотеки, обновить службу и перезапустить её.
#
#   cd roomclimate/collector
#   sudo ./update.sh            # обновить и перезапустить
#   ./update.sh --check         # только показать, есть ли обновления (без sudo)
#
# Настройки (/etc/roomclimate/collector.env) и буфер показаний не трогаются.
set -euo pipefail

SERVICE=roomclimate-collector
UNIT_DST=/etc/systemd/system/${SERVICE}.service
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(git -C "$DIR" rev-parse --show-toplevel)"
OWNER="$(stat -c %U "$REPO")"
CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

# git — от имени владельца репозитория, чтобы файлы не стали принадлежать root.
as_owner() {
    if [[ $EUID -eq 0 && "$OWNER" != "root" ]]; then
        sudo -u "$OWNER" "$@"
    else
        "$@"
    fi
}

BRANCH="$(as_owner git -C "$REPO" rev-parse --abbrev-ref HEAD)"
if [[ "$BRANCH" == "HEAD" ]]; then
    echo "Репозиторий не на ветке (detached HEAD) — переключитесь: git checkout main" >&2
    exit 1
fi

echo "Проверяю обновления ($BRANCH)..."
as_owner git -C "$REPO" fetch --quiet origin "$BRANCH"
OLD="$(as_owner git -C "$REPO" rev-parse HEAD)"
NEW="$(as_owner git -C "$REPO" rev-parse "origin/$BRANCH")"

if [[ "$OLD" == "$NEW" ]]; then
    echo "Уже последняя версия: $(as_owner git -C "$REPO" log -1 --format='%h %s')"
    exit 0
fi

echo "Новые изменения:"
as_owner git -C "$REPO" log --format='  %h %s' "$OLD..$NEW"
if [[ $CHECK_ONLY -eq 1 ]]; then
    echo "Обновить: sudo $0"
    exit 0
fi

if [[ $EUID -ne 0 ]]; then
    echo "Нужны права root для перезапуска службы: sudo $0" >&2
    exit 1
fi

if [[ -n "$(as_owner git -C "$REPO" status --porcelain --untracked-files=no)" ]]; then
    echo "В репозитории есть несохранённые правки — обновление остановлено, чтобы их не потерять:" >&2
    as_owner git -C "$REPO" status --short --untracked-files=no >&2
    echo "Сохраните их (git stash) или отмените (git checkout -- .), затем запустите снова." >&2
    exit 1
fi

as_owner git -C "$REPO" merge --ff-only --quiet "origin/$BRANCH"
changed() { as_owner git -C "$REPO" diff --quiet "$OLD" "$NEW" -- "$@" && return 1 || return 0; }

# Библиотеки — только если поменялся requirements.txt.
VENV="$DIR/.venv"
if changed roomclimate/collector/requirements.txt && [[ -x "$VENV/bin/pip" ]]; then
    echo "Обновляю библиотеки..."
    as_owner "$VENV/bin/pip" install -q -r "$DIR/requirements.txt" \
        || echo "Внимание: библиотеки не обновились — проверьте сеть" >&2
fi

# Файл службы — пересобираем, если поменялся шаблон (так же, как install-service.sh).
if [[ -f "$UNIT_DST" ]] && changed roomclimate/collector/${SERVICE}.service; then
    echo "Обновляю службу systemd..."
    RUN_USER="$(sed -n 's/^User=//p' "$UNIT_DST")"
    PYTHON=/usr/bin/python3
    [[ -x "$VENV/bin/python" ]] && PYTHON="$VENV/bin/python"
    sed -e "s|@USER@|$RUN_USER|g" -e "s|@DIR@|$DIR|g" -e "s|@PYTHON@|$PYTHON|g" \
        "$DIR/${SERVICE}.service" > "$UNIT_DST"
    systemctl daemon-reload
fi

if systemctl list-unit-files "$SERVICE.service" >/dev/null 2>&1 && [[ -f "$UNIT_DST" ]]; then
    systemctl restart "$SERVICE"
    sleep 2
    if systemctl is-active --quiet "$SERVICE"; then
        echo "Готово: $(git -C "$REPO" log -1 --format='%h %s'), служба перезапущена."
    else
        echo "Служба не запустилась после обновления. Журнал: journalctl -u $SERVICE -n 50" >&2
        echo "Вернуть прежнюю версию: git -C $REPO reset --hard $OLD && sudo systemctl restart $SERVICE" >&2
        exit 1
    fi
else
    echo "Код обновлён. Служба не установлена — установите: sudo ./install-service.sh"
fi
