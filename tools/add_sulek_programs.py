"""Append four Sam Sulek programs to app/src/main/assets/blocks.json.

Exercise names are normalised toward what ExerciseLibrary.lookup() recognises so the in-app
analysis / swaps / achievements classify them correctly. Set counts for very high-volume days are
capped to a sane number with the true prescription preserved in notes; special schemes (drop sets,
partials, to failure, per-arm, supersets) live in notes. %1RM is intentionally unset.
"""
import json
from pathlib import Path

ASSET = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "blocks.json"


def ex(name, sets, reps, notes=""):
    return {"name": name, "sets": sets, "target_reps": reps, "percent_1rm": None, "notes": notes}


def day(name, exercises):
    return {"name": name, "exercises": exercises}


P1 = {
    "name": "Offseason Staple (Sam Sulek)",
    "author": "Sam Sulek",
    "type": "",
    "split": "4-Day Bro Split",
    "style": "Hypertrophy",
    "specialisation": "Offseason",
    "description": (
        "Sam Sulek's classic ongoing 4-day rotating bro split (documented by Garett Reid, CSCS - "
        "Set For Set). No planned rest days, ~45-75 min per session, 8-12 working sets per muscle "
        "trained to failure with partials. Daily AM cardio: 30 min LISS recumbent bike (~300 kcal) "
        "3-4 hrs before lifting."
    ),
    "days": [
        day("Chest & Side Delts", [
            ex("Incline Dumbbell Press", 3, "8-12", "Upper-chest emphasis. To failure with partials."),
            ex("Machine Neutral-Grip Chest Press", 2, "8-12", "To failure."),
            ex("Pec Deck Fly", 2, "8-12"),
            ex("High Cable Chest Fly", 1, "10-15"),
            ex("Lateral Raise", 2, "12-15", "Drop set on the final set."),
        ]),
        day("Back & Rear Delts", [
            ex("Weighted Chin-Up", 2, "8-12"),
            ex("Wide-Grip Lat Pulldown", 2, "8-12"),
            ex("Seated V-Bar Cable Row", 2, "10-12"),
            ex("Single-Arm Bent-Over Cable Row", 1, "10-12", "Per arm."),
            ex("Rear Delt Fly", 1, "12-15", "Superset with Cable Pullover."),
            ex("Cable Pullover", 1, "12-15", "Superset with Rear Delt Fly."),
        ]),
        day("Arms - Biceps & Triceps", [
            ex("Tricep Pushdown", 2, "12-15"),
            ex("Cable Preacher Curl", 2, "10-12"),
            ex("Overhead Triceps Extension", 2, "10-12"),
            ex("Cable Hammer Curl", 2, "12-15"),
            ex("Cross-Cable Triceps Extension", 1, "12-15", "To failure."),
            ex("Single-Arm Preacher Curl", 1, "10-12", "Per arm, to failure."),
        ]),
        day("Legs - Hamstrings & Quads", [
            ex("Lying Leg Curl", 2, "12-15"),
            ex("Cable Romanian Deadlift", 2, "8-12"),
            ex("Hack Squat", 2, "8-12", "Or Smith-machine front squat."),
            ex("Leg Press", 1, "15-20", "High-rep burnout."),
            ex("Leg Extension", 1, "12-15", "Final set to failure."),
            ex("Calf Raise", 2, "12-15"),
        ]),
    ],
}

P2 = {
    "name": "2023-2024 Bulk (Sam Sulek)",
    "author": "Sam Sulek",
    "type": "",
    "split": "8-Day Rotating Bro Split",
    "style": "Hypertrophy",
    "specialisation": "Bulk",
    "description": (
        "Sam Sulek's 8-day rotating bulk block (documented by Murshid Akram - The Fitness Phantom), "
        "each session drawn from a specific vlog. No fixed rest days, 12-20 working sets per session, "
        "8-15 reps trained to failure - for experienced lifters. Daily AM cardio: 30 min LISS bike "
        "(~300 kcal). Beginners: rest after every 2 sessions for the first 2 months."
    ),
    "days": [
        day("Back & Rear Delts", [
            ex("Shoulder-Width Grip Lat Pulldown", 3, "12-15"),
            ex("Wide Hammer-Grip Lat Pulldown", 3, "10-12"),
            ex("Machine Low Row", 3, "12-15"),
            ex("Machine Lat Pullover", 3, "12-15"),
            ex("Seated Low Cable Row", 3, "12-15"),
            ex("Lying Face Pull", 3, "10-12"),
        ]),
        day("Biceps & Triceps", [
            ex("Cross-Cable Triceps Extension", 3, "12-15"),
            ex("Machine Dips", 3, "10-12"),
            ex("Tricep Pushdown", 3, "12-15"),
            ex("Alternating Dumbbell Curl", 3, "8-10", "Per arm."),
            ex("Seated Machine Biceps Curl", 3, "12-15"),
            ex("Barbell Curl", 3, "10-12"),
            ex("Alternating Dumbbell Curl", 3, "8-10", "Finisher, per arm, to failure."),
        ]),
        day("Hamstrings & Quads", [
            ex("Lying Leg Curl", 4, "12-15"),
            ex("Single-Leg Extension", 3, "12-15", "Unilateral, per leg."),
            ex("Leg Extension", 1, "12-15", "Bilateral, to failure."),
            ex("Heel-Elevated Back Squat", 4, "10-12"),
            ex("Single-Leg Extension", 1, "12-15", "Finisher, per leg, to failure."),
        ]),
        day("Chest & Side Delts", [
            ex("Incline Bench Press", 5, "10-12"),
            ex("Bent-Over Cable Chest Fly", 3, "10-12"),
            ex("Standing Cable Fly", 3, "10-12"),
            ex("Machine Lateral Raise", 3, "10-12"),
            ex("Dumbbell Lateral Raise", 3, "8-10"),
        ]),
        day("Back, Rear Delts & Calves", [
            ex("Seated Cable Row (Shoulder-Width Grip)", 4, "10-15"),
            ex("One-Arm Straight-Arm Lat Pulldown", 2, "10", "Per side."),
            ex("Wide-Grip Seated Cable Row", 3, "10-12"),
            ex("Single-Arm Lat Pulldown", 1, "10", "Per side."),
            ex("Shoulder-Width Cable Row", 1, "10-15", "To failure."),
            ex("Lying Face Pull", 4, "10-12"),
            ex("Seated Calf Raise", 4, "10-15"),
        ]),
        day("Triceps & Biceps", [
            ex("Cross-Body Cable Triceps Extension", 3, "12-15"),
            ex("Single-Arm Overhead Triceps Extension", 2, "10", "Per arm."),
            ex("Machine Dips", 1, "10-12", "To failure."),
            ex("Triceps Bar Pushdown", 2, "8-10"),
            ex("Alternating Dumbbell Curl", 2, "12-15"),
            ex("Barbell Curl", 2, "10-12", "To failure."),
            ex("Single-Arm Cable Curl", 2, "10-12", "Per arm."),
            ex("Alternating Dumbbell Curl", 2, "8", "Close-out, per arm."),
        ]),
        day("Hamstrings & Quads", [
            ex("Seated Leg Curl", 4, "15-20"),
            ex("Leg Extension", 2, "15-20", "Bilateral."),
            ex("Single-Leg Extension", 2, "15", "Per leg."),
            ex("Heel-Elevated Back Squat", 2, "8-10"),
            ex("Leg Extension", 1, "10-12"),
            ex("Single-Leg Extension", 1, "10-12", "Finisher, per leg."),
        ]),
        day("Chest & Side Delts", [
            ex("Incline Bench Press", 4, "10-12"),
            ex("Bent-Over Cable Chest Fly", 3, "10-12"),
            ex("Standing Cable Fly", 2, "10-12"),
            ex("Bent-Over Parallel Cable Fly", 2, "10-12"),
            ex("Machine Lateral Raise", 2, "10-12"),
            ex("Dumbbell Lateral Raise", 2, "8-10"),
        ]),
    ],
}

P3 = {
    "name": "2024-2025 Mass Phase (Sam Sulek)",
    "author": "Sam Sulek",
    "type": "",
    "split": "4-Day Bro Split",
    "style": "Hypertrophy",
    "specialisation": "Mass / High Volume",
    "description": (
        "Sam Sulek's highest-volume 4-day mass phase (documented by Trumeta) - failure and partials "
        "throughout, leg days up to 3 hours. This captures the UPPER END of his volume, not the "
        "average: working-set counts here are capped for a usable log, with the full prescription in "
        "the notes. AM cardio: 30 min LISS bike (~300 kcal), at least 3 hrs before lifting."
    ),
    "days": [
        day("Chest & Deltoids", [
            ex("Incline Barbell Bench Press", 5, "8-12", "Sam ramps 4-8 sets to failure."),
            ex("Machine Chest Press", 2, "8-12", "To failure."),
            ex("Pec Deck Fly", 2, "12-15", "Drop sets."),
            ex("Cable Fly", 2, "10-15", "To max effort."),
            ex("Lateral Raise", 5, "15-20", "Heavy partials - Sam ramps up to ~11 sets."),
        ]),
        day("Back", [
            ex("Wide-Grip Barbell Row", 3, "8-12", "Partials."),
            ex("Lat Pulldown", 3, "8-10", "To failure."),
            ex("Single-Arm Machine Row", 3, "8-10", "Partials, per arm."),
            ex("T-Bar Row", 2, "10", "To failure."),
            ex("Cable Pullover", 2, "10-12"),
        ]),
        day("Legs", [
            ex("Seated Leg Curl", 5, "12-15", "1 warm-up + up to 8 working sets."),
            ex("Lying Leg Curl", 5, "8-12", "Partials - up to 8 sets."),
            ex("Cable Romanian Deadlift", 3, "8-12"),
            ex("Heel-Elevated Back Squat", 4, "8-12", "To failure."),
            ex("Leg Extension", 5, "8-15", "To failure - Sam ramps to ~10 sets."),
        ]),
        day("Arms", [
            ex("Straight-Bar Triceps Pushdown", 2, "12-15"),
            ex("Overhead Triceps Extension", 3, "12-15", "Partials."),
            ex("Cable Triceps Extension", 3, "12-15"),
            ex("Standing Dumbbell Curl", 3, "8-12"),
            ex("Seated Dumbbell Curl", 3, "8-12"),
            ex("EZ-Bar Curl", 3, "8-12"),
            ex("Preacher Curl", 3, "8-12", "To failure."),
        ]),
    ],
}

P4 = {
    "name": "Beginner-Adapted Entry Point (Sam Sulek Style)",
    "author": "Murshid Akram (Sam Sulek style)",
    "type": "",
    "split": "5-Day Split",
    "style": "Hypertrophy",
    "specialisation": "Beginner",
    "description": (
        "NOT Sam Sulek's own program - a beginner/intermediate adaptation of his principles by "
        "Murshid Akram (The Fitness Phantom) into a Mon-Fri split with the weekend off. Optional "
        "cardio: 20 min steady-state, 3x per week."
    ),
    "days": [
        day("Mon - Chest & Triceps", [
            ex("Barbell Bench Press", 4, "6-8"),
            ex("Incline Dumbbell Press", 4, "8-10"),
            ex("Machine Chest Press", 3, "10-12"),
            ex("Cable Fly", 3, "12-15"),
            ex("Rope Triceps Pushdown", 3, "10-12"),
            ex("Overhead Triceps Extension", 3, "10-12"),
        ]),
        day("Tue - Back & Biceps", [
            ex("Lat Pulldown", 4, "8-10"),
            ex("Barbell Row", 4, "6-8"),
            ex("Seated Cable Row", 3, "10-12"),
            ex("Single-Arm Dumbbell Row", 3, "10-12", "Per arm."),
            ex("Barbell Curl", 3, "10-12"),
            ex("Hammer Curl", 3, "12-15"),
        ]),
        day("Wed - Legs", [
            ex("Barbell Squat", 4, "6-8"),
            ex("Leg Press", 4, "10"),
            ex("Leg Extension", 3, "12-15"),
            ex("Lying Leg Curl", 3, "12-15"),
            ex("Romanian Deadlift", 3, "8-10"),
            ex("Standing Calf Raise", 4, "15-20"),
        ]),
        day("Thu - Shoulders", [
            ex("Overhead Press", 4, "6-8"),
            ex("Dumbbell Lateral Raise", 4, "12-15"),
            ex("Rear Delt Fly", 3, "12-15"),
            ex("Arnold Press", 3, "10-12"),
            ex("Upright Row", 3, "10-12"),
        ]),
        day("Fri - Arms & Abs", [
            ex("Close-Grip Bench Press", 3, "6-8"),
            ex("EZ-Bar Curl", 3, "10-12"),
            ex("Incline Dumbbell Curl", 3, "10-12"),
            ex("Skull Crushers", 3, "10-12"),
            ex("Cable Curl", 3, "12-15"),
            ex("Hanging Leg Raise", 3, "12-15"),
            ex("Cable Crunch", 3, "15-20"),
        ]),
    ],
}

NEW = [P1, P2, P3, P4]

data = json.loads(ASSET.read_text(encoding="utf-8"))
existing_names = {p["name"] for p in data["programs"]}
added = [p for p in NEW if p["name"] not in existing_names]
data["programs"].extend(added)
data["program_count"] = len(data["programs"])
ASSET.write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"Added {len(added)} programs. Catalogue now has {data['program_count']} programs.")
for p in added:
    print(f"  - {p['name']} ({len(p['days'])} days)")
