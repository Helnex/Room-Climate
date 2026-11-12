# -*- coding: utf-8 -*-
"""Тесты расчётов app/insights.py без Docker: python test_insights.py"""
from datetime import datetime, timezone

from app import insights


def test_dew_point():
    # Справочные значения: 20 °C / 50 % -> ~9.3 °C; 25 °C / 80 % -> ~21.3 °C
    assert abs(insights.dew_point(20, 50) - 9.3) <= 0.2
    assert abs(insights.dew_point(25, 80) - 21.3) <= 0.2
    assert insights.dew_point(20, 0) is None
    assert insights.dew_point(20, 150) is None


def test_comfort():
    assert insights.comfort(22, 45)["code"] == "comfort"
    assert insights.comfort(16, 45)["code"] == "cold"
    assert insights.comfort(28, 45)["code"] == "hot"
    assert insights.comfort(22, 25)["code"] == "dry"
    assert insights.comfort(22, 65)["code"] == "humid"
    assert insights.comfort(None, None)["code"] == "unknown"


def test_mold_risk():
    assert insights.mold_risk(22, 50)["code"] == "low"
    assert insights.mold_risk(22, 66)["code"] == "medium"
    assert insights.mold_risk(22, 72)["code"] == "high"
    assert insights.mold_risk(10, 72)["code"] == "medium"
    assert insights.mold_risk(10, 85)["code"] == "high"


def test_advice_and_thresholds():
    assert insights.advice(22, 45) == ["Климат в норме"]
    assert any("осушитель" in tip for tip in insights.advice(22, 75))
    assert insights.threshold_state(27, 18, 26) == "above"
    assert insights.threshold_state(17, 18, 26) == "below"
    assert insights.threshold_state(20, 18, 26) == "ok"
    assert insights.threshold_state(None, 18, 26) == "unknown"


def test_stats_and_age():
    assert insights.stats([]) is None
    assert insights.stats([20, 22, 24]) == {"min": 20, "max": 24, "avg": 22, "count": 3}
    now = datetime(2026, 11, 12, 10, 0, 0, tzinfo=timezone.utc)
    assert insights.age_seconds("2026-11-12T09:58:00+00:00", now) == 120
    assert insights.age_seconds("2026-11-12T09:58:00Z", now) == 120
    assert insights.age_seconds("мусор", now) is None


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items()) if name.startswith("test_")]
    for fn in tests:
        fn()
        print(f"OK  {fn.__name__}")
    print(f"Все {len(tests)} тестов пройдены")
