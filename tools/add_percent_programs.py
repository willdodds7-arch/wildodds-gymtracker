#!/usr/bin/env python3
"""Append the four %1RM-driven, week-varying programs to app/src/main/assets/blocks.json:
5/3/1, Candito 6-Week Bench (recovered verbatim from the pre-trim backup), Russian Squat Routine
(lift-swappable), and a Calgary Barbell 8-week adaptation. Idempotent: removes same-named programs
before appending. All four use the per-week `weeks` shape so week N prescribes its own %/reps.
"""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLOCKS = os.path.join(ROOT, "app", "src", "main", "assets", "blocks.json")
BACKUP = os.path.join(ROOT, "backups", "blocks.json.bak-20260705090804")


def ex(name, sets, reps, pct=None, rpe=None, notes=None):
    return {"name": name, "sets": sets, "target_reps": reps, "percent_1rm": pct, "rpe": rpe, "notes": notes}


# ── 5/3/1 (Jim Wendler) ────────────────────────────────────────────────────────
# Classic 4-week cycle. Percentages in the book are of the TRAINING MAX (90% of 1RM); the app
# prefills from the user's true 1RM, so each %TM is converted: shown% = book% × 0.9.
# Slash-lists prescribe a different % per set (the app prefills per set).
def five31():
    def tm(*pcts):  # book %TM list -> true-1RM slash string
        return "/".join(f"{p * 0.9:g}" for p in pcts) + "%"

    wk_main = [
        (tm(65, 75, 85), "5/5/5+", "Warm up, then 3 work sets. Last set: as many reps as possible (AMRAP). % shown are of your 1RM (= 65/75/85% of Training Max, TM = 90% of 1RM)."),
        (tm(70, 80, 90), "3/3/3+", "3 work sets; last set AMRAP. (= 70/80/90% of TM.)"),
        (tm(75, 85, 95), "5/3/1+", "The 5/3/1 week: last set AMRAP — this is the money set. (= 75/85/95% of TM.)"),
        (tm(40, 50, 60), "5/5/5", "Deload — keep it crisp and easy, no AMRAP. (= 40/50/60% of TM.)"),
    ]
    days = [("Overhead Press", "Press Day"), ("Deadlift", "Deadlift Day"),
            ("Bench Press", "Bench Day"), ("Back Squat", "Squat Day")]
    weeks = []
    for wi, (pct, reps, note) in enumerate(wk_main):
        wdays = []
        for lift, day_name in days:
            exs = [ex(lift, 3, reps, pct=pct, notes=note)]
            if wi < 3:  # BBB skipped on deload
                exs.append(ex(f"{lift} (Boring But Big)", 5, "10", pct="45%",
                              notes="Optional volume work: 5×10 at 50% of TM (= 45% of 1RM)."))
            exs.append(ex("Assistance — pull (rows or chins)", 5, "10",
                          notes="Wendler's template: 25-50 quality reps of pulling."))
            exs.append(ex("Assistance — core or single-leg", 5, "10",
                          notes="25-50 reps: ab wheel, hanging leg raises, lunges…"))
            wdays.append({"name": day_name, "exercises": exs})
        weeks.append({"name": f"Week {wi + 1}", "days": wdays})
    return {
        "name": "5/3/1 (Jim Wendler)",
        "author": "Jim Wendler",
        "split": "4-Day Main Lift Split",
        "style": "Strength",
        "specialisation": "None",
        "description": (
            "Wendler's classic strength template: one main lift per day, three waved work sets, and "
            "an all-out AMRAP top set to drive progress. Cycles 5s week → 3s week → 5/3/1 week → "
            "deload. All loads are prescribed as percentages and prefill from YOUR 1RMs (set them in "
            "Profile) — the book runs off a Training Max of 90% of your true 1RM, and the numbers "
            "here are already converted. After each cycle, add 2.5 kg to upper-body and 5 kg to "
            "lower-body maxes and run it again."),
        "weeks": weeks,
    }


# ── Russian Squat Routine (lift-swappable) ────────────────────────────────────
def russian():
    # Canonical 18 sessions: 9 volume (all 80%: 6×2,3,2,4,2,5,2,6,2) then the intensity ladder.
    plan = [(80, 6, 2), (80, 6, 3), (80, 6, 2),
            (80, 6, 4), (80, 6, 2), (80, 6, 5),
            (80, 6, 2), (80, 6, 6), (80, 6, 2),
            (85, 5, 5), (80, 6, 2), (90, 4, 4),
            (80, 6, 2), (95, 3, 3), (80, 6, 2),
            (100, 2, 2), (80, 6, 2), (105, 1, 1)]
    weeks = []
    for w in range(6):
        wdays = []
        for d in range(3):
            pct, sets, reps = plan[w * 3 + d]
            note = "Rest 3-5 min between sets."
            if pct == 100:
                note = "Current max for a double. Rest as needed — this is heavy."
            if pct == 105:
                note = "New max attempt: 105% of your old 1RM (go higher if it moves well). Update your 1RM in Profile afterwards!"
            wdays.append({"name": f"Session {w * 3 + d + 1}",
                          "exercises": [ex("Back Squat", sets, str(reps), pct=f"{pct}%", notes=note),
                                        ex("Optional accessory (light)", 2, "8-12",
                                           notes="Keep accessories minimal — the volume here is the program.")]})
        weeks.append({"name": f"Week {w + 1}", "days": wdays})
    return {
        "name": "Russian Squat Routine",
        "author": "Classic Soviet template",
        "split": "3x/week Single Lift",
        "style": "Powerlifting",
        "specialisation": "Squat (swappable)",
        "lift_swappable": True,
        "description": (
            "The classic 6-week, 18-session Soviet peaking cycle: three weeks of brutal volume at "
            "80%, then an intensity ladder — 85% 5×5, 90% 4×4, 95% 3×3, 100% 2×2 — ending in a new "
            "max attempt at 105%. Built for the squat, but when you start it you can run the whole "
            "cycle on another lift instead (bench, overhead press, or deadlift — deadlift only if "
            "you recover like a mutant). Loads prefill from your 1RM in Profile."),
        "weeks": weeks,
    }


# ── Calgary Barbell 8-week (adaptation) ───────────────────────────────────────
def calgary():
    # Faithful-shape adaptation of the 8-week spreadsheet: 4 days, squat 3x / bench 4x / dead 3x
    # weekly, %1RM + RPE-capped, volume weeks 1-4 → intensity 5-7 → taper/test week 8.
    # (The exact sheet isn't public on the program page; percentages are a representative adaptation.)
    sq  = [(4, 6, 70), (4, 5, 74), (5, 4, 77), (4, 4, 79), (3, 3, 84), (4, 2, 87), (2, 2, 90), (1, 1, 92)]
    bp  = [(4, 6, 72), (4, 5, 75), (5, 4, 78), (4, 4, 80), (4, 3, 84), (4, 2, 88), (2, 2, 91), (1, 1, 93)]
    dl  = [(4, 5, 72), (4, 4, 76), (4, 4, 78), (3, 3, 81), (3, 3, 85), (3, 2, 88), (2, 2, 91), (1, 1, 93)]
    sqv = [(3, 6, 62), (3, 6, 64), (3, 5, 66), (3, 5, 68), (3, 4, 72), (2, 3, 75), (2, 2, 78), (1, 3, 60)]
    dlv = [(3, 5, 64), (3, 5, 66), (3, 4, 68), (3, 4, 70), (2, 3, 74), (2, 3, 76), (2, 2, 79), (1, 3, 62)]
    cgb = [(4, 8, 63), (4, 7, 66), (4, 6, 68), (3, 6, 70), (3, 5, 73), (3, 4, 76), (2, 3, 79), (1, 5, 60)]
    b2  = [(3, 8, 60), (3, 8, 62), (3, 7, 64), (3, 6, 67), (3, 5, 70), (3, 4, 73), (2, 3, 76), (1, 5, 58)]

    weeks = []
    for w in range(8):
        rpe = "7" if w < 4 else ("8" if w < 7 else "6")
        taper = w == 7
        wk = {"name": f"Week {w + 1}", "days": [
            {"name": "Day 1 — Squat + Bench", "exercises": [
                ex("Back Squat", sq[w][0], str(sq[w][1]), pct=f"{sq[w][2]}%", rpe=rpe,
                   notes="Competition style. Cap the effort at the RPE — cut a set if bar speed dies." if not taper else "Openers only — crisp and fast."),
                ex("Bench Press", bp[w][0], str(bp[w][1]), pct=f"{bp[w][2]}%", rpe=rpe,
                   notes="Competition pause on every first rep."),
                ex("Barbell Row", 3, "8-10", notes="Upper-back balance work."),
            ]},
            {"name": "Day 2 — Deadlift + Bench volume", "exercises": [
                ex("Deadlift", dl[w][0], str(dl[w][1]), pct=f"{dl[w][2]}%", rpe=rpe,
                   notes="Competition stance." if not taper else "Openers only."),
                ex("Close Grip Bench Press", cgb[w][0], str(cgb[w][1]), pct=f"{cgb[w][2]}%",
                   notes="% of your bench 1RM."),
                ex("Lat Pulldown or Chins", 3, "8-12"),
            ]},
            {"name": "Day 3 — Squat variation + Bench", "exercises": [
                ex("Pause Squat", sqv[w][0], str(sqv[w][1]), pct=f"{sqv[w][2]}%",
                   notes="2-count pause in the hole. % of your squat 1RM."),
                ex("Bench Press", b2[w][0], str(b2[w][1]), pct=f"{b2[w][2]}%",
                   notes="Second bench slot — tighter technique focus, leave 2-3 reps in reserve."),
                ex("Dumbbell Shoulder Press", 3, "8-12"),
            ]},
            {"name": "Day 4 — Deadlift variation + Bench", "exercises": [
                ex("Pause Deadlift", dlv[w][0], str(dlv[w][1]), pct=f"{dlv[w][2]}%",
                   notes="Pause at the knee. % of your deadlift 1RM."),
                ex("Bench Press (feet up)", 3, "6-8", pct=f"{max(b2[w][2] - 3, 55)}%",
                   notes="Larsen-style: feet off the floor, strict."),
                ex("Face Pulls + Triceps", 3, "12-15", notes="Shoulder health superset."),
            ]},
        ]}
        weeks.append(wk)
    return {
        "name": "Calgary Barbell 8-Week (adapted)",
        "author": "Adapted from Calgary Barbell",
        "split": "4-Day Powerlifting",
        "style": "Powerlifting",
        "specialisation": "None",
        "description": (
            "An adaptation of Calgary Barbell's free 8-week block: four days a week with squat ×3, "
            "bench ×4 and deadlift ×3 weekly across competition lifts and paused variations. "
            "Percent-based with RPE caps — weeks 1-4 build volume, 5-7 push intensity, week 8 "
            "tapers to openers. Loads prefill from your 1RMs in Profile. This is an adaptation of "
            "the spreadsheet's structure, not a copy — for Calgary Barbell's exact programming get "
            "their original sheet."),
        "weeks": weeks,
    }


def main():
    data = json.load(open(BLOCKS, encoding="utf-8"))
    backup = json.load(open(BACKUP, encoding="utf-8"))
    candito = next(p for p in backup["programs"] if p["name"] == "Candito 6-Week (Advanced Bench Hybrid)")
    # Make the prompt-for-1RM + prefill story explicit in its description too.
    candito["description"] = candito.get("description", "").rstrip() + (
        " Loads are percent-based and prefill from your bench (and squat/deadlift) 1RMs — set them in Profile."
    )

    new_programs = [five31(), candito, russian(), calgary()]
    names = {p["name"] for p in new_programs}
    data["programs"] = [p for p in data["programs"] if p["name"] not in names] + new_programs
    data["program_count"] = len(data["programs"])

    with open(BLOCKS, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("programs now:", [p["name"] for p in data["programs"]])


if __name__ == "__main__":
    main()
