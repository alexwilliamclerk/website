import atexit
import datetime as dt
import json
import os
import re
import sqlite3
from pathlib import Path
from zoneinfo import ZoneInfo

import requests
from apscheduler.schedulers.background import BackgroundScheduler
from bs4 import BeautifulSoup
from flask import Flask, jsonify, render_template

BASE_DIR = Path(__file__).parent
DB_PATH = BASE_DIR / "weather.db"
CITY_FILE = BASE_DIR / "data" / "guangdong_cities.json"
SCHEDULER_LOCK_PATH = BASE_DIR / ".weather_scheduler.lock"
LONDON_TZ = ZoneInfo("Europe/London")
GUANGDONG_TZ = ZoneInfo("Asia/Shanghai")
HTTP_HEADERS = {"User-Agent": "Mozilla/5.0"}
METRIC_COLUMNS = {
    "aqi": "TEXT",
    "humidity": "TEXT",
    "wind_speed": "TEXT",
    "uv_index": "TEXT",
    "visibility": "TEXT",
    "pressure": "TEXT",
}

app = Flask(__name__)
_scheduler: BackgroundScheduler | None = None
_scheduler_lock_file = None


def init_db() -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS weather_daily (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                city TEXT NOT NULL,
                date TEXT NOT NULL,
                high_temp INTEGER NOT NULL,
                low_temp INTEGER NOT NULL,
                aqi TEXT,
                humidity TEXT,
                wind_speed TEXT,
                uv_index TEXT,
                visibility TEXT,
                pressure TEXT,
                source TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(city, date)
            )
            """
        )
        existing_columns = {
            row[1]
            for row in conn.execute("PRAGMA table_info(weather_daily)").fetchall()
        }
        for column, column_type in METRIC_COLUMNS.items():
            if column not in existing_columns:
                conn.execute(f"ALTER TABLE weather_daily ADD COLUMN {column} {column_type}")
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_weather_city_date ON weather_daily(city, date)"
        )


def load_cities() -> list[dict]:
    with CITY_FILE.open("r", encoding="utf-8") as f:
        return json.load(f)


def clean_metric(value) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def parse_temperatures(text: str) -> tuple[int, int]:
    pair = re.search(r"(-?\d{1,2})\D*°\D*/\D*(-?\d{1,2})\D*°", text)
    if not pair:
        pair = re.search(r"(-?\d{1,2})\D*℃\D*/\D*(-?\d{1,2})\D*℃", text)
    if pair:
        first = int(pair.group(1))
        second = int(pair.group(2))
        return max(first, second), min(first, second)

    highs = re.findall(r"最高\s*(-?\d{1,2})\D*℃", text)
    lows = re.findall(r"最低\s*(-?\d{1,2})\D*℃", text)
    if highs and lows:
        return int(highs[0]), int(lows[0])

    raise ValueError("Unable to parse temperatures")


def parse_life_index(lines: list[str], label: str) -> str | None:
    for i, line in enumerate(lines):
        if line == label and i > 0:
            return clean_metric(lines[i - 1])
    return None


def parse_moji_weather(city_slug: str, session: requests.Session) -> dict:
    url = f"https://tianqi.moji.com/weather/china/guangdong/{city_slug}"
    resp = session.get(url, timeout=15, headers=HTTP_HEADERS)
    resp.raise_for_status()

    soup = BeautifulSoup(resp.text, "html.parser")
    text = soup.get_text(" ", strip=True)
    line_text = soup.get_text("\n", strip=True)
    lines = [line.strip() for line in line_text.splitlines() if line.strip()]
    high, low = parse_temperatures(text)

    weather = {
        "high_temp": high,
        "low_temp": low,
        "aqi": None,
        "humidity": None,
        "wind_speed": None,
        "uv_index": parse_life_index(lines, "紫外线"),
        "visibility": None,
        "pressure": None,
    }

    aqi_match = re.search(
        r"(?:^|\s)(\d{1,3})\s*(优|良|轻度污染|中度污染|重度污染|严重污染)(?:\s|$)",
        text,
    )
    if aqi_match:
        weather["aqi"] = f"{aqi_match.group(1)} {aqi_match.group(2)}"

    humidity_match = re.search(r"湿度\s*(\d{1,3})\s*%", text)
    if humidity_match:
        weather["humidity"] = f"{humidity_match.group(1)}%"

    wind_match = re.search(r"([东南西北中微无持续]+风\s*\d+\s*级)", text)
    if wind_match:
        weather["wind_speed"] = re.sub(r"\s+", "", wind_match.group(1))

    return weather


def format_float(value, digits: int = 1) -> str | None:
    if value is None:
        return None
    return f"{float(value):.{digits}f}"


def fetch_open_meteo_metrics(city: dict, session: requests.Session) -> dict:
    latitude = city.get("latitude")
    longitude = city.get("longitude")
    if latitude is None or longitude is None:
        return {}

    weather_resp = session.get(
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": latitude,
            "longitude": longitude,
            "current": ",".join(
                [
                    "relative_humidity_2m",
                    "wind_speed_10m",
                    "pressure_msl",
                    "visibility",
                    "uv_index",
                ]
            ),
            "daily": "temperature_2m_max,temperature_2m_min",
            "forecast_days": 1,
            "timezone": "Asia/Shanghai",
        },
        timeout=15,
    )
    weather_resp.raise_for_status()
    weather_payload = weather_resp.json()
    current = weather_payload.get("current", {})
    daily = weather_payload.get("daily", {})

    metrics = {
        "high_temp": None,
        "low_temp": None,
        "humidity": None,
        "wind_speed": None,
        "uv_index": None,
        "visibility": None,
        "pressure": None,
        "aqi": None,
    }

    if daily.get("temperature_2m_max") and daily.get("temperature_2m_min"):
        metrics["high_temp"] = round(float(daily["temperature_2m_max"][0]))
        metrics["low_temp"] = round(float(daily["temperature_2m_min"][0]))

    if current.get("relative_humidity_2m") is not None:
        metrics["humidity"] = f"{round(float(current['relative_humidity_2m']))}%"
    if current.get("wind_speed_10m") is not None:
        metrics["wind_speed"] = f"{format_float(current['wind_speed_10m'])} km/h"
    if current.get("uv_index") is not None:
        metrics["uv_index"] = format_float(current["uv_index"])
    if current.get("visibility") is not None:
        metrics["visibility"] = f"{float(current['visibility']) / 1000:.1f} km"
    if current.get("pressure_msl") is not None:
        metrics["pressure"] = f"{round(float(current['pressure_msl']))} hPa"

    air_resp = session.get(
        "https://air-quality-api.open-meteo.com/v1/air-quality",
        params={
            "latitude": latitude,
            "longitude": longitude,
            "current": "european_aqi",
            "timezone": "Asia/Shanghai",
        },
        timeout=15,
    )
    air_resp.raise_for_status()
    air_current = air_resp.json().get("current", {})
    if air_current.get("european_aqi") is not None:
        metrics["aqi"] = f"EAQI {round(float(air_current['european_aqi']))}"

    return {key: value for key, value in metrics.items() if value is not None}


def combine_weather_metrics(primary: dict, secondary: dict) -> dict:
    weather = {**primary}
    for key, value in secondary.items():
        if key in {"wind_speed", "uv_index"} and weather.get(key) and value:
            weather[key] = f"{weather[key]} · {value}"
        elif not weather.get(key):
            weather[key] = value
    return weather


def save_weather(city: str, date: str, weather: dict) -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            INSERT INTO weather_daily(
                city, date, high_temp, low_temp,
                aqi, humidity, wind_speed, uv_index, visibility, pressure,
                source, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'moji+open-meteo', ?)
            ON CONFLICT(city, date) DO UPDATE SET
                high_temp = excluded.high_temp,
                low_temp = excluded.low_temp,
                aqi = excluded.aqi,
                humidity = excluded.humidity,
                wind_speed = excluded.wind_speed,
                uv_index = excluded.uv_index,
                visibility = excluded.visibility,
                pressure = excluded.pressure,
                source = excluded.source,
                created_at = excluded.created_at
            """,
            (
                city,
                date,
                weather["high_temp"],
                weather["low_temp"],
                clean_metric(weather.get("aqi")),
                clean_metric(weather.get("humidity")),
                clean_metric(weather.get("wind_speed")),
                clean_metric(weather.get("uv_index")),
                clean_metric(weather.get("visibility")),
                clean_metric(weather.get("pressure")),
                dt.datetime.now(dt.UTC).isoformat(),
            ),
        )


def prune_old_data() -> None:
    cutoff = (dt.datetime.now(GUANGDONG_TZ).date() - dt.timedelta(days=14)).isoformat()
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute("DELETE FROM weather_daily WHERE date < ?", (cutoff,))


def crawl_guangdong_weather() -> dict:
    init_db()
    today = dt.datetime.now(GUANGDONG_TZ).date().isoformat()
    result = {"ok": 0, "failed": [], "warnings": []}

    with requests.Session() as session:
        for city in load_cities():
            city_name = city["name"]
            slug = city["slug"]
            weather = {}
            moji_error = None
            open_meteo_error = None

            try:
                weather = parse_moji_weather(slug, session)
            except Exception as e:
                moji_error = str(e)

            try:
                open_meteo_metrics = fetch_open_meteo_metrics(city, session)
                weather = combine_weather_metrics(weather, open_meteo_metrics)
            except Exception as e:
                open_meteo_error = str(e)

            if open_meteo_error:
                result["warnings"].append(
                    {"city": city_name, "source": "open-meteo", "reason": open_meteo_error}
                )

            if moji_error and not weather:
                result["failed"].append(
                    {"city": city_name, "source": "moji", "reason": moji_error}
                )
                continue

            if weather.get("high_temp") is None or weather.get("low_temp") is None:
                result["failed"].append(
                    {"city": city_name, "reason": moji_error or "missing temperatures"}
                )
                continue

            try:
                save_weather(city_name, today, weather)
                result["ok"] += 1
            except Exception as e:
                result["failed"].append({"city": city_name, "reason": str(e)})

    prune_old_data()
    return result


def row_to_weather(row: sqlite3.Row) -> dict:
    record = dict(row)
    for column in METRIC_COLUMNS:
        record.setdefault(column, None)
    return record


def try_acquire_scheduler_lock() -> bool:
    global _scheduler_lock_file

    try:
        import fcntl
    except ImportError:
        return True

    _scheduler_lock_file = SCHEDULER_LOCK_PATH.open("w", encoding="utf-8")
    try:
        fcntl.flock(_scheduler_lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        _scheduler_lock_file.close()
        _scheduler_lock_file = None
        return False

    _scheduler_lock_file.write(str(os.getpid()))
    _scheduler_lock_file.flush()
    return True


def setup_scheduler() -> BackgroundScheduler:
    scheduler = BackgroundScheduler(timezone=LONDON_TZ)
    scheduler.add_job(
        crawl_guangdong_weather,
        "cron",
        hour=0,
        minute=0,
        id="daily-weather-crawl-london",
        replace_existing=True,
        max_instances=1,
        coalesce=True,
        misfire_grace_time=3600,
    )
    scheduler.start()
    return scheduler


def start_scheduler_once() -> BackgroundScheduler | None:
    global _scheduler

    if os.environ.get("WEATHER_DISABLE_SCHEDULER") == "1":
        return None
    if _scheduler and _scheduler.running:
        return _scheduler
    if not try_acquire_scheduler_lock():
        return None

    _scheduler = setup_scheduler()

    def shutdown_scheduler() -> None:
        if _scheduler and _scheduler.running:
            _scheduler.shutdown(wait=False)

    atexit.register(shutdown_scheduler)
    return _scheduler


init_db()
start_scheduler_once()


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/api/weather/latest")
def latest_weather():
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT
                city, date, high_temp, low_temp,
                aqi, humidity, wind_speed, uv_index, visibility, pressure
            FROM weather_daily
            WHERE date = (SELECT MAX(date) FROM weather_daily)
            ORDER BY city
            """
        ).fetchall()
    return jsonify([row_to_weather(r) for r in rows])


@app.route("/api/weather/<city>")
def city_weather(city: str):
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT
                city, date, high_temp, low_temp,
                aqi, humidity, wind_speed, uv_index, visibility, pressure
            FROM weather_daily
            WHERE city = ?
            ORDER BY date DESC
            LIMIT 14
            """,
            (city,),
        ).fetchall()
    return jsonify([row_to_weather(r) for r in rows])


@app.route("/api/crawl", methods=["POST"])
def trigger_crawl():
    return jsonify(crawl_guangdong_weather())


if __name__ == "__main__":
    app.run(debug=True)
