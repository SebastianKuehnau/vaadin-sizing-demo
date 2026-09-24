#!/usr/bin/env python3
"""Expands the test data of the recorded k6 scripts, so that the virtual users create different persons.

loadtest:record writes a single data row (the values typed in during the recording) into
<scenario>-data.csv, and the k6 script picks a random row per iteration. This keeps the recorded row
and adds variants of it with the same format: every date is replaced by a random date in the past,
every run of digits by random digits of the same length (names, email, phone). Running it again
regenerates the same rows.

    loadtest/expand-test-data.py [directory] [rows]
"""
import csv
import glob
import random
import re
import sys
from datetime import date, timedelta

directory = sys.argv[1] if len(sys.argv) > 1 else "src/test/k6/recordings"
rows = int(sys.argv[2]) if len(sys.argv) > 2 else 500
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def variant(value, rnd):
    if DATE.match(value):
        return (date(1950, 1, 1) + timedelta(days=rnd.randrange(50 * 365))).isoformat()
    return re.sub(r"\d+", lambda m: "".join(rnd.choice("0123456789") for _ in m.group()), value)


for path in glob.glob(f"{directory}/*-data.csv"):
    with open(path, newline="") as f:
        header, recorded, *_ = list(csv.reader(f)) + [None]
    if recorded is None:
        continue
    rnd = random.Random(42)
    with open(path, "w", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(header)
        writer.writerow(recorded)
        for _ in range(rows - 1):
            writer.writerow([variant(v, rnd) for v in recorded])
    print(f"{path}: {rows} rows")
