import datetime as dt
import json
import re
import sqlite3
from pathlib import Path

import requests
from apscheduler.schedulers.background import BackgroundScheduler
from bs4 import BeautifulSoup
from flask import Flask, jsonify, render_template

BASE_DIR = Path(__file__).parent
DB_PATH = BASE_DIR / "weather.db"
CITY_FILE = BASE_DIR / "data" / "guangdong_cities.json"

app = Flask(__name__)


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
                source TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(city, date)
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_weather_city_date ON weather_daily(city, date)"
        )


def load_cities() -> list[dict]:
    with CITY_FILE.open("r", encoding="utf-8") as f:
        return json.load(f)


def parse_high_low_from_moji(city_slug: str) -> tuple[int, int]:
    url = f"https://tianqi.moji.com/weather/china/guangdong/{city_slug}"
    resp = requests.get(url, timeout=15, headers={"User-Agent": "Mozilla/5.0"})
    resp.raise_for_status()

    soup = BeautifulSoup(resp.text, "lxml")
    text = soup.get_text(" ", strip=True)

    # Try explicit markers first.
    highs = re.findall(r"最高\s*(-?\d{1,2})\D*℃", text)
    lows = re.findall(r"最低\s*(-?\d{1,2})\D*℃", text)
    if highs and lows:
        return int(highs[0]), int(lows[0])

    # Fallback: pick first high/low pair from sequences like 31°/24°.
    pair = re.search(r"(-?\d{1,2})\D*°\D*/\D*(-?\d{1,2})\D*°", text)
    if pair:
        high = int(pair.group(1))
        low = int(pair.group(2))
        if high < low:
            high, low = low, high
        return high, low

    raise ValueError(f"Unable to parse temperatures for {city_slug} from Moji page")


def save_weather(city: str, date: str, high: int, low: int) -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            INSERT INTO weather_daily(city, date, high_temp, low_temp, source, created_at)
            VALUES (?, ?, ?, ?, 'moji', ?)
            ON CONFLICT(city, date) DO UPDATE SET
                high_temp = excluded.high_temp,
                low_temp = excluded.low_temp,
                source = excluded.source,
                created_at = excluded.created_at
            """,
            (city, date, high, low, dt.datetime.utcnow().isoformat()),
        )


def prune_old_data() -> None:
    cutoff = (dt.date.today() - dt.timedelta(days=14)).isoformat()
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute("DELETE FROM weather_daily WHERE date < ?", (cutoff,))


def crawl_guangdong_weather() -> dict:
    today = dt.date.today().isoformat()
    result = {"ok": 0, "failed": []}

    for c in load_cities():
        city_name = c["name"]
        slug = c["slug"]
        try:
            high, low = parse_high_low_from_moji(slug)
            save_weather(city_name, today, high, low)
            result["ok"] += 1
        except Exception as e:
            result["failed"].append({"city": city_name, "reason": str(e)})

    prune_old_data()
    return result


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/api/weather/latest")
def latest_weather():
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT city, date, high_temp, low_temp
            FROM weather_daily
            WHERE date = (SELECT MAX(date) FROM weather_daily)
            ORDER BY city
            """
        ).fetchall()
    return jsonify([dict(r) for r in rows])


@app.route("/api/weather/<city>")
def city_weather(city: str):
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT city, date, high_temp, low_temp
            FROM weather_daily
            WHERE city = ?
            ORDER BY date DESC
            LIMIT 14
            """,
            (city,),
        ).fetchall()
    return jsonify([dict(r) for r in rows])


@app.route("/api/crawl", methods=["POST"])
def trigger_crawl():
    return jsonify(crawl_guangdong_weather())


def setup_scheduler() -> BackgroundScheduler:
    scheduler = BackgroundScheduler(timezone="Asia/Shanghai")
    scheduler.add_job(crawl_guangdong_weather, "cron", hour=7, minute=0)
    scheduler.start()
    return scheduler


if __name__ == "__main__":
    init_db()
    setup_scheduler()
    app.run(debug=True)
