#!/usr/bin/env python3
"""Convert the jaredsburrows license plugin's JSON report into legal/open-source-licenses.md.

Run after `./gradlew :app:licenseOfflineDebugReport`. Keeps the bundled/offline OSS-licence doc in
sync with the resolved dependency graph. Deterministic (sorted) so re-runs produce stable diffs.
"""
import json
import os
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT = os.path.join(ROOT, "app", "build", "reports", "licenses", "licenseOfflineDebugReport.json")
OUT = os.path.join(ROOT, "legal", "open-source-licenses.md")


def main():
    with open(REPORT, encoding="utf-8") as f:
        entries = json.load(f)

    seen = {}
    for e in entries:
        name = (e.get("project") or e.get("name") or "").strip()
        group = (e.get("dependency") or "").strip()
        licenses = e.get("licenses") or []
        lic = ", ".join(sorted({(l.get("license") or "").strip() for l in licenses if l.get("license")})) or "See project"
        key = group or name
        if key and key not in seen:
            seen[key] = (name or key, group, lic)

    lines = [
        "# Open-Source Licenses",
        "",
        f"**Last updated {date(2026, 7, 13).strftime('%d %B %Y')}**",
        "",
        "Wild Odds Gym Tracker is open source (GPLv3) and is built on the open-source components",
        "below. This list is generated from the app's resolved dependencies. Each component remains",
        "under its own license.",
        "",
    ]
    for key in sorted(seen):
        name, group, lic = seen[key]
        label = group or name
        lines.append(f"- **{label}** — {lic}")
    lines.append("")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print(f"Wrote {OUT} with {len(seen)} components")


if __name__ == "__main__":
    main()
