#!/usr/bin/env python3
"""Append the three requested programs to app/src/main/assets/blocks.json.

Idempotent: removes any previously-added copies (by name) before appending.
"""
import json, copy, os

ASSET = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "blocks.json")


def ex(name, sets, reps, pct=None, notes=""):
    e = {"name": name, "sets": sets, "target_reps": reps, "percent_1rm": pct, "notes": notes}
    return e


def day(name, exercises):
    return {"name": name, "exercises": exercises}


# ─────────────────────────────────────────────────────────────────────────────
# 1) Muscle & Strength "Gain 35 lbs in 6 Weeks" — laid out as 6 training days
#    (each AM/PM session of the original 3-day cycle becomes its own day).
# ─────────────────────────────────────────────────────────────────────────────
ms_bench_am = [
    ex("Barbell Bench Press", 6, "7", None,
       "5-7 sets x 7. Each new cycle add 5-10 lb. Heavy but clean."),
    ex("Banded Leg Press", 7, "25", None,
       "Add 50 lb each cycle. Don't lock out - keep constant tension on the quads."),
]

ms = {
    "name": "Gain 35 lbs in 6 Weeks (M&S)",
    "author": "Muscle & Strength",
    "split": "6-Day Bench Specialisation",
    "style": "High Volume Hypertrophy",
    "specialisation": "Bench Press",
    "description": ("A metabolic-overload mass program from Muscle & Strength, run as 6 training days. "
                    "The original 3-day cycle splits each day into an AM session (heavy barbell bench + "
                    "banded leg press) and a PM session (high-rep pump work). On the PM sessions chase "
                    "muscular fatigue and the pump with pristine technique - forget the weight. Each new "
                    "cycle add 5-10 lb to bench and 50 lb to leg press."),
    "block_weeks": 6,
    "days": [
        day("Day 1 - Bench + Legs (AM)", ms_bench_am),
        day("Day 1 - Chest/Back Pump (PM)", [
            ex("Incline DB Bench Press", 5, "8-12", None, "Chase the pump."),
            ex("Incline DB Fly", 5, "20", None, "Or incline stretch push-ups off boxes."),
            ex("One Arm DB Row", 4, "15-20", None, ""),
            ex("Chin-up", 4, "Failure", None, "Bodyweight to failure."),
            ex("Lat Pulldown", 7, "10", None, "Moderate-light, contraction over load."),
        ]),
        day("Day 2 - Bench + Legs (AM)", copy.deepcopy(ms_bench_am)),
        day("Day 2 - Quads/Traps (PM)", [
            ex("Barbell Back Squat", 3, "20", None, "High-rep breathing squats."),
            ex("Leg Extension", 5, "150 (min total)", None,
               "Sets vary - accumulate at least 150 total reps, Tom Platz style."),
            ex("Barbell Shrug (straps)", 5, "25-75", None,
               "4-5 sets, very high reps. ~315 lb necessitates straps."),
        ]),
        day("Day 3 - Bench + Legs (AM)", copy.deepcopy(ms_bench_am)),
        day("Day 3 - Arms/Delts/Calves (PM)", [
            ex("Seated Alternating DB Curl", 4, "15 min EDT", None, "EDT block A1 with triceps."),
            ex("Tricep Rope Extension", 4, "15 min EDT", None, "EDT block A2."),
            ex("Band Resisted Partial Curl", 4, "15 min EDT", None, "EDT block B1."),
            ex("Tricep Band Pushdown", 4, "15 min EDT", None, "EDT block B2."),
            ex("DB Lateral Raise", 6, "150 (total)", None, "5-6 sets, 150 total reps."),
            ex("Arm Circles w/ 5 lb Plate", 3, "100", None, "Vary sets - ~100 total."),
            ex("Leg Press Calf Raise", 1, "10", None, "Superset C1 for 15 min."),
            ex("Seated Calf Raise", 1, "10", None, "Superset C2 for 15 min."),
        ]),
    ],
}


# ─────────────────────────────────────────────────────────────────────────────
# 2) Candito 6-Week (Advanced Bench Press Hybrid) — true week-by-week.
#    Main lifts (Squat / Deadlift / Bench) carry a representative %1RM per week so
#    the app prefills working weight from the user's configured 1RMs. Per-set
#    schemes + protocols are summarised in notes. Week 6 = peak/test week.
# ─────────────────────────────────────────────────────────────────────────────
OPT1 = ex("Optional Accessory 1", 3, "8-12", None, "Your choice - hypertrophy accessory.")
OPT2 = ex("Optional Accessory 2", 3, "8-12", None, "Your choice - hypertrophy accessory.")

def upper(bench, row, mil, pull, opts=True, extra_notes=""):
    items = [bench, row, mil, pull]
    if opts:
        items += [copy.deepcopy(OPT1), copy.deepcopy(OPT2)]
    return items

candito_weeks = [
    # Week 1 — Muscular Conditioning (moderate)
    {"days": [
        day("Lower A", [
            ex("Squat", 4, "6", "75%", "4x6 @ ~75%. Controlled."),
            ex("Deadlift", 2, "6", "77%", "2x6 @ ~77%."),
            copy.deepcopy(OPT1), copy.deepcopy(OPT2)]),
        day("Upper A (Bench)", upper(
            ex("Bench Press", 4, "6-10", "75%", "Ramp 10,10,8,6 to a top ~75%. Hybrid extra volume."),
            ex("Barbell Row", 4, "6-10", None, "Match bench: 10,10,8,6."),
            ex("Military Press", 4, "8-12", None, "12,12,10,8."),
            ex("Weighted Pull-up", 4, "8-12", None, "12,12,10,8."))),
        day("Upper B (Bench)", upper(
            ex("Bench Press", 4, "6-10", "75%", "Second bench day - same ramp as Upper A."),
            ex("Barbell Row", 4, "6-10", None, "10,10,8,6."),
            ex("Military Press", 4, "8-12", None, "12,12,10,8."),
            ex("Weighted Pull-up", 4, "8-12", None, "12,12,10,8."))),
        day("Lower B", [
            ex("Squat", 4, "8", "67%", "4x8 @ ~67%. Lighter volume."),
            ex("Deadlift", 2, "8", "68%", "2x8 @ ~68%.")]),
        day("Upper C (Bench MR)", upper(
            ex("Bench Press", 1, "AMRAP", "77%", "One max-rep set @ ~77%. Stop ~1 shy of failure."),
            ex("Barbell Row", 4, "6-10", None, ""),
            ex("Military Press", 4, "8-12", None, ""),
            ex("Weighted Pull-up", 4, "8-12", None, ""))),
    ]},
    # Week 2 — Conditioning / Hypertrophy (higher difficulty)
    {"days": [
        day("Lower A (Squat MR10)", [
            ex("Squat", 1, "AMRAP (~10)", "78%", "MR10 top set @ ~78%, then 5x3 @ +5 lb, 60s rest. If <8 reps, drop max 2.5%."),
            ex("Deadlift Variation", 3, "8", "70%", "Deficit / paused / RDL - 3x8 @ ~70%."),
            copy.deepcopy(OPT1), copy.deepcopy(OPT2)]),
        day("Upper A (Bench)", upper(
            ex("Bench Press", 3, "6-10", "79%", "165x10, 175x8, 185x6-8 style ramp to ~79%."),
            ex("Barbell Row", 3, "8-10", None, ""),
            ex("Military Press", 3, "6-10", None, ""),
            ex("Weighted Pull-up", 3, "6-10", None, ""))),
        day("Lower B (Squat MR10)", [
            ex("Squat", 1, "AMRAP (~10)", "80%", "MR10 @ ~80%, then back-off triples: 10/8/5 sets of 3 by reps hit (Candito protocol)."),
            ex("Deadlift Variation", 3, "8", "70%", "3x8 @ ~70%.")]),
        day("Upper B (Bench)", upper(
            ex("Bench Press", 3, "6-10", "79%", "Second bench day - same ramp ~79%."),
            ex("Barbell Row", 3, "8-10", None, ""),
            ex("Military Press", 3, "6-10", None, ""),
            ex("Weighted Pull-up", 3, "6-10", None, ""))),
        day("Upper C (Bench MR)", upper(
            ex("Bench Press", 1, "AMRAP", "75%", "175xMR style max-rep set @ ~75%."),
            ex("Barbell Row", 3, "8-10", None, ""),
            ex("Military Press", 3, "6-10", None, ""),
            ex("Weighted Pull-up", 3, "6-10", None, ""))),
    ]},
    # Week 3 — Linear Max OT (no accessories)
    {"days": [
        day("Lower A", [
            ex("Squat", 3, "4-6", "82%", "3x4-6 @ ~82%."),
            ex("Deadlift", 2, "3-6", "85%", "2x3-6 @ ~85%.")]),
        day("Upper A (Bench)", [
            ex("Bench Press", 3, "4-6", "82%", "3x4-6 @ ~82%."),
            ex("Barbell Row", 3, "6", None, ""),
            ex("Military Press", 3, "6", None, ""),
            ex("Weighted Pull-up", 3, "6", None, "")]),
        day("Lower B", [
            ex("Squat", 1, "4-6", "83%", "1 top set 4-6 @ ~83%."),
            ex("Deadlift Variation", 1, "8", "70%", "1x8 variation.")]),
        day("Upper B (Bench)", [
            ex("Bench Press", 3, "4-6", "83%", "3x4-6 @ ~83%."),
            ex("Barbell Row", 3, "6", None, ""),
            ex("Military Press", 3, "6", None, ""),
            ex("Weighted Pull-up", 3, "6", None, "")]),
    ]},
    # Week 4 — Heavy Weight Acclimation
    {"days": [
        day("Lower A", [
            ex("Squat", 3, "3", "87%", "Ascending 200x3, 205x3, 210x3 style ~85-90%."),
            ex("Deadlift Variation", 2, "6", "80%", "2x6 variation."),
            copy.deepcopy(OPT1), copy.deepcopy(OPT2)]),
        day("Upper A (Bench)", upper(
            ex("Bench Press", 3, "3", "87%", "Ascending 190x3, 200x3, 205x3 ~85-90%."),
            ex("Barbell Row", 4, "6-12", None, ""),
            ex("Military Press", 4, "8-12", None, ""),
            ex("Weighted Pull-up", 4, "8-12", None, ""))),
        day("Lower B", [
            ex("Squat", 2, "1-3", "90%", "210x3 then 215x1-2 ~88-90%."),
            ex("Deadlift", 2, "1-3", "90%", "295x3 then 305x1-2 ~89-92%."),
            copy.deepcopy(OPT1), copy.deepcopy(OPT2)]),
        day("Upper B (Bench)", upper(
            ex("Bench Press", 3, "1-4", "90%", "195x3, 205x2-4, 215x1-2 working to ~91%."),
            ex("Barbell Row", 4, "6-12", None, ""),
            ex("Military Press", 4, "8-12", None, ""),
            ex("Weighted Pull-up", 4, "8-12", None, ""))),
    ]},
    # Week 5 — High Intensity Strength
    {"days": [
        day("Lower A (Squat)", [
            ex("Squat", 1, "1-4", "92%", "Heavy top set 1-4 reps ~92%."),
            ex("Deadlift", 3, "2-4", "70%", "Speed/volume pulls 215x4, 225x4, 230x2 ~65-72%."),
            ex("Optional Lower 1", 3, "8-12", None, ""),
            ex("Optional Lower 2", 3, "8-12", None, "")]),
        day("Upper A (Bench)", [
            ex("Bench Press", 1, "1-4", "94%", "Heavy top set 1-4 reps ~94%."),
            ex("Barbell Row", 3, "6-8", None, ""),
            ex("Military Press", 3, "6-8", None, ""),
            ex("Weighted Pull-up", 3, "6-8", None, ""),
            copy.deepcopy(OPT1), copy.deepcopy(OPT2)]),
        day("Lower B (Deadlift)", [
            ex("Deadlift", 1, "1-4", "94%", "Heavy top pull 1-4 reps ~94% (e.g. 310x1-4)."),
            ex("Optional Lower 1", 3, "8-12", None, ""),
            ex("Optional Lower 2", 3, "8-12", None, "")]),
    ]},
    # Week 6 — Peak / Test (reconstructed from the standard Candito finish)
    {"days": [
        day("Deload Upper", [
            ex("Bench Press", 3, "3", "70%", "Light, fast, crisp - prime the CNS. ~70%."),
            ex("Barbell Row", 3, "8", None, ""),
            ex("Military Press", 2, "8", None, "")]),
        day("Test Lower", [
            ex("Squat", 1, "1", "100%", "Work up to a new 1RM single (or heavy single at RPE 9-10)."),
            ex("Deadlift", 1, "1", "100%", "Work up to a new 1RM single.")]),
        day("Test Upper (Bench)", [
            ex("Bench Press", 1, "1", "100%", "Work up to a new bench 1RM single."),
            ex("Military Press", 3, "5-8", None, "Optional back-off after testing."),
            ex("Weighted Pull-up", 3, "6-8", None, "")]),
    ]},
]

candito_bench = {
    "name": "Candito 6-Week (Advanced Bench Hybrid)",
    "author": "Jonnie Candito",
    "split": "Candito Upper/Lower - Bench Focus",
    "style": "Powerlifting",
    "specialisation": "Bench Press",
    "description": ("Jonnie Candito's free 6-week linear program in the Advanced Bench Press Hybrid "
                    "layout - extra bench frequency on top of the upper/lower structure. The block "
                    "waves from muscular conditioning (wk1-2) through a linear max-OT phase (wk3), "
                    "heavy acclimation (wk4), high-intensity strength (wk5) and a peak/test week (wk6). "
                    "Main lifts are prescribed as a %1RM each week, so set your Squat / Bench / Deadlift "
                    "1RMs in Profile and the app fills your working weights. Per-set schemes and the MR / "
                    "back-off protocols are in each exercise's notes."),
    "weeks": candito_weeks,
}


# 3) OHP variant: swap the pressing focus. Bench<->Overhead Press by name, so the
#    main %1RM pressing slot becomes Overhead Press (prefills from the OHP 1RM) and
#    the old Military Press accessory becomes a Bench Press accessory.
def swap_bench_ohp(program):
    out = copy.deepcopy(program)
    out["name"] = "Candito 6-Week (Advanced OHP Hybrid)"
    out["specialisation"] = "Overhead Press"
    out["split"] = "Candito Upper/Lower - OHP Focus"
    out["description"] = out["description"].replace(
        "Advanced Bench Press Hybrid", "Advanced Overhead Press Hybrid"
    ).replace("extra bench frequency", "extra overhead-press frequency").replace(
        "Squat / Bench / Deadlift", "Squat / Overhead Press / Deadlift")
    for wk in out["weeks"]:
        for d in wk["days"]:
            d["name"] = d["name"].replace("Bench", "OHP")
            for e in d["exercises"]:
                if e["name"] == "Bench Press":
                    e["name"] = "Overhead Press"
                elif e["name"] == "Military Press":
                    e["name"] = "Bench Press"
    return out

candito_ohp = swap_bench_ohp(candito_bench)


# ── Write back ────────────────────────────────────────────────────────────────
with open(ASSET, encoding="utf-8") as f:
    lib = json.load(f)

new_names = {ms["name"], candito_bench["name"], candito_ohp["name"]}
lib["programs"] = [p for p in lib["programs"] if p.get("name") not in new_names]
lib["programs"].extend([ms, candito_bench, candito_ohp])
lib["program_count"] = len(lib["programs"])

with open(ASSET, "w", encoding="utf-8") as f:
    json.dump(lib, f, ensure_ascii=False, indent=1)

print("program_count =", lib["program_count"])
for p in lib["programs"][-3:]:
    nd = len(p.get("days", [])) or (len(p["weeks"][0]["days"]) if p.get("weeks") else 0)
    nw = len(p.get("weeks", [])) or p.get("block_weeks", 4)
    print(f"  + {p['name']}  | {nw} weeks | {nd} days/wk")
