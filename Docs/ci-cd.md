# CI/CD: ветки, pull request, автоматические проверки

Материалы лабораторной работы № 6. Платформа — **GitHub Actions** (репозиторий на GitHub).
Конфигурации пайплайнов — в [`.github/workflows`](../.github/workflows).

## 1. Рабочий процесс «ветка → pull request → проверки → merge»

1. Задача берётся с доски ([sprints.md](sprints.md)) и переносится в «В работе».
2. От свежего `main` создаётся короткая ветка под задачу. Префикс — по виду работы:
   `feature/…` (новая возможность), `fix/…` (исправление), `docs/…`, `devops/…`, `qa/…`,
   `ui/…`. Пример: `feature/device-registry`, `fix/app-bugs`.
3. Изменения коммитятся в ветку и отправляются на GitHub (`git push -u origin <ветка>`).
   Прямой push в `main` запрещён правилом команды; у участников стоит git-хук `pre-push`,
   который отклоняет push в `main`.
4. Открывается **pull request** в `main` по шаблону [`.github/pull_request_template.md`](../.github/pull_request_template.md):
   что сделано, ссылка на карточку, как проверено. Карточка переходит в «На проверке».
5. На PR автоматически запускаются пайплайны тех модулей, которые он затрагивает. Статус
   (зелёный / красный) виден во вкладке **Checks** и внизу PR.
6. **Код-ревью:** PR просматривает хотя бы один участник, отвечающий за затронутый модуль
   (сервер — Кобзев С., приложение — Камалетдинов М. или Вековшинин И., плата — Хабарова А.,
   инфраструктура и CI — Крючков К.).
7. Влить PR можно только при зелёном CI и одобрении ревьюера. Вливает владелец репозитория
   (тимлид Кобзев С.); после слияния ветка удаляется, карточка — в «Готово».

С 22.10 по 26.11.2026 через этот процесс в `main` влит 21 pull request (#2–#23, #14 вошёл в состав #15).

## 2. Пайплайны

| Пайплайн | Файл | Когда запускается | Шаги |
|---|---|---|---|
| `server-ci` | [server.yml](../.github/workflows/server.yml) | PR и push с изменениями в `roomclimate/server/**`, `Docs/api/**` | получить код → Python 3.11 → установить зависимости → **unit-тесты по спецификациям UT-01…UT-19** → **контрактный тест OpenAPI** → остальные unit-тесты (тревоги, восстановление пароля, обновление, помещения, плата, справочники) → собрать и поднять стек Docker Compose → смоук-тест живого API → остановить стек |
| `collector-ci` | [collector.yml](../.github/workflows/collector.yml) | PR и push с изменениями в `roomclimate/collector/**` | получить код → Python 3.11 → все `test_*.py` сборщика (в т. ч. UT-20…UT-23) → проверка синтаксиса shell-скриптов |
| `android-ci` | [android.yml](../.github/workflows/android.yml) | PR и push с изменениями в `roomclimate/android/**` или `roomclimate/server/**` | получить код → JDK 17 → Android SDK → поднять сервер в Docker → дождаться `/health` → собрать debug-APK и прогнать 34 JUnit-теста (включая интеграционный с живым сервером) → при ошибке — отчёт тестов артефактом → APK артефактом → остановить сервер |
| `release-apk` (в `android.yml`) | — | только после слияния в `main` | собрать подписанный release-APK и выложить его на сервер — приложения у пользователей предлагают обновиться (непрерывная поставка) |

Порядок шагов — **от дешёвого к дорогому**: быстрые unit-тесты (секунды) идут до сборки
Docker-образа и смоук-теста (десятки секунд), а сборка APK — отдельным пайплайном. Если
сломан код правил, ошибка видна через несколько секунд и дорогие шаги не запускаются.

Фрагмент `server.yml` — шаги с тестами по спецификациям лабораторной № 5:

```yaml
      - name: Install dependencies for unit tests
        run: python3 -m pip install -r requirements.txt httpx pyyaml

      # Спецификации UT-01…UT-19 из Docs/testing.md (лабораторные № 5–6), фреймворк unittest.
      - name: Unit tests (lab specs UT-01…UT-19)
        run: python3 -m unittest -v test_lab_specs

      # Сервер соответствует контракту Docs/api/openapi.yaml (лабораторная № 7).
      - name: Contract test (OpenAPI)
        run: python3 -m unittest -v test_contract
```

## 3. Реализованный тест

Спецификация **UT-02** из [testing.md](testing.md):

| № | Модуль / операция | Вход | Ожидаемый выход | Класс |
|---|---|---|---|---|
| UT-02 | Пороги / сохранить | Сохранены 20…24; `temp_min=25, temp_max=24` | 400, «Минимальная температура должна быть меньше максимальной»; сохранены по-прежнему 20…24 | отрицательный |

Реализация в [`roomclimate/server/test_lab_specs.py`](../roomclimate/server/test_lab_specs.py)
(фреймворк `unittest` из семейства xUnit, схема «подготовить — выполнить — проверить»):

```python
def test_ut02_min_greater_than_max_rejected(self):
    # Подготовить: у платы сохранены пороги 20…24 °C
    device = "lab-ut02"
    self.client.put(f"/settings?device_id={device}", headers=self.owner, json={"temp_min": 20, "temp_max": 24})
    # Выполнить: попытка сохранить минимум больше максимума
    r = self.client.put(f"/settings?device_id={device}", headers=self.owner, json={"temp_min": 25, "temp_max": 24})
    # Проверить: 400 с понятным текстом, сохранённые значения не изменились
    self.assertEqual(r.status_code, 400)
    self.assertEqual(r.json(), {"detail": "Минимальная температура должна быть меньше максимальной"})
    kept = self.client.get(f"/settings?device_id={device}", headers=self.owner).json()
    self.assertEqual((kept["temp_min"], kept["temp_max"]), (20, 24), "сохранённые пороги изменились")
```

Всего по спецификациям реализовано 23 теста (UT-01…UT-23). Локальный запуск одной командой:

```powershell
cd roomclimate/server
python -m unittest -v test_lab_specs
```

## 4. Демонстрация «зелёный → красный → зелёный»

Показана на учебном pull request, в котором намеренно вносится ошибка в правило порогов:
проверка `if body.temp_min >= body.temp_max` заменяется на `>`, то есть равные минимум и
максимум перестают отклоняться. Результаты прогонов — в разделе 4 отчёта по лабораторной
№ 6 (скриншоты статуса проверок в PR).

1. PR с корректным изменением — `server-ci` **зелёный**.
2. Коммит с ошибкой — тест **UT-03** («равные минимум и максимум отклоняются») падает,
   `server-ci` **красный**, PR помечен «Some checks were not successful».
3. Коммит с исправлением — `server-ci` снова **зелёный**. Учебный PR закрывается без слияния.

## 5. Защита ветки `main`

Требуемые настройки (Settings → Branches → Branch protection rule для `main`, или
Settings → Rules → Rulesets):

- **Require a pull request before merging**, **Require approvals: 1** — нужен одобренный ревьюер;
- **Require status checks to pass before merging** — обязательные проверки `smoke` (server-ci),
  `test` (collector-ci), `build-and-test` (android-ci); **Require branches to be up to date**;
- **Do not allow bypassing the above settings**; запрет force-push и удаления ветки.

Настройки меняет только владелец репозитория (Кобзев С.) — задача T-37 Спринта 4. До их
включения правило соблюдается организационно и git-хуком `pre-push` у участников.

## 6. Секреты

- В репозитории нет паролей, ключей и токенов: проверено поиском по истории файлов
  (`.env`, `*.jks`, `*.pem`, токены вида `ghp_…`, присвоения `INGEST_TOKEN=…` и т. п. —
  совпадений нет).
- Секреты сервера (`INGEST_TOKEN`, `NTFY_TOPIC`, `REGISTER_INVITE_CODE`, `SMTP_PASSWORD`,
  `CF_API_TOKEN`, `APP_PUBLISH_TOKEN`) хранятся в `roomclimate/server/.env`, который в
  `.gitignore`; в репозитории — только шаблон `.env.example` без значений. Код обращается к
  ним по имени через переменные окружения (`app/config.py`, `docker-compose.yml`).
- Ключ подписи Android-приложения — в `android/RoomClimateApp/signing/` (в `.gitignore`); для
  CI — секреты GitHub `RC_KEYSTORE_BASE64`, `RC_KEYSTORE_PASSWORD`, `APP_PUBLISH_TOKEN`
  (Settings → Secrets and variables → Actions), в `android.yml` — только `${{ secrets.… }}`.
- В тестах используются заведомо тестовые значения (`lab-test-device-token`), а не настоящие.

## 7. Дополнительно: контейнеризация и поставка

- Сервер упакован в Docker: `Dockerfile` + `docker-compose.yml` (api, api-tls, worker, redis,
  ntfy; профиль `public` — Caddy и DDNS). Стек одинаково запускается у разработчиков, в CI
  (смоук-тест и интеграционные тесты Android) и на стенде.
- Окружения: **dev** — Docker на ноутбуке разработчика; **CI** — временный стек в GitHub
  Actions; **стенд (prod для демонстрации)** — ПК в локальной сети с доступом из интернета
  `https://api.climate-room.online`. Стенд обновляется из `main` задачей «RoomClimate rebuild».
- Непрерывная поставка приложения: после слияния в `main` задача `release-apk` собирает
  подписанный APK и публикует его на стенде (`PUT /app/publish`), телефоны получают
  предложение обновиться. Сервер развёртывается на стенде вручную запуском пересборки —
  это поставка (delivery), а не автоматическое развёртывание (deployment).
