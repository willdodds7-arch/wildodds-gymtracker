#!/usr/bin/env python3
"""Generate app/src/main/assets/blocks.json with the curated programs.

3 lifting programs + 1 running program. Smart inferences are baked in here:
- set x rep notations are parsed into numeric `sets` + a `target_reps` string
- special schemes (myo-reps, AMRAP, EMOM, partials, clusters, drop sets) are preserved verbatim in `notes`
- exercise names are normalised toward the app's ExerciseLibrary where an obvious match exists
- the running program is tagged `type: "running"` so the app routes it to the running page
"""
import json, os, re

def ex(name, sets, reps, pct=None, notes=""):
    return {"name": name, "sets": sets, "target_reps": reps, "percent_1rm": pct, "notes": notes}

def oly(name, scheme):
    """Parse an Olympic-lifting ramp like '2,1,1,1,1 @ 20, 60, 80, 100, 103kg' or '5 x 3 @ 88kg'
    into one app exercise: sets count + a rep target, with the full set-by-set ramp kept in notes."""
    reps_part, _, weight_part = scheme.partition("@")
    reps_part = reps_part.strip().rstrip(",").strip()
    weights = [w.strip() for w in weight_part.lower().replace("kg", "").split(",") if w.strip()] if weight_part else []

    m = re.match(r"^(\d+)\s*[x×]\s*(.+)$", reps_part)   # "N x R"
    if m:
        sets = int(m.group(1))
        rep_list = [m.group(2).strip()] * sets
    else:                                                    # "r1,r2,r3,..."
        rep_list = [r.strip() for r in reps_part.split(",") if r.strip()]
        sets = len(rep_list)

    uniq = list(dict.fromkeys(rep_list))
    nums = [int(r) for r in rep_list if r.isdigit()]
    target = rep_list[0] if len(uniq) == 1 else (f"{min(nums)}-{max(nums)}" if nums else reps_part)

    note = ""
    if weights:
        if len(weights) == 1:
            note = f"{sets}×{rep_list[0]} @ {weights[0]} kg"
        else:
            items = []
            for i in range(sets):
                r = rep_list[i] if i < len(rep_list) else rep_list[-1]
                w = weights[i] if i < len(weights) else weights[-1]
                items.append(f"{r}@{'BW' if w in ('_', '-', '') else w + 'kg'}")
            note = "Ramp: " + ", ".join(items)
    elif len(uniq) > 1:
        note = "Per set: " + ", ".join(rep_list) + " reps"
    return ex(name, sets, target, None, note)

AUTHOR = 'Wild Odds'

# ── Program 1: Strength Formula ───────────────────────────────────────────────
strength_formula = {
    "name": "Strength Formula",
    "author": AUTHOR,
    "split": "4x Upper Lower",
    "style": "Strength",
    "specialisation": "None",
    "description": (
        "A 4-day upper/lower strength block built around the big barbell lifts. Two pressing days "
        "(bench-led, then overhead-press-led) and two pulling/lower days (squat-led, then "
        "deadlift-led). Every session opens with a heavy main lift in the 2-5 rep range and is backed "
        "off with strength-hypertrophy accessories. Run it for 4 weeks, adding a little load each week "
        "while keeping the top sets sharp and leaving a rep in reserve."
    ),
    "days": [
        {"name": "Bench / Upper Pull", "exercises": [
            ex("Bench Press", 4, "3-5", "85-90%", "Top strength sets - leave 1 rep in reserve. Rest 3 min."),
            ex("Overhead Press", 3, "6-8", None, "Strict press. Rest 2-3 min."),
            ex("Lat Pulldown", 3, "8-10", None, "Full stretch at the top."),
            ex("Cable Row", 2, "12", None, "Controlled, squeeze the back."),
        ]},
        {"name": "Squat / Lower", "exercises": [
            ex("Back Squat", 4, "3-5", "85-90%", "Top strength sets - leave 1 rep in reserve. Rest 3 min."),
            ex("Bulgarian Split Squat", 3, "5-8", None, "Per leg. Dumbbells or barbell."),
            ex("Romanian Deadlift", 3, "8", None, "Slow eccentric, feel the hamstrings."),
            ex("Glute Ham Raise", 2, "12", None, "On the GHD - lower under control."),
        ]},
        {"name": "OHP Focus / Upper", "exercises": [
            ex("Overhead Press", 5, "3-5", "85-90%", "Main lift today - heavy strict doubles/triples. Rest 3 min."),
            ex("Bench Press", 3, "8", None, "Back-off volume, controlled."),
            ex("Weighted Pull-Up", 2, "6-8", None, "Add load on a belt; full range."),
            ex("Face Pull", 2, "15", None, "Rear delts and upper back - high reps."),
        ]},
        {"name": "Deadlift Focus / Lower", "exercises": [
            ex("Conventional Deadlift", 5, "2-4", "85-92%", "Main lift - reset each rep, brace hard. Rest 3-4 min."),
            ex("Deficit Deadlift", 3, "4-6", None, "Deficit or paused deadlift - builds off the floor."),
            ex("Good Morning", 2, "10", None, "Light to moderate, long hamstrings."),
            ex("Farmer's Carry", 2, "30-40 m", None, "Heavy carry, 30-40 m per set. Grip and core."),
        ]},
    ],
}

# ── Program 2: GiraffeBrah Hypertrophy Split ─────────────────────────────────
def myo(work_reps, myo_sets):
    total = 1 + myo_sets
    return total, work_reps, f"1 all-out set of {work_reps}, then {myo_sets} myo-rep mini-sets."

giraffebrah = {
    "name": "GiraffeBrah Hypertrophy Split",
    "author": "GiraffeBrah",
    "split": "4x Hypertrophy",
    "style": "Hypertrophy",
    "specialisation": "None",
    "description": (
        "A high-intensity 4-day hypertrophy split from GiraffeBrah built on myo-rep matchsets, AMRAPs "
        "and lengthened-partial work to squeeze maximum stimulus out of every exercise. Most lifts open "
        "with one all-out set of 8-10, then milk 2-4 myo-rep mini-sets before moving on. Expect short "
        "rests, a lot of training to failure, and plenty of arm, chest and glute volume. The 'sets' "
        "shown include the myo-rep mini-sets - read each note for the exact scheme."
    ),
    "days": [
        {"name": "Arms & Legs", "exercises": [
            ex("Hammer Curl", *myo("8-10", 4)),
            ex("Preacher Curl", *myo("8-10", 4)),
            ex("Seated Leg Curl", *myo("8-10", 2)[:2], None, "Hamstring isolation: " + myo("8-10", 2)[2]),
            ex("Leg Extension", *myo("8-10", 2)[:2], None, "Quad isolation: " + myo("8-10", 2)[2]),
            ex("Belt Squat", 2, "AMRAP", None, "Two sets taken to failure."),
            ex("Hip Abduction", *myo("8-10", 2)[:2], None, "'Good girls': " + myo("8-10", 2)[2]),
        ]},
        {"name": "Chest & Back", "exercises": [
            ex("Sternal Press-Around", 2, "8-10", None, "Cable press-around biased to the lower/sternal chest."),
            ex("Pec Flye", *myo("8-10", 2)),
            ex("Machine Chest Press", *myo("8-10", 3)),
            ex("Row + Shrug", 4, "8-12", None, "Combine a row with a shrug at the top. 4 working sets."),
            ex("Back Extension", 3, "10-12", None, "Heavy - hold a plate or dumbbell."),
        ]},
        {"name": "Arms, Triceps & Legs", "exercises": [
            ex("Bodyweight Skull Crusher", 1, "100-200", None, "Bodyweight skull crushers - accumulate 100-200 reps for time."),
            ex("Katana Extension", *myo("8-10", 4)),
            ex("Lengthened Cable Curl", 5, "12", None, "Lengthened-bias cable curl: 1x12 + 4 myo-rep sets."),
            ex("Seated Leg Curl", 3, "12", None, "Hamstring movement: 1x12 + 2 myo-rep sets."),
            ex("Leg Press", 3, "5-15", None, "Triple drop: 1x15, drop and x10, drop and x5."),
            ex("Hip Adduction", 3, "20", None, "'Bad girls': 1x20 + 2 myo-rep sets."),
        ]},
        {"name": "Pull, Push & Delts", "exercises": [
            ex("Neutral-Grip Lat Pulldown", 4, "5-7", None, "Neutral-grip pulldown, heavy."),
            ex("Incline Smith Machine Chest Press", 4, "8-10", None, "Add 5-10 lengthened partials after each set."),
            ex("Incline Chest Flye Press", 4, "8-10", None, "Hybrid flye-press on an incline."),
            ex("Single-Arm Lat-Biased Row", 4, "8-10", None, "Add partials at the stretched position."),
            ex("Lateral Raise", 5, "15-20", None, "DB or cable: 1x15-20 + 4 myo-rep sets."),
            ex("Hanging Leg Raise", 4, "10-15", None, "On the dip bar."),
            ex("Dip", 4, "AMRAP", None, "Bodyweight dips to failure, 4 sets."),
        ]},
    ],
}

# ── Program 3: Coach Whito Hypertrophy Split + Power Day ──────────────────────
coach_whito = {
    "name": "Coach Whito Hypertrophy Split + Power Day",
    "author": "Coach Whito",
    "split": "5x Anterior/Posterior",
    "style": "Powerbuilding",
    "specialisation": "None",
    "description": (
        "Coach Whito's 5-day anterior/posterior hypertrophy split capped with a dedicated power day. "
        "Each session pairs explosive athletic work - jumps, plyometrics, EMOM main lifts - with "
        "high-quality bodybuilding volume: supersets, myo-style spam sets and cluster pull-ups. The "
        "power day is pure output: rebound jumps, heavy doubles, EMOM squats and all-out bike intervals. "
        "Notation like 'x2 (2-4)' means 2 sets of 2-4 reps."
    ),
    "days": [
        {"name": "Anterior 1", "exercises": [
            ex("Jump Squat", 3, "3-5", None, "Explosive jumps from a full squat."),
            ex("Overhead Press", 2, "2-4", None, "Standing, heavy doubles/triples."),
            ex("Overhead Press", 5, "2", None, "EMOM: every minute on the minute x5 (10 total reps)."),
            ex("Whito Press", 3, "8-12", None, "Coach Whito's pressing variation."),
            ex("Single-Arm Preacher Curl", 3, "8-12", None, "Single-arm dumbbell preacher curl."),
            ex("Single-Arm Hammer Curl", 3, "8-12", None, "Alternating."),
            ex("Leg Extension", 3, "12-15", None, "Mat under the seat for extra range."),
            ex("Tibialis Raise", 3, "15-20", None, "On the leg-curl machine."),
            ex("Hip March", 3, "20", None, "Standing hip-flexor marches."),
        ]},
        {"name": "Posterior 1", "exercises": [
            ex("Banded External Rotation", 2, "15", None, "Double-banded - shoulder prep."),
            ex("Conventional Deadlift", 2, "2-4", None, "Heavy doubles/triples."),
            ex("Paused Deadlift", 1, "5", None, "Back-down set with a pause off the floor."),
            ex("Rear Delt Flye", 3, "15-20", None, "Superset."),
            ex("Seated Shrug", 3, "12-15", None, "On the machine."),
            ex("Glute Ham Raise", 3, "AMRAP", None, "GHD spam - accumulate reps."),
            ex("GHD Sit-Up", 3, "AMRAP", None, "Spam reps."),
            ex("Pull-Up", 3, "5-8", None, "Cluster sets - short rests inside the set."),
            ex("Pull-Up", 1, "AMRAP", None, "One all-out set to failure."),
        ]},
        {"name": "Anterior 2", "exercises": [
            ex("Broad Jump", 3, "3", None, "Double-bounding broad jumps."),
            ex("Hanging Leg Raise", 3, "12-15", None, ""),
            ex("Hip Abduction", 3, "15-20", None, "'Good girl'."),
            ex("Ring Biceps Curl", 3, "8-12", None, ""),
            ex("Cable Biceps Curl", 3, "12-15", None, ""),
            ex("Pec Flye", 3, "12-15", None, "Superset."),
            ex("Deficit Push-Up", 3, "AMRAP", None, "Hands elevated for extra range."),
            ex("Cable Lateral Raise", 3, "15-20", None, ""),
        ]},
        {"name": "Posterior 2", "exercises": [
            ex("Plyometrics", 3, "5", None, "Plyos of your choice."),
            ex("Overhead Triceps Extension", 2, "10-12", None, ""),
            ex("Pullover", 2, "12-15", None, "Superset."),
            ex("Triceps Pushdown", 2, "12-15", None, ""),
            ex("Single-Leg Smith Hip Thrust", 2, "10-12", None, "Per leg."),
            ex("Reverse Lunge", 2, "10-12", None, "With a mat under the front foot."),
            ex("Leg Press", 2, "15", None, "Superset with calf raises."),
        ]},
        {"name": "Power", "exercises": [
            ex("Rebound Jump", 4, "3", None, "Fast ground contact, reactive."),
            ex("Back Squat", 2, "2-4", None, "Heavy doubles/triples."),
            ex("Back Squat", 5, "2", None, "EMOM: every minute on the minute x5 (10 total reps)."),
            ex("C2 Bike", 4, "20 cal", None, "20 calories all-out, no rest between efforts."),
            ex("Bodyweight Squat", 4, "20", None, "Finisher."),
        ]},
    ],
}

# ── Program: Dodds Split (named "Low Volume Strength, High Volume Hypertrophy") ─
dodds_split = {
    "name": "Low Volume Strength, High Volume Hypertrophy",
    "author": AUTHOR,
    "split": "4x Body Part Split",
    "style": "Mixed Powerbuilding",
    "specialisation": "None",
    "description": (
        "The Dodds Split - a 4-day powerbuilding week that pairs low-volume strength doubles on the main "
        "lifts (squat, overhead press, deadlift) with high-volume hypertrophy work for everything else. "
        "Four sessions: Chest & Biceps, Quads, Shoulders & Core, and Posterior. Heavy 2-rep top sets and "
        "EMOM clusters build strength, while 8-12 rep accessories and bodyweight burnout sets drive growth."
    ),
    "days": [
        {"name": "Chest & Biceps", "exercises": [
            ex("Push-Up", 1, "100", None, "Bodyweight - 100 reps."),
            ex("Pec Flye", 3, "8-12"),
            ex("Sternal Press", 3, "8-12", None, "Cable press biased to the sternal/lower chest."),
            ex("Smith Machine Chest Press", 3, "8-12"),
            ex("Single-Arm Preacher Curl", 3, "8-12"),
            ex("Alternating Hammer Curl", 3, "8-12"),
        ]},
        {"name": "Quads", "exercises": [
            ex("Back Squat", 2, "2", "88-92%", "Heavy strength doubles."),
            ex("Back Squat", 5, "10", None, "Volume back-off sets."),
            ex("C2 Bike", 5, "20", None, "20 hard cals/efforts per set."),
            ex("Bodyweight Squat", 3, "8-12"),
        ]},
        {"name": "Shoulders & Core", "exercises": [
            ex("Hip Abduction", 3, "12", None, "'Good girl'."),
            ex("Dip Bar Crunch", 3, "8-12", None, "Core on the dip/captain's chair."),
            ex("Overhead Press", 2, "2", "85-90%", "Heavy strength doubles."),
            ex("Overhead Press", 8, "10", None, "E2MOM - every 2 minutes on the minute x8 sets."),
            ex("Cable Lateral Raise", 3, "8-12"),
        ]},
        {"name": "Posterior", "exercises": [
            ex("Conventional Deadlift", 2, "2", "88-92%", "Heavy strength doubles."),
            ex("Deficit Deadlift", 5, "4", None, "Deficit pause deadlift, EMOM x5."),
            ex("STOHE", 3, "8-12"),
            ex("LPO", 2, "8-12"),
            ex("TDP", 2, "8-12"),
            ex("GHD Spam", 3, "8-12", None, "Accumulate glute-ham raise reps."),
            ex("Bodyweight Skull Crusher", 3, "8-12", None, "Triceps - bodyweight skull crushers."),
        ]},
    ],
}

# ── Aaron's 5 Day Oly Split ──────────────────────────────────────────────────
aarons_oly = {
    "name": "Aaron's 5 Day Oly Split",
    "author": "Aaron",
    "split": "5 Day Olympic Weightlifting",
    "style": "Olympic Weightlifting",
    "specialisation": "None",
    "description": (
        "Aaron's 5-day Olympic weightlifting split. Three technical platform days - snatch development, "
        "competition lifts, and a clean & jerk + squat-strength day - built around ramping singles and "
        "doubles up to a top set, plus two accessory days hammering hamstrings, posterior chain and core. "
        "The weights shown are Aaron's working numbers; read each exercise's note for the full set-by-set "
        "ramp and scale it to your own maxes."
    ),
    "days": [
        {"name": "Snatch Technical Development", "exercises": [
            oly("Snatch Push Press + Overhead Squat + Snatch Balance", "2,1,1,1,1 @ 20, 60, 80, 100, 103kg"),
            oly("Power Snatch + Hang Snatch", "2,1,1,1,1,1,1,1,1,1 @ 20, 40, 50, 60, 70, 83, 83, 83, 83, 83kg"),
            oly("Snatch High Pull", "5 x 3 @ 88kg"),
            oly("Pause Front Squat", "3,1,3,3,3 @ 60, 100, 123, 123, 123kg"),
        ]},
        {"name": "Competition Lifts + Lower Pull", "exercises": [
            oly("Snatch", "3,3,2,2,1,1,1,1 @ 20, 40, 50, 60, 70, 80, 85, 90kg"),
            oly("Clean & Jerk", "2,1,1,1 @ 60, 100, 115, 120kg"),
            oly("Snatch Deadlift", "5 x 1 @ 110, 120, 130, 140, 150kg"),
            oly("Front Squat", "3,1,1,1,1 @ 60, 100, 120, 130, 140kg"),
            oly("GHD Sit-Up", "4 x 10"),
        ]},
        {"name": "Clean & Jerk Variation + Squat Strength", "exercises": [
            oly("Thruster", "2,2,2,2,2,1,1,2,2 @ 20, 40, 60, 80, 90, 100, 100, 100, 100kg"),
            oly("Power Clean + Hang Clean + Jerk", "10 x 1 @ 20, 40, 60, 80, 100, 108, 108, 108, 108, 108kg"),
            oly("Clean Panda Pull", "5 x 3 @ 80, 90, 100, 110, 110kg"),
            oly("Barbell Back Squat", "3,2,1,5,5,5 @ 60, 100, 120, 138, 138, 138kg"),
        ]},
        {"name": "Accessory & Posterior Chain A", "exercises": [
            oly("Nordic Hamstring Curl", "5, 8, 10, 10"),
            oly("Bulgarian Split Squat", "3 x 10 @ _, 15, 20kg"),
            oly("Weighted Back Extension", "4 x 10 @ _, 15, 15, 15kg"),
            oly("Weighted Pull-Up", "10 x 3 @ _, 5, 10, 15, 20, 20, 20, 20, 20, 20kg"),
            oly("GHD Sit-Up", "4 x 10"),
            oly("QL Raise", "5 x 5"),
        ]},
        {"name": "Accessory & Posterior Chain B", "exercises": [
            oly("Nordic Hamstring Curl", "5, 8, 10, 10"),
            oly("Bulgarian Split Squat", "3 x 10 @ _, 15, 20kg"),
            oly("Weighted Back Extension", "4 x 10 @ _, 15, 15, 15kg"),
            oly("Seated Military Press", "7 x 5 @ 20, 40, 50, 50, 50, 50, 50kg"),
            oly("GHD Sit-Up", "4 x 10"),
            oly("QL Raise", "5 x 5"),
        ]},
    ],
}

# ── Program 4: Running for crayon eaters (RUNNING) ───────────────────────────
running = {
    "name": "Running for crayon eaters",
    "author": AUTHOR,
    "type": "running",
    "split": "Running",
    "style": "Running",
    "specialisation": "None",
    "description": (
        "A dead-simple 3-day running week for lifters who hate running. One easy aerobic Zone 2 run to "
        "build your base, one brutal Norwegian 4x4 interval session to raise your ceiling, and one short "
        "sprint day for top-end speed. No fluff, no junk miles - just three runs that cover the whole "
        "spectrum. Log each run on the Running page."
    ),
    "days": [
        {"name": "Zone 2 Run", "exercises": [
            ex("Zone 2 Run", 1, "30-60 min", None, "Easy, conversational pace. 30-60 minutes - do not care about distance."),
        ]},
        {"name": "Norwegian 4x4 Intervals", "exercises": [
            ex("Norwegian 4x4 Intervals", 4, "4 min", None, "4 minutes hard / 4 minutes easy, repeated 4 times. Aim for zone 4 on the work intervals."),
        ]},
        {"name": "Sprints", "exercises": [
            ex("Sprints", 8, "20-60 m", None, "5-10 x 20-60 m sprints. Full recovery between reps."),
        ]},
    ],
}

programs = [strength_formula, giraffebrah, coach_whito, dodds_split, aarons_oly, running]
out = {
    "library_version": "1.0",
    "author": AUTHOR,
    "program_count": len(programs),
    "programs": programs,
}

dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "blocks.json")
dest = os.path.abspath(dest)
with open(dest, "w", encoding="utf-8") as f:
    json.dump(out, f, indent=1, ensure_ascii=True)
print(f"Wrote {len(programs)} programs to {dest}")
for p in programs:
    tag = " [RUNNING]" if p.get("type") == "running" else ""
    print(f"  - {p['name']} ({len(p['days'])} days){tag}")
