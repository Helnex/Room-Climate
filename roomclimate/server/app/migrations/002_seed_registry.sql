-- Справочники и стартовый набор плат и датчиков стенда.

INSERT INTO metric_types(code, name, unit, position) VALUES
    ('temperature', 'Температура', '°C', 1),
    ('humidity', 'Влажность', '%', 2),
    ('co2', 'Углекислый газ', 'ppm', 3),
    ('light', 'Освещённость', 'лк', 4),
    ('motion', 'Движение', 'да / нет', 5);

INSERT INTO sensor_types(code, name, short_name, default_name, model, icon) VALUES
    ('climate', 'Температура и влажность', 'Темп. и влажность', 'Климат', 'DHT22', 'climate'),
    ('co2', 'Углекислый газ', 'CO₂', 'CO₂', 'MH-Z19B', 'co2'),
    ('light', 'Освещённость', 'Освещённость', 'Свет', 'BH1750', 'light'),
    ('motion', 'Движение', 'Движение', 'Движение', 'HC-SR501', 'motion');

INSERT INTO sensor_type_metrics(sensor_type, metric) VALUES
    ('climate', 'temperature'),
    ('climate', 'humidity'),
    ('co2', 'co2'),
    ('light', 'light'),
    ('motion', 'motion');

-- Платы: pi-1 — Raspberry Pi стенда, pi-2 и pi-3 — генераторы данных
-- (collector.py с SENSOR=fake или simulate_collector.py --device pi-2).
INSERT OR IGNORE INTO devices(id, name, created_at) VALUES
    ('pi-1', 'pi-1', strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    ('pi-2', 'pi-2', strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    ('pi-3', 'pi-3', strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'));
UPDATE devices SET serial = '0001', title = 'Плата № 0001', model = 'Raspberry Pi' WHERE id = 'pi-1';
UPDATE devices SET serial = '0002', title = 'Плата № 0002', model = 'Генератор данных' WHERE id = 'pi-2';
UPDATE devices SET serial = '0003', title = 'Плата № 0003', model = 'Генератор данных' WHERE id = 'pi-3';

-- Датчики (все свободны — к плате их привязывают в приложении: «Добавить датчик»).
INSERT INTO sensor_registry(serial, sensor_type) VALUES
    ('0142', 'climate'),
    ('0143', 'climate'),
    ('0144', 'climate'),
    ('0145', 'light'),
    ('0146', 'co2'),
    ('0147', 'motion');
