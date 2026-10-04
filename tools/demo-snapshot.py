#!/usr/bin/env python3
"""Writes the demo's saved copy of the public data (Economía and Festivos) from a scratch instance's database.

Usage: tools/demo-snapshot.py target/snapshot-data/finanzas.db
The scratch instance must have downloaded the data first; docs/demo.md has the steps. Only public tables are
read (economy_*, holiday*); the output is src/main/resources/demo/public-data.json.gz, which DemoDataRunner loads.
"""
import datetime
import gzip
import json
import sqlite3
import sys
from zoneinfo import ZoneInfo

SPAIN = ZoneInfo("Europe/Madrid")
OUT = "src/main/resources/demo/public-data.json.gz"


def instant(ms):
    # SQLite has the instants as epoch milliseconds
    return None if ms is None else datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def day(ms):
    # ...and dates as the epoch milliseconds of their midnight in Spain
    return None if ms is None else datetime.datetime.fromtimestamp(ms / 1000, SPAIN).date().isoformat()


db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
rows = lambda sql: db.execute(sql).fetchall()
data = {
    "economySources": [{"key": k, "lastAttemptAt": instant(a), "lastSuccessAt": instant(s), "lastError": e}
                       for k, a, s, e in rows("select source_key, last_attempt_at, last_success_at, last_error from economy_sources order by 1")],
    "economyPoints": [{"indicator": i, "region": r, "period": p, "value": v, "arrivedAt": instant(a)}
                      for i, r, p, v, a in rows("select indicator, region, period, value, arrived_at from economy_points order by indicator, region, period")],
    "holidays": [{"day": day(d), "name": n, "kind": k, "source": s, "region": r, "townCode": t}
                 for d, n, k, s, r, t in rows("select day, name, kind, source, region, town_code from holidays where source != 'manual' order by day, region")],
    "holidayPlaces": [{"role": r, "region": g, "townCode": t, "townName": n}
                      for r, g, t, n in rows("select role, region, town_code, town_name from holiday_places order by 1")],
    "holidayYears": [{"year": y, "boeId": b, "scannedUntil": day(s)}
                     for y, b, s in rows("select calendar_year, boe_id, scanned_until from holiday_years order by 1")],
}
with gzip.open(OUT, "wt", encoding="utf-8") as out:
    json.dump(data, out, ensure_ascii=False, separators=(",", ":"))
print(OUT, {k: len(v) for k, v in data.items()})
