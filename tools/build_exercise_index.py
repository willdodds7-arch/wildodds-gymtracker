#!/usr/bin/env python3
"""
Generates app/src/main/resources/reader/exercises.tsv — the Reader's bundled exercise index.

Why generated rather than hand-written: the real space of exercise names is a small set of
MOVEMENTS crossed with equipment and qualifiers, and writing the cross product by hand guarantees
inconsistent metadata (the same movement tagged HORIZONTAL_PUSH in one row and PUSH in another) plus
a long tail of typos. Generating it from the base movements means the pattern, muscle and equipment
of every variant are correct by construction, and adding a movement adds its whole family.

Why TSV rather than JSON: it is roughly a third of the size for this shape, it parses with a split
in the reader with no allocation per field, and it stays readable in a diff — which matters, because
this file IS the review surface when someone adds exercises.

Columns:
  canonical | pattern | primary muscles | equipment | unilateral (0/1) | aliases | secondary muscles

`primary` and `secondary` are comma-separated. The FIRST primary is the main mover and never
changes when the list grows, so a consumer wanting one muscle can keep taking it.

Run:  python tools/build_exercise_index.py
"""

import os
import sys

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main",
                   "resources", "reader", "exercises.tsv")

# ── base movements ────────────────────────────────────────────────────────────
# (canonical, pattern, primary, default equipment, unilateral, aliases, variants, equipment set)
#
# `variants` and `equipment` are the qualifiers that make SENSE for this movement. A bench press has
# an incline; a deadlift does not. Restricting them per movement is the whole reason this generates
# clean data rather than a combinatorial mess.

GRIPS = ["Close Grip", "Wide Grip", "Neutral Grip", "Reverse Grip"]
ANGLES = ["Incline", "Decline", "Flat"]
STANCES = ["Sumo", "Conventional", "Narrow Stance", "Wide Stance"]
TEMPOS = ["Paused", "Tempo", "Slow Eccentric"]
POSITIONS = ["Seated", "Standing", "Lying", "Kneeling", "Half Kneeling"]

BB, DB, KB, MACHINE, CABLE, BW, SMITH, BAND, SLED, SANDBAG = (
    "BARBELL", "DUMBBELL", "KETTLEBELL", "MACHINE", "CABLE", "BODYWEIGHT",
    "SMITH_MACHINE", "BAND", "SLED", "SANDBAG")

# ─── muscles ─────────────────────────────────────────────────────────────────
# base movement -> (further PRIMARY muscles, SECONDARY muscles)
#
# The primary column of the TSV already carries the movement's main mover; this adds the muscles
# that make it a compound. It lives HERE, per base movement, rather than being derived from the
# pattern, because pattern alone cannot tell a Back Squat from a Leg Extension: both are
# (SQUAT, QUADS), and giving the leg extension a squat's glutes and hamstrings would inflate every
# weekly volume number that touches it.
#
# Every base movement must appear. main() fails if one does not, so a movement added later cannot
# quietly ship with no muscles at all.
#
# Primary counts fully toward weekly sets; secondary counts at 0.5x. A stretch trains nothing and
# is listed with empty lists rather than omitted, so 'missing' always means 'someone forgot'.

MUSCLES = {
 # horizontal push
 "Bench Press": (["FRONT_DELT"], ["TRICEPS"]),
 "Push Up": (["FRONT_DELT"], ["TRICEPS", "ABS"]),
 "Chest Fly": ([], ["FRONT_DELT"]),
 "Chest Press": (["FRONT_DELT"], ["TRICEPS"]),
 "Dip": (["TRICEPS"], ["FRONT_DELT"]),
 # vertical push
 "Overhead Press": (["SIDE_DELT"], ["TRICEPS", "TRAPS", "ABS"]),
 "Push Press": (["SIDE_DELT"], ["TRICEPS", "TRAPS", "QUADS"]),
 "Push Jerk": (["SIDE_DELT"], ["TRICEPS", "TRAPS", "QUADS"]),
 "Handstand Push Up": (["SIDE_DELT"], ["TRICEPS", "TRAPS", "ABS"]),
 "Arnold Press": (["SIDE_DELT"], ["TRICEPS"]),
 "Lateral Raise": ([], []),
 "Front Raise": ([], []),
 "Log Press": (["SIDE_DELT"], ["TRICEPS", "TRAPS", "ABS"]),
 "Landmine Press": (["SIDE_DELT"], ["TRICEPS", "ABS"]),
 # vertical pull
 "Upright Row": (["TRAPS"], ["BICEPS", "REAR_DELT"]),
 "Pull Up": (["UPPER_BACK"], ["BICEPS", "REAR_DELT", "FOREARMS"]),
 "Chin Up": (["BICEPS"], ["UPPER_BACK", "FOREARMS"]),
 "Lat Pulldown": (["UPPER_BACK"], ["BICEPS", "REAR_DELT", "FOREARMS"]),
 "Straight Arm Pulldown": ([], ["TRICEPS", "ABS"]),
 "Pullover": ([], ["CHEST", "TRICEPS"]),
 "Muscle Up": (["UPPER_BACK", "TRICEPS"], ["BICEPS", "CHEST", "ABS"]),
 # horizontal pull
 "Barbell Row": (["LATS"], ["BICEPS", "REAR_DELT", "LOWER_BACK", "FOREARMS"]),
 "Dumbbell Row": (["LATS"], ["BICEPS", "REAR_DELT", "FOREARMS"]),
 "Cable Row": (["LATS"], ["BICEPS", "REAR_DELT", "FOREARMS"]),
 "T Bar Row": (["LATS"], ["BICEPS", "REAR_DELT", "LOWER_BACK", "FOREARMS"]),
 "Inverted Row": (["LATS"], ["BICEPS", "REAR_DELT", "ABS"]),
 "Face Pull": (["UPPER_BACK"], ["TRAPS", "BICEPS"]),
 "Rear Delt Fly": ([], ["UPPER_BACK"]),
 "Shrug": ([], ["FOREARMS", "UPPER_BACK"]),
 "Face Pull Apart": ([], ["UPPER_BACK"]),
 "Cuban Rotation": ([], ["UPPER_BACK", "TRAPS"]),
 "External Rotation": ([], []),
 # squat
 "Back Squat": (["GLUTES"], ["HAMSTRINGS", "ADDUCTORS", "LOWER_BACK", "ABS"]),
 "Front Squat": (["GLUTES"], ["ABS", "UPPER_BACK", "ADDUCTORS"]),
 "Goblet Squat": (["GLUTES"], ["ABS", "ADDUCTORS"]),
 "Overhead Squat": (["GLUTES"], ["ABS", "FRONT_DELT", "UPPER_BACK", "ADDUCTORS"]),
 "Hack Squat": (["GLUTES"], ["ADDUCTORS"]),
 "Leg Press": (["GLUTES"], ["HAMSTRINGS", "ADDUCTORS"]),
 "Sissy Squat": ([], []),
 "Pistol Squat": (["GLUTES"], ["ABS", "ADDUCTORS"]),
 "Wall Sit": ([], ["GLUTES"]),
 "Leg Extension": ([], []),
 "Zercher Squat": (["GLUTES"], ["ABS", "UPPER_BACK", "BICEPS"]),
 # lunge
 "Bulgarian Split Squat": (["GLUTES"], ["HAMSTRINGS", "ADDUCTORS", "ABS"]),
 "Split Squat": (["GLUTES"], ["HAMSTRINGS", "ADDUCTORS"]),
 "Lunge": (["GLUTES"], ["HAMSTRINGS", "ADDUCTORS", "ABS"]),
 "Step Up": (["GLUTES"], ["HAMSTRINGS", "CALVES"]),
 # hinge
 "Deadlift": (["GLUTES", "LOWER_BACK"], ["TRAPS", "UPPER_BACK", "FOREARMS", "QUADS"]),
 "Romanian Deadlift": (["GLUTES"], ["LOWER_BACK", "TRAPS", "FOREARMS"]),
 "Stiff Leg Deadlift": (["GLUTES"], ["LOWER_BACK", "TRAPS", "FOREARMS"]),
 "Good Morning": (["GLUTES"], ["LOWER_BACK", "UPPER_BACK"]),
 "Hip Thrust": ([], ["HAMSTRINGS", "QUADS"]),
 "Glute Bridge": ([], ["HAMSTRINGS"]),
 "Back Extension": (["GLUTES"], ["HAMSTRINGS"]),
 "Back Extension Machine": (["GLUTES"], ["HAMSTRINGS"]),
 "Glute Ham Raise": (["GLUTES"], ["LOWER_BACK", "CALVES"]),
 "Nordic Curl": (["GLUTES"], ["CALVES"]),
 "Leg Curl": ([], ["CALVES"]),
 "Kettlebell Swing": (["HAMSTRINGS"], ["LOWER_BACK", "ABS", "FOREARMS"]),
 "Hip Abduction": ([], ["GLUTES"]),
 "Hip Adduction": ([], []),
 "Atlas Stone": (["GLUTES", "HAMSTRINGS"], ["BICEPS", "ABS", "UPPER_BACK", "QUADS"]),
 "Tire Flip": (["QUADS", "HAMSTRINGS"], ["LOWER_BACK", "UPPER_BACK", "BICEPS"]),
 # olympic
 "Clean": (["GLUTES", "TRAPS"], ["HAMSTRINGS", "UPPER_BACK", "LOWER_BACK", "CALVES", "FRONT_DELT"]),
 "Snatch": (["GLUTES", "TRAPS"], ["HAMSTRINGS", "UPPER_BACK", "LOWER_BACK", "CALVES", "FRONT_DELT"]),
 "Clean and Jerk": (["GLUTES", "TRAPS", "FRONT_DELT"], ["HAMSTRINGS", "UPPER_BACK", "LOWER_BACK", "TRICEPS", "CALVES"]),
 "High Pull": (["REAR_DELT"], ["HAMSTRINGS", "GLUTES", "UPPER_BACK", "BICEPS"]),
 "Thruster": (["GLUTES", "FRONT_DELT"], ["TRICEPS", "ABS", "UPPER_BACK"]),
 # elbow flexion / extension
 "Barbell Curl": ([], ["FOREARMS"]),
 "Dumbbell Curl": ([], ["FOREARMS"]),
 "Hammer Curl": (["FOREARMS"], []),
 "Preacher Curl": ([], ["FOREARMS"]),
 "Concentration Curl": ([], ["FOREARMS"]),
 "Spider Curl": ([], ["FOREARMS"]),
 "Reverse Curl": (["BICEPS"], []),
 "Wrist Curl": ([], []),
 "Tricep Pushdown": ([], []),
 "Overhead Tricep Extension": ([], []),
 "Skull Crusher": ([], []),
 "Close Grip Bench Press": (["CHEST"], ["FRONT_DELT"]),
 "JM Press": ([], ["CHEST", "FRONT_DELT"]),
 "Tricep Kickback": ([], []),
 # carry
 "Farmer Carry": (["TRAPS"], ["ABS", "UPPER_BACK", "QUADS"]),
 "Suitcase Carry": (["FOREARMS"], ["TRAPS", "ABS"]),
 "Yoke Walk": (["TRAPS", "ABS"], ["LOWER_BACK", "UPPER_BACK", "CALVES"]),
 "Sandbag Carry": (["ABS", "TRAPS"], ["UPPER_BACK", "QUADS", "FOREARMS"]),
 # core
 "Plank": ([], ["OBLIQUES", "FRONT_DELT"]),
 "Side Plank": ([], ["ABS", "ABDUCTORS"]),
 "Hanging Leg Raise": (["HIP_FLEXORS"], ["OBLIQUES", "FOREARMS"]),
 "Toes to Bar": (["HIP_FLEXORS"], ["LATS", "OBLIQUES", "FOREARMS"]),
 "Ab Wheel": ([], ["OBLIQUES", "LATS"]),
 "Cable Crunch": ([], ["OBLIQUES"]),
 "Crunch": ([], ["OBLIQUES"]),
 "Russian Twist": ([], ["ABS"]),
 "Pallof Press": ([], ["ABS"]),
 "Dead Bug": ([], ["OBLIQUES", "HIP_FLEXORS"]),
 "Bird Dog": ([], ["LOWER_BACK", "GLUTES"]),
 "Hollow Hold": ([], ["HIP_FLEXORS", "OBLIQUES"]),
 "L Sit": (["HIP_FLEXORS"], ["TRICEPS", "OBLIQUES"]),
 "Copenhagen Plank": ([], ["OBLIQUES", "ABS"]),
 "Neck Curl": ([], []),
 # calf
 "Calf Raise": ([], []),
 "Tibialis Raise": ([], []),
 # conditioning
 "Row": (["LATS"], ["BICEPS", "QUADS", "HAMSTRINGS"]),
 "Assault Bike": ([], ["HAMSTRINGS", "CALVES", "FRONT_DELT"]),
 "Ski Erg": (["TRICEPS"], ["ABS", "UPPER_BACK"]),
 "Treadmill Run": (["HAMSTRINGS"], ["GLUTES", "CALVES"]),
 "Sled Push": (["GLUTES"], ["CALVES", "HAMSTRINGS"]),
 "Sled Drag": (["GLUTES"], ["QUADS", "CALVES", "UPPER_BACK"]),
 "Burpee": (["CHEST"], ["TRICEPS", "ABS", "CALVES"]),
 "Box Jump": (["GLUTES"], ["CALVES", "HAMSTRINGS"]),
 "Jump Rope": ([], ["FOREARMS"]),
 "Battle Rope": ([], ["ABS", "UPPER_BACK", "FOREARMS"]),
 "Wall Ball": (["GLUTES", "FRONT_DELT"], ["TRICEPS", "ABS"]),
 "Mountain Climber": (["HIP_FLEXORS"], ["OBLIQUES", "FRONT_DELT"]),
 "Broad Jump": (["GLUTES"], ["HAMSTRINGS", "CALVES"]),
 "Sprint": (["GLUTES"], ["QUADS", "CALVES"]),
 # mobility — a stretch trains nothing; it is listed so the generator does not warn.
 "Couch Stretch": ([], []),
 "90 90 Hip Stretch": ([], []),
 "Cat Cow": ([], []),
 "Thoracic Extension": ([], []),
 "Hip Airplane": ([], []),
 "World's Greatest Stretch": ([], []),
}


BASE = [
    # ── horizontal push ──
    ("Bench Press", "HORIZONTAL_PUSH", "CHEST", BB, 0,
     ["bp", "barbell bench", "flat bench", "bench"],
     ANGLES + GRIPS + TEMPOS + ["Floor", "Board", "Pin", "Spoto", "Larsen", "Feet Up"],
     [BB, DB, MACHINE, SMITH, CABLE]),
    ("Push Up", "HORIZONTAL_PUSH", "CHEST", BW, 0,
     ["pushup", "press up", "press-up"],
     ["Incline", "Decline", "Diamond", "Wide", "Deficit", "Archer", "Ring", "Banded", "Weighted"],
     [BW]),
    ("Chest Fly", "HORIZONTAL_PUSH", "CHEST", DB, 0,
     ["fly", "flye", "chest flye", "pec fly", "pec deck"],
     ["Incline", "Decline", "Low to High", "High to Low"],
     [DB, CABLE, MACHINE]),
    ("Chest Press", "HORIZONTAL_PUSH", "CHEST", MACHINE, 0,
     ["machine chest press", "hammer press"],
     ["Incline", "Decline", "Single Arm"],
     [MACHINE, CABLE]),
    ("Dip", "HORIZONTAL_PUSH", "CHEST", BW, 0,
     ["dips", "chest dip", "parallel bar dip"],
     ["Weighted", "Assisted", "Ring", "Bench"],
     [BW, MACHINE]),

    # ── vertical push ──
    ("Overhead Press", "VERTICAL_PUSH", "FRONT_DELT", BB, 0,
     ["ohp", "military press", "shoulder press", "strict press", "press"],
     POSITIONS + GRIPS + TEMPOS + ["Behind the Neck", "Z", "Bradford"],
     [BB, DB, KB, MACHINE, SMITH, CABLE]),
    ("Push Press", "VERTICAL_PUSH", "FRONT_DELT", BB, 0,
     ["pp"], ["Single Arm"], [BB, DB, KB]),
    ("Push Jerk", "VERTICAL_PUSH", "FRONT_DELT", BB, 0,
     ["jerk", "split jerk", "power jerk"], [], [BB, DB, KB]),
    ("Handstand Push Up", "VERTICAL_PUSH", "FRONT_DELT", BW, 0,
     ["hspu", "handstand pushup"], ["Deficit", "Strict", "Kipping", "Wall"], [BW]),
    ("Arnold Press", "VERTICAL_PUSH", "FRONT_DELT", DB, 0, ["arnolds"], POSITIONS, [DB]),
    ("Lateral Raise", "VERTICAL_PUSH", "SIDE_DELT", DB, 0,
     ["side raise", "lat raise", "side lateral raise"],
     POSITIONS + ["Leaning", "Cheat", "Partial", "Single Arm"],
     [DB, CABLE, MACHINE, BAND]),
    ("Front Raise", "VERTICAL_PUSH", "FRONT_DELT", DB, 0,
     ["front delt raise"], POSITIONS + ["Plate", "Single Arm"], [DB, CABLE, BB, BAND]),
    ("Upright Row", "VERTICAL_PULL", "SIDE_DELT", BB, 0,
     ["upright rows"], GRIPS, [BB, DB, CABLE, KB]),

    # ── vertical pull ──
    ("Pull Up", "VERTICAL_PULL", "LATS", BW, 0,
     ["pu", "pullup", "pull-up"],
     GRIPS + ["Weighted", "Assisted", "Banded", "Strict", "Kipping", "Butterfly", "L Sit"],
     [BW, MACHINE]),
    ("Chin Up", "VERTICAL_PULL", "LATS", BW, 0,
     ["cu", "chinup", "chin-up"], ["Weighted", "Assisted", "Banded"], [BW, MACHINE]),
    ("Lat Pulldown", "VERTICAL_PULL", "LATS", CABLE, 0,
     ["lpd", "pulldown", "lat pull down", "lat pull-down"],
     GRIPS + ["Single Arm", "Kneeling", "Behind the Neck"],
     [CABLE, MACHINE]),
    ("Straight Arm Pulldown", "VERTICAL_PULL", "LATS", CABLE, 0,
     ["straight arm pull down", "lat pullover"], [], [CABLE, MACHINE]),
    ("Pullover", "VERTICAL_PULL", "LATS", DB, 0,
     ["dumbbell pullover"], ["Lying"], [DB, BB, CABLE, MACHINE]),
    ("Muscle Up", "VERTICAL_PULL", "LATS", BW, 0, ["muscleup"], ["Ring", "Bar", "Strict"], [BW]),

    # ── horizontal pull ──
    ("Barbell Row", "HORIZONTAL_PULL", "UPPER_BACK", BB, 0,
     ["bor", "bent over row", "bent-over row", "pendlay row", "barbell bent over row"],
     GRIPS + TEMPOS + ["Pendlay", "Yates", "Chest Supported", "Dead Stop"],
     [BB, SMITH]),
    ("Dumbbell Row", "HORIZONTAL_PULL", "UPPER_BACK", DB, 1,
     ["db row", "one arm row", "single arm row", "kroc row"],
     ["Chest Supported", "Incline", "Bench Supported", "Single Arm"],
     [DB, KB]),
    ("Cable Row", "HORIZONTAL_PULL", "UPPER_BACK", CABLE, 0,
     ["seated row", "seated cable row", "low row"],
     GRIPS + ["Single Arm", "Chest Supported"],
     [CABLE, MACHINE]),
    ("T Bar Row", "HORIZONTAL_PULL", "UPPER_BACK", BB, 0,
     ["t-bar row", "tbar row", "landmine row"], GRIPS + ["Chest Supported"], [BB, MACHINE]),
    ("Inverted Row", "HORIZONTAL_PULL", "UPPER_BACK", BW, 0,
     ["body row", "australian pull up", "ring row"], ["Feet Elevated", "Weighted", "Ring"], [BW]),
    ("Face Pull", "HORIZONTAL_PULL", "REAR_DELT", CABLE, 0,
     ["facepull", "fp"], ["Seated", "Kneeling", "Half Kneeling"], [CABLE, BAND]),
    ("Rear Delt Fly", "HORIZONTAL_PULL", "REAR_DELT", DB, 0,
     ["reverse fly", "rear delt flye", "reverse pec deck", "bent over fly"],
     POSITIONS + ["Incline"], [DB, CABLE, MACHINE, BAND]),
    ("Shrug", "HORIZONTAL_PULL", "TRAPS", BB, 0,
     ["shrugs", "barbell shrug"], GRIPS + ["Behind the Back", "Snatch Grip"],
     [BB, DB, MACHINE, CABLE, SMITH, KB]),

    # ── squat ──
    ("Back Squat", "SQUAT", "QUADS", BB, 0,
     ["bs", "squat", "hbbs", "lbbs", "high bar squat", "low bar squat", "barbell squat",
      "bodyweight squat", "air squat"],
     STANCES + TEMPOS + ["High Bar", "Low Bar", "Box", "Pin", "Anderson", "Pause", "1 1/4"],
     [BB, SMITH, MACHINE]),
    ("Front Squat", "SQUAT", "QUADS", BB, 0,
     ["fs"], STANCES + TEMPOS + ["Box", "Pin", "Zombie"], [BB, DB, KB, SMITH, MACHINE]),
    ("Goblet Squat", "SQUAT", "QUADS", DB, 0, ["goblet"], TEMPOS + ["Heels Elevated"], [DB, KB]),
    ("Overhead Squat", "SQUAT", "QUADS", BB, 0, ["ohs"], [], [BB, DB, KB]),
    ("Hack Squat", "SQUAT", "QUADS", MACHINE, 0,
     ["machine hack squat"], STANCES + ["Reverse"], [MACHINE, BB]),
    ("Leg Press", "SQUAT", "QUADS", MACHINE, 0,
     ["lp", "leg-press"], STANCES + ["Single Leg", "High Foot", "Low Foot"], [MACHINE]),
    ("Bulgarian Split Squat", "LUNGE", "QUADS", DB, 1,
     ["bss", "rfess", "rear foot elevated split squat", "bulgarian"],
     TEMPOS + ["Deficit", "Front Foot Elevated"], [DB, BB, KB, SMITH, BW]),
    ("Split Squat", "LUNGE", "QUADS", DB, 1,
     ["static lunge"], TEMPOS + ["Deficit", "Front Foot Elevated"], [DB, BB, KB, BW]),
    ("Lunge", "LUNGE", "QUADS", DB, 1,
     ["lunges", "walking lunge", "reverse lunge", "forward lunge"],
     ["Walking", "Reverse", "Forward", "Lateral", "Curtsy", "Deficit"],
     [DB, BB, KB, BW, SANDBAG]),
    ("Step Up", "LUNGE", "QUADS", DB, 1,
     ["stepup", "box step up"], ["Lateral", "Weighted", "High Box"], [DB, BB, KB, BW]),
    ("Sissy Squat", "SQUAT", "QUADS", BW, 0, [], ["Assisted", "Weighted"], [BW, MACHINE]),
    ("Pistol Squat", "SQUAT", "QUADS", BW, 1, ["single leg squat"], ["Assisted", "Weighted"], [BW]),
    ("Wall Sit", "SQUAT", "QUADS", BW, 0, ["wall squat"], ["Weighted"], [BW]),
    ("Leg Extension", "SQUAT", "QUADS", MACHINE, 0,
     ["le", "knee extension", "quad extension"], ["Single Leg", "Paused"], [MACHINE, BAND]),

    # ── hinge ──
    ("Deadlift", "HINGE", "HAMSTRINGS", BB, 0,
     ["dl", "conventional deadlift"],
     STANCES + TEMPOS + ["Deficit", "Block", "Rack Pull", "Pin", "Snatch Grip", "Touch and Go",
                         "Dead Stop", "Paused"],
     [BB, DB, KB, MACHINE, SMITH, SANDBAG]),
    ("Romanian Deadlift", "HINGE", "HAMSTRINGS", BB, 0,
     ["rdl", "romanian dl"], TEMPOS + ["Single Leg", "Deficit", "Snatch Grip"],
     [BB, DB, KB, SMITH]),
    ("Stiff Leg Deadlift", "HINGE", "HAMSTRINGS", BB, 0,
     ["sldl", "sdl", "straight leg deadlift"], ["Deficit"], [BB, DB]),
    ("Good Morning", "HINGE", "HAMSTRINGS", BB, 0,
     ["gm", "good mornings"], STANCES + ["Seated", "Safety Bar"], [BB, BAND]),
    ("Hip Thrust", "HINGE", "GLUTES", BB, 0,
     ["barbell hip thrust", "glute bridge"], ["Single Leg", "Paused", "Banded", "B Stance"],
     [BB, DB, MACHINE, BW, BAND]),
    ("Glute Bridge", "HINGE", "GLUTES", BW, 0,
     ["bridge"], ["Single Leg", "Weighted", "Banded", "Feet Elevated"], [BW, BB, DB]),
    ("Back Extension", "HINGE", "LOWER_BACK", BW, 0,
     ["hyperextension", "45 degree back extension", "roman chair"], ["Weighted", "Single Leg"],
     [BW, MACHINE]),
    ("Glute Ham Raise", "HINGE", "HAMSTRINGS", BW, 0,
     ["ghr", "ghd raise"], ["Assisted", "Weighted", "Eccentric"], [BW, MACHINE]),
    ("Nordic Curl", "HINGE", "HAMSTRINGS", BW, 0,
     ["nordic hamstring curl", "nordics"], ["Assisted", "Eccentric"], [BW]),
    ("Leg Curl", "HINGE", "HAMSTRINGS", MACHINE, 0,
     ["lc", "hamstring curl", "lying leg curl", "seated leg curl"],
     ["Lying", "Seated", "Standing", "Single Leg"], [MACHINE, BAND]),
    ("Kettlebell Swing", "HINGE", "GLUTES", KB, 0,
     ["kbs", "swing", "russian swing", "american swing"], ["Single Arm", "American", "Russian"],
     [KB, DB]),
    ("Back Extension Machine", "HINGE", "LOWER_BACK", MACHINE, 0, ["lumbar extension"], [], [MACHINE]),

    # ── olympic ──
    ("Clean", "OLYMPIC", "QUADS", BB, 0,
     ["power clean", "squat clean", "hang clean"],
     ["Power", "Hang", "Squat", "High Hang", "From Blocks", "Muscle"], [BB, DB, KB]),
    ("Snatch", "OLYMPIC", "QUADS", BB, 0,
     ["power snatch", "squat snatch", "hang snatch"],
     ["Power", "Hang", "Squat", "High Hang", "From Blocks", "Muscle", "Single Arm"], [BB, DB, KB]),
    ("Clean and Jerk", "OLYMPIC", "QUADS", BB, 0, ["c&j", "clean & jerk"], ["Power", "Hang"], [BB, DB]),
    ("High Pull", "OLYMPIC", "TRAPS", BB, 0, ["clean pull", "snatch pull"], ["Hang"], [BB, DB, KB]),
    ("Thruster", "OLYMPIC", "QUADS", BB, 0, ["thrusters"], [], [BB, DB, KB]),

    # ── arms ──
    ("Barbell Curl", "ELBOW_FLEXION", "BICEPS", BB, 0,
     ["bb curl", "bicep curl", "curl"], GRIPS + ["EZ Bar", "Cheat", "Drag", "21s"],
     [BB, DB, CABLE, MACHINE, BAND]),
    ("Dumbbell Curl", "ELBOW_FLEXION", "BICEPS", DB, 0,
     ["db curl", "alternating curl"], POSITIONS + ["Incline", "Alternating", "Single Arm"],
     [DB, CABLE, KB]),
    ("Hammer Curl", "ELBOW_FLEXION", "BICEPS", DB, 0,
     ["neutral grip curl"], POSITIONS + ["Cross Body", "Incline", "Single Arm"], [DB, CABLE, BAND]),
    ("Preacher Curl", "ELBOW_FLEXION", "BICEPS", BB, 0,
     ["pb", "scott curl"], ["EZ Bar", "Single Arm"], [BB, DB, CABLE, MACHINE]),
    ("Concentration Curl", "ELBOW_FLEXION", "BICEPS", DB, 1, [], [], [DB, CABLE]),
    ("Spider Curl", "ELBOW_FLEXION", "BICEPS", DB, 0, [], ["EZ Bar"], [DB, BB, CABLE]),
    ("Reverse Curl", "ELBOW_FLEXION", "FOREARMS", BB, 0, [], ["EZ Bar"], [BB, DB, CABLE]),
    ("Tricep Pushdown", "ELBOW_EXTENSION", "TRICEPS", CABLE, 0,
     ["pushdown", "cable pushdown", "rope pushdown", "tricep extension"],
     GRIPS + ["Rope", "Straight Bar", "V Bar", "Single Arm"], [CABLE, BAND]),
    ("Overhead Tricep Extension", "ELBOW_EXTENSION", "TRICEPS", DB, 0,
     ["french press", "skull crusher", "overhead extension"],
     POSITIONS + ["Rope", "EZ Bar", "Single Arm"], [DB, CABLE, BB, MACHINE]),
    ("Skull Crusher", "ELBOW_EXTENSION", "TRICEPS", BB, 0,
     ["lying tricep extension", "nose breaker"], ["EZ Bar", "Incline", "Decline"], [BB, DB, CABLE]),
    ("Close Grip Bench Press", "HORIZONTAL_PUSH", "TRICEPS", BB, 0,
     ["cgbp", "close grip bench"], ANGLES + TEMPOS, [BB, SMITH, DB]),
    ("JM Press", "ELBOW_EXTENSION", "TRICEPS", BB, 0, [], [], [BB, DB]),
    ("Tricep Kickback", "ELBOW_EXTENSION", "TRICEPS", DB, 1, ["kickback"], [], [DB, CABLE]),
    ("Wrist Curl", "ELBOW_FLEXION", "FOREARMS", DB, 0,
     ["forearm curl"], ["Reverse", "Behind the Back"], [DB, BB, CABLE]),
    ("Farmer Carry", "CARRY", "FOREARMS", DB, 0,
     ["farmers walk", "farmer's walk", "farmers carry", "loaded carry"],
     ["Single Arm", "Suitcase", "Overhead", "Front Rack"], [DB, KB, BB, SANDBAG]),

    # ── core ──
    ("Plank", "CORE", "ABS", BW, 0,
     ["front plank", "forearm plank"], ["Side", "Weighted", "RKC", "Long Lever", "Single Arm"], [BW]),
    ("Side Plank", "CORE", "OBLIQUES", BW, 1, ["side bridge"], ["Weighted", "With Reach"], [BW]),
    ("Hanging Leg Raise", "CORE", "ABS", BW, 0,
     ["hlr", "hanging knee raise", "leg raise"], ["Knee", "Straight Leg", "Weighted"], [BW, MACHINE]),
    ("Toes to Bar", "CORE", "ABS", BW, 0, ["t2b", "ttb"], ["Strict", "Kipping"], [BW]),
    ("Ab Wheel", "CORE", "ABS", BW, 0,
     ["ab rollout", "rollout", "wheel rollout"], ["Kneeling", "Standing"], [BW]),
    ("Cable Crunch", "CORE", "ABS", CABLE, 0, ["kneeling cable crunch"], ["Kneeling", "Standing"], [CABLE]),
    ("Crunch", "CORE", "ABS", BW, 0,
     ["sit up", "situp", "sit-up"], ["Weighted", "Decline", "Bicycle", "Reverse"], [BW, MACHINE, CABLE]),
    ("Russian Twist", "CORE", "OBLIQUES", BW, 0, ["twist"], ["Weighted"], [BW, DB, KB]),
    ("Pallof Press", "CORE", "OBLIQUES", CABLE, 0,
     ["anti rotation press"], POSITIONS, [CABLE, BAND]),
    ("Dead Bug", "CORE", "ABS", BW, 0, ["deadbug"], ["Weighted", "Banded"], [BW]),
    ("Bird Dog", "CORE", "ABS", BW, 0, [], [], [BW]),
    ("Hollow Hold", "CORE", "ABS", BW, 0, ["hollow body hold"], ["Rock"], [BW]),
    ("L Sit", "CORE", "ABS", BW, 0, ["l-sit"], ["Tuck"], [BW]),
    ("Suitcase Carry", "CARRY", "OBLIQUES", DB, 1, [], [], [DB, KB]),

    # ── calves, neck, hips ──
    ("Calf Raise", "CALF", "CALVES", MACHINE, 0,
     ["standing calf raise", "seated calf raise", "calf press"],
     ["Standing", "Seated", "Single Leg", "Donkey"], [MACHINE, DB, BB, SMITH, BW]),
    ("Tibialis Raise", "CALF", "CALVES", BW, 0, ["tib raise"], ["Weighted"], [BW, MACHINE]),
    ("Neck Curl", "CORE", "NECK", BW, 0, ["neck flexion"], ["Weighted", "Extension"], [BW, MACHINE]),
    ("Hip Abduction", "HINGE", "ABDUCTORS", MACHINE, 0,
     ["abduction", "clamshell"], ["Seated", "Standing", "Banded"], [MACHINE, CABLE, BAND]),
    ("Hip Adduction", "HINGE", "ADDUCTORS", MACHINE, 0,
     ["adduction", "copenhagen"], ["Seated", "Standing"], [MACHINE, CABLE, BAND]),
    ("Copenhagen Plank", "CORE", "ADDUCTORS", BW, 1, ["copenhagen"], ["Short Lever"], [BW]),

    # ── conditioning ──
    ("Row", "CONDITIONING", "UPPER_BACK", MACHINE, 0,
     ["rowing", "erg", "concept 2", "c2 row", "rower"], ["Interval", "Steady State"], [MACHINE]),
    ("Assault Bike", "CONDITIONING", "QUADS", MACHINE, 0,
     ["air bike", "echo bike", "fan bike", "bike"], ["Interval", "Steady State"], [MACHINE]),
    ("Ski Erg", "CONDITIONING", "LATS", MACHINE, 0, ["skierg", "ski"], [], [MACHINE]),
    ("Treadmill Run", "CONDITIONING", "QUADS", MACHINE, 0,
     ["run", "running", "jog", "treadmill"], ["Incline", "Interval", "Tempo"], [MACHINE]),
    ("Sled Push", "CONDITIONING", "QUADS", SLED, 0,
     ["prowler push", "prowler", "sled"], ["Heavy", "Light"], [SLED]),
    ("Sled Drag", "CONDITIONING", "HAMSTRINGS", SLED, 0,
     ["backward sled drag", "reverse sled drag"], ["Backward", "Forward"], [SLED]),
    ("Burpee", "CONDITIONING", "QUADS", BW, 0,
     ["burpees"], ["Box Jump Over", "Bar Facing", "Chest to Deck"], [BW]),
    ("Box Jump", "CONDITIONING", "QUADS", BW, 0,
     ["box jumps"], ["Step Down", "Seated", "Depth"], [BW]),
    ("Jump Rope", "CONDITIONING", "CALVES", BW, 0,
     ["skipping", "double under", "du", "single under"], ["Double Under", "Single Under"], [BW]),
    ("Battle Rope", "CONDITIONING", "FRONT_DELT", BW, 0, ["ropes"], [], [BW]),
    ("Wall Ball", "CONDITIONING", "QUADS", BW, 0, ["wall balls", "wallball"], [], [BW]),
    ("Mountain Climber", "CONDITIONING", "ABS", BW, 0, ["mountain climbers"], [], [BW]),
    ("Broad Jump", "CONDITIONING", "QUADS", BW, 0, ["standing long jump"], [], [BW]),
    ("Sprint", "CONDITIONING", "HAMSTRINGS", BW, 0,
     ["sprints", "hill sprint", "flying sprint"], ["Hill", "Flying", "Resisted"], [BW, SLED]),

    # ── strongman ──
    ("Atlas Stone", "HINGE", "LOWER_BACK", SANDBAG, 0, ["stone", "stone over bar"], [], [SANDBAG]),
    ("Log Press", "VERTICAL_PUSH", "FRONT_DELT", BB, 0, ["log clean and press"], [], [BB]),
    ("Yoke Walk", "CARRY", "QUADS", SLED, 0, ["yoke"], [], [SLED]),
    ("Tire Flip", "HINGE", "GLUTES", SANDBAG, 0, [], [], [SANDBAG]),
    ("Sandbag Carry", "CARRY", "LOWER_BACK", SANDBAG, 0, ["sandbag"], ["Bear Hug", "Shouldered"], [SANDBAG]),
    ("Zercher Squat", "SQUAT", "QUADS", BB, 0, ["zercher"], [], [BB]),
    ("Landmine Press", "VERTICAL_PUSH", "FRONT_DELT", BB, 0,
     ["landmine"], ["Single Arm", "Kneeling", "Half Kneeling"], [BB]),

    # ── mobility / prehab ──
    ("Face Pull Apart", "HORIZONTAL_PULL", "REAR_DELT", BAND, 0, ["band pull apart", "pull apart"], [], [BAND]),
    ("Cuban Rotation", "HORIZONTAL_PULL", "REAR_DELT", DB, 0, ["cuban press"], [], [DB, BAND, CABLE]),
    ("External Rotation", "HORIZONTAL_PULL", "REAR_DELT", CABLE, 1,
     ["cuff external rotation", "rotator cuff"], ["Side Lying", "Half Kneeling"], [CABLE, DB, BAND]),
    ("Couch Stretch", "MOBILITY", "HIP_FLEXORS", BW, 1, [], [], [BW]),
    ("90 90 Hip Stretch", "MOBILITY", "HIP_FLEXORS", BW, 0, ["90/90"], [], [BW]),
    ("Cat Cow", "MOBILITY", "LOWER_BACK", BW, 0, [], [], [BW]),
    ("Thoracic Extension", "MOBILITY", "UPPER_BACK", BW, 0, ["t spine extension"], [], [BW]),
    ("Hip Airplane", "MOBILITY", "GLUTES", BW, 1, [], [], [BW]),
    ("World's Greatest Stretch", "MOBILITY", "HIP_FLEXORS", BW, 0, ["wgs"], [], [BW]),
]

EQUIPMENT_WORD = {
    "BARBELL": "Barbell", "DUMBBELL": "Dumbbell", "KETTLEBELL": "Kettlebell",
    "MACHINE": "Machine", "CABLE": "Cable", "BODYWEIGHT": "", "SMITH_MACHINE": "Smith Machine",
    "BAND": "Band", "SLED": "Sled", "SANDBAG": "Sandbag",
}

# Equipment prefixes that are already implied by the canonical name; adding them makes
# "Barbell Barbell Row".
def implied(name, equip_word):
    return equip_word and equip_word.lower() in name.lower()



# How people actually say a generated name. "Incline Dumbbell Bench Press" is the precise name and
# nobody says it; they say "incline dumbbell press". These run over every generated row, so one rule
# covers the whole family rather than a hand-written alias per variant.
#
# Each rule is (must contain, find, replace). A rule that would produce the name unchanged, or a name
# already taken by a real movement, is dropped when the index loads.
SPOKEN_FORMS = [
    # "Incline Dumbbell Bench Press" -> "Incline Dumbbell Press". Not for the barbell forms, where
    # "bench press" IS what people say.
    ("Bench Press", " Bench Press", " Press"),
    # "Cable Chest Fly" -> "Cable Fly", "Dumbbell Chest Fly" -> "Dumbbell Fly".
    ("Chest Fly", " Chest Fly", " Fly"),
    # "Dumbbell Overhead Press" -> "Dumbbell Shoulder Press".
    ("Overhead Press", "Overhead Press", "Shoulder Press"),
]


def spoken_forms(canonical, equipment):
    out = []
    for contains, find, repl in SPOKEN_FORMS:
        if contains not in canonical:
            continue
        # "Bench Press" and "Barbell Bench Press" keep their names; only the qualified forms shorten.
        if contains == "Bench Press" and canonical.strip().endswith("Bench Press") and (
                equipment == "BARBELL" or canonical.strip() == "Bench Press"):
            continue
        alt = canonical.replace(find, repl).strip()
        if alt and alt != canonical:
            out.append(alt)
    return out


def main():
    rows = {}

    absent = [b[0] for b in BASE if b[0] not in MUSCLES]
    if absent:
        print(f"ERROR: no muscles for {absent}", file=sys.stderr)
        raise SystemExit(1)

    def add(canonical, pattern, primary, equipment, unilateral, aliases, base):
        key = canonical.lower()
        if key in rows:
            rows[key][5].update(a.lower() for a in aliases if a)
            return
        co, sec = MUSCLES[base]
        # The main mover first, then the co-primaries: a consumer that wants one muscle takes
        # [0], and that one must stay exactly what it was before this column grew.
        primaries = [primary] + [x for x in co if x != primary]
        secondary = [x for x in sec if x not in primaries]
        spoken = spoken_forms(canonical, equipment)
        rows[key] = [canonical, pattern, primaries, equipment, unilateral,
                     set(a.lower() for a in list(aliases) + spoken if a), secondary]

    for (name, pattern, primary, default_eq, unilateral, aliases, variants, equips) in BASE:
        add(name, pattern, primary, default_eq, unilateral, aliases, name)

        for eq in equips:
            word = EQUIPMENT_WORD[eq]
            if implied(name, word):
                continue
            variant_name = f"{word} {name}".strip()
            add(variant_name, pattern, primary, eq, unilateral, [], name)

        for v in variants:
            uni = 1 if v in ("Single Arm", "Single Leg") else unilateral
            add(f"{v} {name}", pattern, primary, default_eq, uni, [], name)
            # The two qualifiers that most often appear WITH equipment: "Incline Dumbbell Press".
            if v in ANGLES or v in ("Single Arm", "Single Leg", "Seated", "Standing"):
                for eq in equips:
                    word = EQUIPMENT_WORD[eq]
                    if not word or implied(name, word):
                        continue
                    add(f"{v} {word} {name}", pattern, primary, eq, uni, [], name)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Wild Odds Reader exercise index. GENERATED by tools/build_exercise_index.py.\n")
        f.write("# canonical\tpattern\tprimary\tequipment\tunilateral\taliases\tsecondary\n")
        for key in sorted(rows):
            c, p, m, e, u, al, sec = rows[key]
            f.write(f"{c}\t{p}\t{','.join(m)}\t{e}\t{u}\t{','.join(sorted(al))}\t{','.join(sec)}\n")

    size = os.path.getsize(OUT)
    print(f"wrote {len(rows)} exercises to {OUT} ({size // 1024} KB)")
    if len(rows) < 800:
        print("WARNING: fewer entries than expected", file=sys.stderr)


if __name__ == "__main__":
    main()
