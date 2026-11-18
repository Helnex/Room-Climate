-- Реестр плат и датчиков: какие бывают показатели и виды датчиков, какие платы и датчики есть.
-- Сами экземпляры (конкретные платы и датчики с номерами) добавляются следующими миграциями,
-- см. 002_seed_registry.sql и README «Как добавить плату или датчик».

-- Показатели — то, что плата шлёт в POST /telemetry как readings[].type.
-- Показание с типом, которого здесь нет, сервер не принимает (422).
CREATE TABLE metric_types (
    code TEXT PRIMARY KEY,            -- temperature, humidity, co2 …
    name TEXT NOT NULL,               -- «Температура»
    unit TEXT NOT NULL,               -- «°C» — для подписи в приложении
    position INTEGER NOT NULL DEFAULT 0
);

-- Виды датчиков (модели железа). Один датчик может мерить несколько показателей (DHT22).
CREATE TABLE sensor_types (
    code TEXT PRIMARY KEY,            -- climate, co2, light, motion
    name TEXT NOT NULL,               -- «Температура и влажность»
    short_name TEXT NOT NULL,         -- «Темп. и влажность» — в строках списков
    default_name TEXT NOT NULL,       -- название в приложении по умолчанию: «Климат», «Свет»
    model TEXT,                       -- DHT22, BH1750 …
    icon TEXT NOT NULL DEFAULT 'sensor'
);

CREATE TABLE sensor_type_metrics (
    sensor_type TEXT NOT NULL REFERENCES sensor_types(code),
    metric TEXT NOT NULL REFERENCES metric_types(code),
    PRIMARY KEY (sensor_type, metric)
);

-- Платы: та же таблица devices (id = DEVICE_ID сборщика), плюс номер и понятное название.
-- Зарегистрированная плата — та, у которой есть serial.
ALTER TABLE devices ADD COLUMN serial TEXT;
ALTER TABLE devices ADD COLUMN title TEXT;
ALTER TABLE devices ADD COLUMN model TEXT;
CREATE UNIQUE INDEX idx_devices_serial ON devices(serial) WHERE serial IS NOT NULL;

-- Датчики: конкретные экземпляры с номером. device_id — к какой плате привязан
-- (NULL — свободен). Показания датчика — это показания его платы по показателям его вида.
CREATE TABLE sensor_registry (
    serial TEXT PRIMARY KEY,          -- «0145» — номер на наклейке
    sensor_type TEXT NOT NULL REFERENCES sensor_types(code),
    device_id TEXT REFERENCES devices(id),
    name TEXT,                        -- название в приложении («Свет»)
    bound_at TEXT
);
CREATE INDEX idx_sensor_registry_device ON sensor_registry(device_id);
