"""Аналитика по показаниям: точка росы, комфорт, риск плесени, сводка за период.

Чистые функции без БД и Redis — проверяются тестом test_insights.py.
"""
from __future__ import annotations

import math
from datetime import datetime, timezone


def dew_point(temp_c: float, humidity: float) -> float | None:
    """Точка росы по формуле Магнуса (точность ~0.4 °C в диапазоне -45…60 °C)."""
    if humidity is None or temp_c is None or humidity <= 0 or humidity > 100:
        return None
    a, b = 17.62, 243.12
    gamma = math.log(humidity / 100.0) + a * temp_c / (b + temp_c)
    return round(b * gamma / (a - gamma), 1)


def comfort(temp_c: float | None, humidity: float | None) -> dict:
    """Уровень комфорта для жилых и рабочих помещений (СанПиН: 18–26 °C, 30–60 %)."""
    if temp_c is None and humidity is None:
        return {"code": "unknown", "label": "Нет данных"}
    if temp_c is not None and temp_c < 18:
        return {"code": "cold", "label": "Холодно"}
    if temp_c is not None and temp_c > 26:
        return {"code": "hot", "label": "Жарко"}
    if humidity is not None and humidity < 30:
        return {"code": "dry", "label": "Сухо"}
    if humidity is not None and humidity > 60:
        return {"code": "humid", "label": "Влажно"}
    return {"code": "comfort", "label": "Комфортно"}


def mold_risk(temp_c: float | None, humidity: float | None) -> dict:
    """Риск плесени: споры прорастают при длительной влажности выше 70–80 %."""
    if humidity is None:
        return {"code": "unknown", "label": "Нет данных"}
    warm = temp_c is None or temp_c >= 15
    if humidity >= 80 or (humidity >= 70 and warm):
        return {"code": "high", "label": "Высокий"}
    if humidity >= 65:
        return {"code": "medium", "label": "Средний"}
    return {"code": "low", "label": "Низкий"}


def advice(temp_c: float | None, humidity: float | None) -> list[str]:
    tips: list[str] = []
    if humidity is not None and humidity > 60:
        tips.append("Проветрите помещение или включите осушитель")
    if humidity is not None and humidity < 30:
        tips.append("Воздух сухой — включите увлажнитель")
    if temp_c is not None and temp_c > 26:
        tips.append("Жарко — проветрите или включите кондиционер")
    if temp_c is not None and temp_c < 18:
        tips.append("Холодно — проверьте отопление")
    if not tips and (temp_c is not None or humidity is not None):
        tips.append("Климат в норме")
    return tips


def stats(values: list[float]) -> dict | None:
    if not values:
        return None
    return {
        "min": round(min(values), 1),
        "max": round(max(values), 1),
        "avg": round(sum(values) / len(values), 1),
        "count": len(values),
    }


def parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def age_seconds(measured_at: str | None, now: datetime | None = None) -> int | None:
    dt = parse_iso(measured_at)
    if dt is None:
        return None
    now = now or datetime.now(timezone.utc)
    return max(0, int((now - dt).total_seconds()))


def threshold_state(value: float | None, low: float | None, high: float | None) -> str:
    if value is None:
        return "unknown"
    if low is not None and value < low:
        return "below"
    if high is not None and value > high:
        return "above"
    return "ok"
