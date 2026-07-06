"""Rewrite Aaron's 5 Day Oly Split so prescriptions use RPE in the notes, not absolute weights.

Olympic lifts are kept technical/submaximal (RPE 7-8, no grinding); strength accessories sit at
RPE 8, true-failure accessories at RPE 8-9. The kg ramps are replaced with "build to a top set at
RPE x" guidance. Matched by exercise name (all names in this program are unique).
"""
import json
from pathlib import Path

ASSET = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "blocks.json"

NOTES = {
    "Snatch Push Press + Overhead Squat + Snatch Balance":
        "Warm up, then build to a heavy but clean top complex at RPE 7-8 (technical - leave 2-3 in reserve).",
    "Power Snatch + Hang Snatch":
        "Build to a crisp working single at ~RPE 7, then hold that load for the remaining singles (RPE 7-8). Speed over grind.",
    "Snatch High Pull":
        "5x3 at RPE 7 - fast and controlled, tall finish.",
    "Pause Front Squat":
        "Warm up, then 3 working triples at RPE 8 with a 2s pause in the hole.",
    "Snatch":
        "Build to a top single at RPE 8 - technical, bar speed must stay high.",
    "Clean & Jerk":
        "Build to a top single at RPE 8 - stop if speed drops off.",
    "Snatch Deadlift":
        "Ramp to a heavy single at RPE 8 - strong position, no grinding.",
    "Front Squat":
        "Build to a top single at RPE 8.",
    "GHD Sit-Up":
        "4x10, controlled tempo.",
    "Thruster":
        "Build to working doubles at RPE 7-8 - explosive drive out of the squat.",
    "Power Clean + Hang Clean + Jerk":
        "Build to a working single at ~RPE 7, then hold that load for the remaining singles (RPE 7-8).",
    "Clean Panda Pull":
        "5x3, top sets at RPE 7 - aggressive extension.",
    "Barbell Back Squat":
        "Warm up, then 3 sets of 5 at RPE 8.",
    "Nordic Hamstring Curl":
        "4 sets (5, 8, 10, 10 reps), each taken to RPE 8-9 - slow the eccentric.",
    "Bulgarian Split Squat":
        "3x10, building to a top set at RPE 8.",
    "Weighted Back Extension":
        "4x10 at RPE 8.",
    "Weighted Pull-Up":
        "Build to a top set of 3, then keep sets of 3 at RPE 8.",
    "QL Raise":
        "5x5, controlled, RPE 7.",
    "Seated Military Press":
        "Build to a top set of 5 at RPE 8, then back-off sets at that load.",
}

data = json.loads(ASSET.read_text(encoding="utf-8"))
prog = next(p for p in data["programs"] if p["name"] == "Aaron's 5 Day Oly Split")

prog["description"] = (
    "Aaron's 5-day Olympic weightlifting split. Three technical platform days - snatch development, "
    "competition lifts, and a clean & jerk + squat-strength day - plus two posterior-chain accessory "
    "days. Loads are prescribed by RPE rather than fixed weights: warm up, then build to the listed "
    "top set at the target RPE. Technical lifts stay submaximal (RPE 7-8) so bar speed and positions "
    "hold up; accessories sit at RPE 8 (true-failure work RPE 8-9)."
)

missing = []
for day in prog["days"]:
    for e in day["exercises"]:
        note = NOTES.get(e["name"])
        if note is None:
            missing.append(e["name"])
        else:
            e["notes"] = note
        e["percent_1rm"] = None

if missing:
    raise SystemExit(f"Unmapped exercises: {missing}")

ASSET.write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
print("Aaron's program updated to RPE-based notes.")
