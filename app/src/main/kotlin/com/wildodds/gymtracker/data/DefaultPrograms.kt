package com.wildodds.gymtracker.data

import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession

private fun ex(name: String, sets: Int, reps: String, notes: String = "", idx: Int) =
  ParsedExercise(name, sets, reps, notes, idx)

private data class SessionTemplate(
  val dayNumber: Int,
  val name: String,
  val muscleGroups: String,
  val exercises: List<ParsedExercise>
)

private val dayPrefixRegex  = Regex("""^Day \d+\s*[·•\-]\s*""")
private val weekdayPrefixRegex = Regex(
  """^(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s*[- - ]\s*""",
  RegexOption.IGNORE_CASE
)

private fun cleanSessionName(name: String): String {
  var s = name
  // Strip repeated "Day N · " or "Day N - " prefixes
  var prev = ""
  while (s != prev) { prev = s; s = dayPrefixRegex.replaceFirst(s, "") }
  // Strip weekday prefix like "Monday - "
  s = weekdayPrefixRegex.replaceFirst(s, "")
  return s.trim()
}

private fun buildProgram(
  name: String, weeks: Int, coverImage: String, templates: List<SessionTemplate>,
  category: String = ""
): ParsedProgram {
  val sessions = (1..weeks).flatMap { week ->
  templates.map { t -> ParsedSession(week, t.dayNumber, cleanSessionName(t.name), t.muscleGroups, t.exercises) }
  }
  return ParsedProgram(name, weeks, sessions, coverImage, category = category)
}

// ─── HYPERTROPHY ─────────────────────────────────────────────────────────────

val PROGRAM_FULL_BODY = buildProgram("Full Body 4×/Week", 5, "1", listOf(
  SessionTemplate(1, "Day 1 · Full Body A", "Quads / Chest / Back", listOf(
  ex("Back Squat", 3, "5", idx = 0), ex("Bench Press", 3, "8-12", idx = 1),
  ex("Barbell Row", 3, "8-12", idx = 2), ex("Romanian DL", 3, "10-12", idx = 3))),
  SessionTemplate(2, "Day 2 · Full Body B", "Hips / Shoulders / Lats", listOf(
  ex("Deadlift", 3, "5", idx = 0), ex("Overhead Press", 3, "8-12", idx = 1),
  ex("Lat Pulldown", 3, "10-12", idx = 2), ex("Leg Curl", 3, "10-12", idx = 3))),
  SessionTemplate(3, "Day 3 · Full Body C", "Quads / Chest / Back", listOf(
  ex("Front Squat", 3, "6-8", idx = 0), ex("Incline DB Press", 3, "8-12", idx = 1),
  ex("Cable Row", 3, "10-12", idx = 2), ex("Leg Press", 3, "10-12", idx = 3))),
  SessionTemplate(4, "Day 4 · Full Body D", "Hips / Chest / Back", listOf(
  ex("Trap Bar Deadlift", 3, "5", idx = 0), ex("Dips", 3, "8-12", idx = 1),
  ex("Pull-Ups", 3, "AMRAP", idx = 2), ex("Face Pulls", 3, "12-15", idx = 3)))
), "Hypertrophy")

val PROGRAM_ANT_POST = buildProgram("Anterior / Posterior 4×/Week", 5, "2", listOf(
  SessionTemplate(1, "Day 1 · Anterior", "Quads / Chest / Shoulders / Biceps", listOf(
  ex("Back Squat", 3, "5", idx = 0), ex("Bench Press", 3, "8-12", idx = 1),
  ex("Overhead Press", 3, "8-12", idx = 2), ex("Leg Extension", 3, "12-15", idx = 3))),
  SessionTemplate(2, "Day 2 · Posterior", "Hamstrings / Back / Glutes / Triceps", listOf(
  ex("Deadlift", 3, "5", idx = 0), ex("Barbell Row", 3, "8-12", idx = 1),
  ex("Lat Pulldown", 3, "10-12", idx = 2), ex("Leg Curl", 3, "12-15", idx = 3))),
  SessionTemplate(3, "Day 3 · Anterior Vol", "Quads / Chest / Shoulders / Biceps", listOf(
  ex("Bulgarian Split Squat", 3, "10-12", idx = 0), ex("Incline DB Press", 3, "10-12", idx = 1),
  ex("DB Shoulder Press", 3, "10-12", idx = 2), ex("Cable Curl", 3, "12-15", idx = 3))),
  SessionTemplate(4, "Day 4 · Posterior Vol", "Hamstrings / Back / Glutes / Triceps", listOf(
  ex("Romanian DL", 3, "10-12", idx = 0), ex("Pendlay Row", 3, "6-8", idx = 1),
  ex("Pull-Ups", 3, "AMRAP", idx = 2), ex("Skull Crushers", 3, "10-12", idx = 3)))
), "Hypertrophy")

val PROGRAM_PPLUL = buildProgram("PPLUL 5×/Week", 5, "4", listOf(
  SessionTemplate(1, "Day 1 · Push", "Chest / Shoulders / Triceps", listOf(
  ex("Bench Press", 4, "6-8", idx = 0), ex("Overhead Press", 3, "8-12", idx = 1),
  ex("Lateral Raises", 3, "12-15", idx = 2), ex("Tricep Pushdown", 2, "12-15", idx = 3))),
  SessionTemplate(2, "Day 2 · Pull", "Back / Biceps / Rear Delts", listOf(
  ex("Barbell Row", 4, "6-8", idx = 0), ex("Lat Pulldown", 3, "10-12", idx = 1),
  ex("Face Pulls", 3, "12-15", idx = 2), ex("Bicep Curl", 2, "12-15", idx = 3))),
  SessionTemplate(3, "Day 3 · Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("Back Squat", 4, "5-8", idx = 0), ex("Romanian DL", 3, "8-12", idx = 1),
  ex("Leg Press", 3, "10-12", idx = 2), ex("Leg Curl", 2, "12-15", idx = 3))),
  SessionTemplate(4, "Day 4 · Upper", "Chest / Back / Shoulders", listOf(
  ex("Incline DB Press", 3, "8-12", idx = 0), ex("Cable Row", 3, "10-12", idx = 1),
  ex("DB Shoulder Press", 3, "10-12", idx = 2), ex("Pull-Ups", 3, "AMRAP", idx = 3))),
  SessionTemplate(5, "Day 5 · Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Deadlift", 4, "4-6", idx = 0), ex("Leg Press", 4, "10-12", idx = 1),
  ex("Leg Extension", 2, "12-15", idx = 2), ex("Leg Curl", 2, "12-15", idx = 3)))
), "Hypertrophy")

val PROGRAM_BRO_SPLIT = buildProgram("Bro Split 6×/Week", 5, "5", listOf(
  SessionTemplate(1, "Day 1 · Chest", "Chest", listOf(
  ex("Bench Press", 4, "8-12", idx = 0), ex("Incline DB Press", 4, "10-12", idx = 1), ex("Cable Fly", 4, "12-15", idx = 2))),
  SessionTemplate(2, "Day 2 · Back", "Back / Lats", listOf(
  ex("Barbell Row", 4, "8-12", idx = 0), ex("Lat Pulldown", 4, "10-12", idx = 1), ex("Cable Row", 4, "10-12", idx = 2))),
  SessionTemplate(3, "Day 3 · Shoulders", "Shoulders", listOf(
  ex("Overhead Press", 4, "8-12", idx = 0), ex("Lateral Raises", 4, "12-15", idx = 1), ex("Rear Delt Fly", 4, "12-15", idx = 2))),
  SessionTemplate(4, "Day 4 · Arms", "Biceps / Triceps", listOf(
  ex("Barbell Curl", 3, "10-12", idx = 0), ex("Hammer Curl", 3, "10-12", idx = 1),
  ex("Skull Crushers", 3, "10-12", idx = 2), ex("Tricep Pushdown", 3, "10-12", idx = 3))),
  SessionTemplate(5, "Day 5 · Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("Back Squat", 4, "6-10", idx = 0), ex("Romanian DL", 4, "10-12", idx = 1), ex("Leg Press", 4, "10-12", idx = 2))),
  SessionTemplate(6, "Day 6 · Full Body", "Full Body", listOf(
  ex("Deadlift", 3, "5", idx = 0), ex("Pull-Ups", 3, "AMRAP", idx = 1),
  ex("Dips", 3, "AMRAP", idx = 2), ex("Farmer's Carry", 3, "40m", idx = 3)))
), "Hypertrophy")

val PROGRAM_UPPER_LOWER = buildProgram("Upper / Lower 4×/Week", 6, "2", listOf(
  SessionTemplate(1, "Day 1 · Upper A", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 4, "6-8", idx = 0), ex("Barbell Row", 4, "6-8", idx = 1),
  ex("Overhead Press", 3, "8-12", idx = 2), ex("Pull-Ups", 3, "AMRAP", idx = 3),
  ex("Lateral Raises", 3, "12-15", idx = 4))),
  SessionTemplate(2, "Day 2 · Lower A", "Quads / Hamstrings / Glutes", listOf(
  ex("Back Squat", 4, "6-8", idx = 0), ex("Romanian DL", 3, "8-12", idx = 1),
  ex("Leg Press", 3, "10-12", idx = 2), ex("Leg Curl", 3, "10-12", idx = 3),
  ex("Calf Raises", 4, "12-15", idx = 4))),
  SessionTemplate(3, "Day 3 · Upper B", "Chest / Back / Arms", listOf(
  ex("Incline DB Press", 4, "8-12", idx = 0), ex("Cable Row", 4, "10-12", idx = 1),
  ex("DB Shoulder Press", 3, "10-12", idx = 2), ex("Lat Pulldown", 3, "10-12", idx = 3),
  ex("Bicep Curl", 2, "12-15", idx = 4))),
  SessionTemplate(4, "Day 4 · Lower B", "Quads / Hamstrings / Glutes", listOf(
  ex("Deadlift", 4, "5-6", idx = 0), ex("Bulgarian Split Squat", 3, "10-12", idx = 1),
  ex("Leg Extension", 3, "12-15", idx = 2), ex("Leg Curl", 3, "12-15", idx = 3),
  ex("Calf Raises", 4, "12-15", idx = 4)))
), "Hypertrophy")

// ─── STRENGTH ─────────────────────────────────────────────────────────────────

val PROGRAM_531 = buildProgram("5/3/1 Classic 3×/Week", 4, "3", listOf(
  SessionTemplate(1, "Day 1 · Press", "Chest / Shoulders / Triceps", listOf(
  ex("Overhead Press", 3, "5/3/1", "Work up to top set then back-off", idx = 0),
  ex("Bench Press", 5, "10", "50% TM - assistance", idx = 1),
  ex("Dips", 3, "AMRAP", idx = 2), ex("Lat Pulldown", 3, "10-12", idx = 3))),
  SessionTemplate(2, "Day 2 · Deadlift", "Posterior Chain", listOf(
  ex("Deadlift", 3, "5/3/1", "Work up to top set then back-off", idx = 0),
  ex("Romanian DL", 5, "10", "50% TM - assistance", idx = 1),
  ex("Leg Press", 3, "10-12", idx = 2), ex("Hanging Leg Raise", 3, "10-15", idx = 3))),
  SessionTemplate(3, "Day 3 · Bench", "Chest / Shoulders / Triceps", listOf(
  ex("Bench Press", 3, "5/3/1", "Work up to top set then back-off", idx = 0),
  ex("Overhead Press", 5, "10", "50% TM - assistance", idx = 1),
  ex("Barbell Row", 3, "10-12", idx = 2), ex("Tricep Pushdown", 3, "12-15", idx = 3))),
  SessionTemplate(4, "Day 4 · Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Back Squat", 3, "5/3/1", "Work up to top set then back-off", idx = 0),
  ex("Front Squat", 5, "10", "50% TM - assistance", idx = 1),
  ex("Leg Curl", 3, "10-12", idx = 2), ex("Ab Wheel", 3, "10-15", idx = 3)))
), "Strength")

val PROGRAM_TEXAS_METHOD = buildProgram("Texas Method 3×/Week", 6, "3", listOf(
  SessionTemplate(1, "Day 1 · Volume", "Full Body", listOf(
  ex("Back Squat", 5, "5", "5×5 across - moderate load", idx = 0),
  ex("Bench Press", 5, "5", "5×5 across", idx = 1),
  ex("Deadlift", 1, "5", "Heavy single working set", idx = 2))),
  SessionTemplate(2, "Day 2 · Recovery", "Full Body", listOf(
  ex("Back Squat", 2, "5", "Light - 80% of Monday", idx = 0),
  ex("Overhead Press", 3, "5", "Light - technique focus", idx = 1),
  ex("Pull-Ups", 3, "AMRAP", idx = 2))),
  SessionTemplate(3, "Day 3 · Intensity", "Full Body", listOf(
  ex("Back Squat", 1, "5", "1×5 heavy - 5RM attempt", idx = 0),
  ex("Bench Press", 1, "5", "1×5 heavy - alternate with OHP", idx = 1),
  ex("Power Clean", 5, "3", idx = 2)))
), "Strength")

val PROGRAM_GREYSKULL = buildProgram("Greyskull LP 3×/Week", 5, "1", listOf(
  SessionTemplate(1, "Day 1 · A", "Full Body", listOf(
  ex("Overhead Press", 2, "5", idx = 0), ex("Overhead Press", 1, "AMRAP", "Last set AMRAP", idx = 1),
  ex("Deadlift", 1, "5", idx = 2), ex("Chin-Ups", 3, "AMRAP", idx = 3))),
  SessionTemplate(2, "Day 2 · B", "Full Body", listOf(
  ex("Bench Press", 2, "5", idx = 0), ex("Bench Press", 1, "AMRAP", "Last set AMRAP", idx = 1),
  ex("Back Squat", 2, "5", idx = 2), ex("Back Squat", 1, "AMRAP", "Last set AMRAP", idx = 3))),
  SessionTemplate(3, "Day 3 · A", "Full Body", listOf(
  ex("Overhead Press", 2, "5", idx = 0), ex("Overhead Press", 1, "AMRAP", "Last set AMRAP", idx = 1),
  ex("Deadlift", 1, "5", idx = 2), ex("Chin-Ups", 3, "AMRAP", idx = 3)))
), "Strength")

// ─── POWERLIFTING ─────────────────────────────────────────────────────────────

val PROGRAM_POWERLIFTING = buildProgram("Powerlifting 5×/Week", 5, "3", listOf(
  SessionTemplate(1, "Day 1 · Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Back Squat", 5, "3", idx = 0), ex("Romanian DL", 3, "5", idx = 1), ex("Leg Press", 4, "8", idx = 2))),
  SessionTemplate(2, "Day 2 · Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 5, "3", idx = 0), ex("Overhead Press", 4, "6", idx = 1), ex("Tricep Pushdown", 3, "10-12", idx = 2))),
  SessionTemplate(3, "Day 3 · Deadlift", "Posterior Chain", listOf(
  ex("Conventional DL", 5, "3", idx = 0), ex("Deficit DL", 3, "5", idx = 1), ex("Barbell Row", 4, "6", idx = 2))),
  SessionTemplate(4, "Day 4 · Squat Vol", "Quads / Hamstrings", listOf(
  ex("Pause Squat", 4, "4", idx = 0), ex("Good Mornings", 4, "8", idx = 1), ex("Leg Curl", 4, "10-12", idx = 2))),
  SessionTemplate(5, "Day 5 · Bench Vol", "Chest / Back / Triceps", listOf(
  ex("Close Grip Bench", 4, "6", idx = 0), ex("DB Bench Press", 4, "8-12", idx = 1), ex("Cable Row", 4, "10-12", idx = 2)))
), "Powerlifting")

val PROGRAM_CONJUGATE = buildProgram("Conjugate Method 4×/Week", 8, "3", listOf(
  SessionTemplate(1, "Day 1 · Max Effort Lower", "Quads / Posterior Chain", listOf(
  ex("Back Squat", 1, "1RM", "Work up to max single", idx = 0),
  ex("Romanian DL", 4, "6-8", idx = 1),
  ex("Good Mornings", 3, "8", idx = 2),
  ex("Leg Curl", 4, "10-12", idx = 3))),
  SessionTemplate(2, "Day 2 · Max Effort Upper", "Chest / Shoulders / Triceps", listOf(
  ex("Bench Press", 1, "1RM", "Work up to max single", idx = 0),
  ex("Close Grip Bench", 4, "5-6", idx = 1),
  ex("Barbell Row", 4, "6-8", idx = 2),
  ex("Tricep Pushdown", 4, "10-12", idx = 3))),
  SessionTemplate(3, "Day 3 · Dynamic Effort Lower", "Quads / Glutes", listOf(
  ex("Box Squat", 8, "2", "50-60% - speed focus", idx = 0),
  ex("Deadlift", 6, "1", "60% - speed pulls", idx = 1),
  ex("Reverse Hyper", 4, "15", idx = 2),
  ex("Ab Wheel", 4, "10", idx = 3))),
  SessionTemplate(4, "Day 4 · Dynamic Effort Upper", "Chest / Back", listOf(
  ex("Bench Press", 9, "3", "50-60% - speed focus", idx = 0),
  ex("Pull-Ups", 5, "5", idx = 1),
  ex("DB Shoulder Press", 4, "10-12", idx = 2),
  ex("Face Pulls", 4, "15", idx = 3)))
), "Powerlifting")

val PROGRAM_SBD_PEAKING = buildProgram("SBD 8-Week Peaking", 8, "5", listOf(
  SessionTemplate(1, "Day 1 · Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Back Squat", 5, "5", "Build weekly - see notes", idx = 0),
  ex("Pause Squat", 3, "3", "70% of working weight", idx = 1),
  ex("Leg Press", 3, "8", idx = 2))),
  SessionTemplate(2, "Day 2 · Bench", "Chest / Triceps", listOf(
  ex("Bench Press", 5, "5", "Build weekly", idx = 0),
  ex("Paused Bench", 3, "3", "70%", idx = 1),
  ex("Tricep Pushdown", 4, "10", idx = 2))),
  SessionTemplate(3, "Day 3 · Deadlift", "Posterior Chain", listOf(
  ex("Conventional DL", 5, "3", "Build weekly", idx = 0),
  ex("Stiff-Leg DL", 3, "5", idx = 1),
  ex("Barbell Row", 4, "6", idx = 2))),
  SessionTemplate(4, "Day 4 · Accessory", "Full Body", listOf(
  ex("Front Squat", 3, "3", "Technique work", idx = 0),
  ex("Close Grip Bench", 4, "6", idx = 1),
  ex("Pull-Ups", 4, "AMRAP", idx = 2),
  ex("Romanian DL", 3, "8", idx = 3)))
), "Powerlifting")

// ─── GPP / ATHLETIC ───────────────────────────────────────────────────────────

val PROGRAM_GPP_3DAY = buildProgram("GPP Foundation 3×/Week", 6, "4", listOf(
  SessionTemplate(1, "Day 1 · Squat + Hinge", "Lower Body / Core", listOf(
  ex("Goblet Squat", 3, "10", "Warm-up movement", idx = 0),
  ex("Back Squat", 4, "6-8", idx = 1),
  ex("Romanian DL", 3, "10-12", idx = 2),
  ex("Farmer's Carry", 3, "40m", idx = 3),
  ex("Plank", 3, "45s", idx = 4))),
  SessionTemplate(2, "Day 2 · Push + Pull", "Upper Body", listOf(
  ex("Push-Ups", 2, "10", "Activation", idx = 0),
  ex("Bench Press", 4, "6-8", idx = 1),
  ex("Barbell Row", 4, "6-8", idx = 2),
  ex("Overhead Press", 3, "8-10", idx = 3),
  ex("Pull-Ups", 3, "AMRAP", idx = 4))),
  SessionTemplate(3, "Day 3 · Full Body Conditioning", "Full Body", listOf(
  ex("Deadlift", 3, "5", idx = 0),
  ex("Dips", 3, "AMRAP", idx = 1),
  ex("Chin-Ups", 3, "AMRAP", idx = 2),
  ex("Sled Push", 4, "20m", "Moderate load", idx = 3),
  ex("Farmer's Carry", 3, "40m", idx = 4)))
), "GPP")

val PROGRAM_ATHLETIC_4DAY = buildProgram("Athletic Performance 4×/Week", 6, "4", listOf(
  SessionTemplate(1, "Day 1 · Power + Lower", "Quads / Glutes / Posterior Chain", listOf(
  ex("Power Clean", 4, "3", "Focus on speed & technique", idx = 0),
  ex("Back Squat", 4, "5", idx = 1),
  ex("Romanian DL", 3, "8", idx = 2),
  ex("Box Jump", 3, "5", "Explosive - rest fully", idx = 3),
  ex("Sled Push", 3, "20m", idx = 4))),
  SessionTemplate(2, "Day 2 · Power + Upper", "Chest / Back / Shoulders", listOf(
  ex("Push Press", 4, "3", "Speed through sticking point", idx = 0),
  ex("Bench Press", 4, "5", idx = 1),
  ex("Barbell Row", 4, "6", idx = 2),
  ex("Chin-Ups", 3, "AMRAP", idx = 3),
  ex("Lateral Raises", 3, "12-15", idx = 4))),
  SessionTemplate(3, "Day 3 · Unilateral Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Bulgarian Split Squat", 4, "8", idx = 0),
  ex("Single-Leg RDL", 3, "10", idx = 1),
  ex("Step-Ups", 3, "10", idx = 2),
  ex("Calf Raises", 4, "12-15", idx = 3),
  ex("Ab Wheel", 3, "10", idx = 4))),
  SessionTemplate(4, "Day 4 · Conditioning", "Full Body", listOf(
  ex("Trap Bar Deadlift", 3, "5", "Moderate speed", idx = 0),
  ex("Dumbbell Row", 3, "10", idx = 1),
  ex("Incline DB Press", 3, "10", idx = 2),
  ex("Farmer's Carry", 4, "30m", idx = 3),
  ex("Face Pulls", 3, "15", idx = 4)))
), "GPP")

// ─── BODYBUILDING ─────────────────────────────────────────────────────────────

val PROGRAM_PPL_6DAY = buildProgram("Push Pull Legs 6×/Week", 6, "5", listOf(
  SessionTemplate(1, "Day 1 · Push A", "Chest / Shoulders / Triceps", listOf(
  ex("Bench Press", 4, "6-8", idx = 0), ex("Incline DB Press", 3, "8-12", idx = 1),
  ex("Overhead Press", 3, "8-12", idx = 2), ex("Lateral Raises", 4, "12-15", idx = 3),
  ex("Tricep Pushdown", 3, "12-15", idx = 4))),
  SessionTemplate(2, "Day 2 · Pull A", "Back / Biceps / Rear Delts", listOf(
  ex("Deadlift", 3, "5", idx = 0), ex("Barbell Row", 4, "6-8", idx = 1),
  ex("Lat Pulldown", 3, "10-12", idx = 2), ex("Face Pulls", 3, "15", idx = 3),
  ex("Barbell Curl", 3, "10-12", idx = 4))),
  SessionTemplate(3, "Day 3 · Legs A", "Quads / Hamstrings / Glutes / Calves", listOf(
  ex("Back Squat", 4, "6-8", idx = 0), ex("Romanian DL", 3, "8-12", idx = 1),
  ex("Leg Press", 3, "10-12", idx = 2), ex("Leg Curl", 3, "10-12", idx = 3),
  ex("Calf Raises", 4, "12-15", idx = 4))),
  SessionTemplate(4, "Day 4 · Push B", "Chest / Shoulders / Triceps", listOf(
  ex("Incline Barbell Press", 4, "8-12", idx = 0), ex("Cable Fly", 3, "12-15", idx = 1),
  ex("DB Shoulder Press", 3, "10-12", idx = 2), ex("Lateral Raises", 3, "15-20", idx = 3),
  ex("Skull Crushers", 3, "10-12", idx = 4))),
  SessionTemplate(5, "Day 5 · Pull B", "Back / Biceps / Rear Delts", listOf(
  ex("Pull-Ups", 4, "AMRAP", idx = 0), ex("Cable Row", 4, "10-12", idx = 1),
  ex("DB Row", 3, "10-12", idx = 2), ex("Rear Delt Fly", 3, "15", idx = 3),
  ex("Hammer Curl", 3, "12-15", idx = 4))),
  SessionTemplate(6, "Day 6 · Legs B", "Quads / Hamstrings / Glutes / Calves", listOf(
  ex("Front Squat", 4, "6-8", idx = 0), ex("Bulgarian Split Squat", 3, "10-12", idx = 1),
  ex("Leg Extension", 3, "12-15", idx = 2), ex("Leg Curl", 3, "12-15", idx = 3),
  ex("Calf Raises", 4, "15-20", idx = 4)))
), "Bodybuilding")

// ─── CONDITIONING ─────────────────────────────────────────────────────────────

val PROGRAM_METCON_3DAY = buildProgram("MetCon 3×/Week", 4, "2", listOf(
  SessionTemplate(1, "Day 1 · Lower Strength + Conditioning", "Lower Body / Full Body", listOf(
  ex("Back Squat", 4, "5", "Strength work first", idx = 0),
  ex("Deadlift", 3, "5", idx = 1),
  ex("Barbell Row", 3, "8", idx = 2),
  ex("Sled Push", 5, "20m", "Rest 60s between", idx = 3))),
  SessionTemplate(2, "Day 2 · Upper Strength + Conditioning", "Upper Body / Full Body", listOf(
  ex("Bench Press", 4, "5", "Strength work first", idx = 0),
  ex("Overhead Press", 3, "6-8", idx = 1),
  ex("Pull-Ups", 4, "AMRAP", idx = 2),
  ex("Farmer's Carry", 5, "30m", "Rest 60s between", idx = 3))),
  SessionTemplate(3, "Day 3 · Full Body Conditioning", "Full Body", listOf(
  ex("Trap Bar Deadlift", 3, "6", idx = 0),
  ex("Push Press", 3, "5", idx = 1),
  ex("Chin-Ups", 3, "AMRAP", idx = 2),
  ex("Sled Push", 4, "20m", idx = 3),
  ex("Farmer's Carry", 4, "30m", idx = 4)))
), "Conditioning")

val PROG_09_ALL_PRO_BEGINNER = buildProgram("All Pro Beginner", 4, "4", listOf(
  SessionTemplate(1, "Day 1 · Full Body (3×/Week)", "Full Body", listOf(
  ex("Squat", 3, "5", "Add 2.5 kg when complete", idx = 0),
  ex("Bench Press", 3, "8", "Alternate with OHP each session", idx = 1),
  ex("Barbell Row", 3, "8", "", idx = 2),
  ex("Overhead Press", 3, "8", "Alternate with Bench", idx = 3),
  ex("Deadlift", 2, "5", "1x/wk or every other session", idx = 4),
  ex("Barbell Curl", 2, "10", "", idx = 5),
  ex("Tricep Pushdown", 2, "10", "", idx = 6),
  ex("Calf Raises", 3, "15", "", idx = 7)))
), "Strength")

// ─── IMPORTED: Top 50 Free Gym Programs ──────────────────────────────────────

val PROG_01_STARTING_STRENGTH = buildProgram("Starting Strength", 4, "1", listOf(
  SessionTemplate(1, "Day 1 · Day A", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5", "Add 2.5 kg each session", idx = 0),
  ex("Bench Press", 3, "5", "Add 1.25 kg each session", idx = 1),
  ex("Deadlift", 1, "5", "Add 2.5 kg each session", idx = 2))),
  SessionTemplate(2, "Day 2 · Day B", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5", "Add 2.5 kg each session", idx = 0),
  ex("Overhead Press", 3, "5", "Add 1.25 kg each session", idx = 1),
  ex("Power Clean", 5, "3", "Or Barbell Row alt.", idx = 2))),
), "Strength")

val PROG_02_STRONGLIFTS_5X5 = buildProgram("StrongLifts 5x5", 4, "2", listOf(
  SessionTemplate(1, "Day 1 · Workout A", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 5, "5", "+2.5 kg each session", idx = 0),
  ex("Bench Press", 5, "5", "+1.25 kg each session", idx = 1),
  ex("Barbell Row", 5, "5", "+1.25 kg each session", idx = 2))),
  SessionTemplate(2, "Day 2 · Workout B", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 5, "5", "", idx = 0),
  ex("Overhead Press", 5, "5", "+1.25 kg each session", idx = 1),
  ex("Deadlift", 1, "5", "+2.5 kg each session", idx = 2))),
), "Strength")

val PROG_03_RFITNESS_BASIC_BEGINNER = buildProgram("r/Fitness Basic Beginner", 12, "3", listOf(
  SessionTemplate(1, "Day 1 · Day A", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5", "Add 2.5 kg when all reps done", idx = 0),
  ex("Bench Press", 3, "5", "Add 1.25 kg when all reps done", idx = 1),
  ex("Barbell Row", 3, "5", "", idx = 2),
  ex("Pull-Ups / Lat Pulldown", 3, "AMRAP", "", idx = 3),
  ex("Face Pulls", 3, "15", "Shoulder health", idx = 4))),
  SessionTemplate(2, "Day 2 · Day B", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5", "", idx = 0),
  ex("Overhead Press", 3, "5", "", idx = 1),
  ex("Deadlift", 1, "5", "", idx = 2),
  ex("Pull-Ups / Lat Pulldown", 3, "AMRAP", "", idx = 3),
  ex("Face Pulls", 3, "15", "", idx = 4))),
), "Strength")

val PROG_04_531_FOR_BEGINNERS = buildProgram("5/3/1 for Beginners", 4, "4", listOf(
  SessionTemplate(1, "Day 1 · Monday (Squat + Press)", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "5/3/1+", "65/75/85% of TM (AMRAP last set)", idx = 0),
  ex("Squat", 5, "5", "First Set Last (FSL) 65%TM", idx = 1),
  ex("Overhead Press", 3, "5/3/1+", "65/75/85% of TM", idx = 2),
  ex("Overhead Press", 5, "5", "FSL 65%TM", idx = 3),
  ex("Pull-Ups", 5, "10", "Assistance", idx = 4),
  ex("Dips", 5, "10", "Assistance", idx = 5),
  ex("Ab Wheel / Plank", 5, "10", "Core", idx = 6))),
  SessionTemplate(2, "Day 2 · Wednesday (Deadlift + Bench)", "Posterior Chain", listOf(
  ex("Deadlift", 3, "5/3/1+", "65/75/85% of TM", idx = 0),
  ex("Deadlift", 5, "5", "FSL 65%TM", idx = 1),
  ex("Bench Press", 3, "5/3/1+", "65/75/85% of TM", idx = 2),
  ex("Bench Press", 5, "5", "FSL 65%TM", idx = 3),
  ex("DB Row", 5, "10", "Assistance", idx = 4),
  ex("Ab Wheel", 5, "10", "", idx = 5))),
  SessionTemplate(3, "Day 3 · Friday (Squat + Press)", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "5/3/1+", "70/80/90% (Wk2) or 75/85/95% (Wk3)", idx = 0),
  ex("Overhead Press", 3, "5/3/1+", "", idx = 1),
  ex("Pull-Ups", 5, "10", "", idx = 2),
  ex("Dips", 5, "10", "", idx = 3))),
), "Strength")

val PROG_05_GREYSKULL_LP = buildProgram("Greyskull LP", 4, "5", listOf(
  SessionTemplate(1, "Day 1 · Day A", "Quads / Hamstrings / Glutes", listOf(
  ex("Bench Press", 3, "5/5/AMRAP", "Last set AMRAP (min 5 reps)", idx = 0),
  ex("Squat", 3, "5/5/AMRAP", "", idx = 1),
  ex("Chin-Ups", 3, "AMRAP", "Add weight when 10+ reps each set", idx = 2))),
  SessionTemplate(2, "Day 2 · Day B", "Chest / Shoulders / Triceps", listOf(
  ex("Press (OHP)", 3, "5/5/AMRAP", "", idx = 0),
  ex("Deadlift", 1, "AMRAP", "Min 5 reps; stop if form breaks", idx = 1),
  ex("Barbell Row", 3, "5", "Or chin-up variant", idx = 2))),
), "Strength")

val PROG_06_GZCLP = buildProgram("GZCLP", 12, "1", listOf(
  SessionTemplate(1, "Day 1 · Day 1 - Squat / Press", "Quads / Glutes / Lower Back", listOf(
  ex("Squat (T1)", 5, "3+ (AMRAP last set)", "Start at ~85% estimated 5RM", idx = 0),
  ex("Overhead Press (T2)", 4, "6+", "", idx = 1),
  ex("Lat Pulldown (T3)", 3, "10 - 15", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Day 2 - Bench / Deadlift", "Posterior Chain", listOf(
  ex("Bench Press (T1)", 5, "3+", "", idx = 0),
  ex("Deadlift (T2)", 4, "6+", "", idx = 1),
  ex("DB Row (T3)", 3, "10 - 15", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Day 3 - OHP / Squat", "Quads / Glutes / Lower Back", listOf(
  ex("OHP (T1)", 5, "3+", "", idx = 0),
  ex("Squat (T2)", 4, "6+", "", idx = 1),
  ex("Cable Row (T3)", 3, "10 - 15", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Day 4 - Deadlift / Bench", "Posterior Chain", listOf(
  ex("Deadlift (T1)", 5, "3+", "", idx = 0),
  ex("Bench Press (T2)", 4, "6+", "", idx = 1),
  ex("Tricep Pushdown (T3)", 3, "10 - 15", "", idx = 2))),
), "Strength")

val PROG_07_PHRAKS_GREYSKULL_LP = buildProgram("Phrak's Greyskull LP", 4, "2", listOf(
  SessionTemplate(1, "Day 1 · Day A", "Quads / Hamstrings / Glutes", listOf(
  ex("Bench Press", 3, "5/5/AMRAP", "AMRAP last set", idx = 0),
  ex("Squat", 3, "5/5/AMRAP", "", idx = 1),
  ex("Barbell Row", 3, "5", "Add weight with LP", idx = 2),
  ex("Chin-Ups", 3, "AMRAP", "", idx = 3),
  ex("Cable Curl", 2, "12", "Optional", idx = 4))),
  SessionTemplate(2, "Day 2 · Day B", "Chest / Shoulders / Triceps", listOf(
  ex("Overhead Press", 3, "5/5/AMRAP", "", idx = 0),
  ex("Deadlift", 1, "AMRAP", "Min 5", idx = 1),
  ex("Barbell Row", 3, "5", "", idx = 2),
  ex("Chin-Ups", 3, "AMRAP", "", idx = 3))),
), "Strength")

val PROG_08_FIERCE_5 = buildProgram("Fierce 5", 4, "3", listOf(
  SessionTemplate(1, "Day 1 · Workout A", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5", "+2.5 kg per session", idx = 0),
  ex("Bench Press", 3, "5", "+1.25 kg", idx = 1),
  ex("Barbell Row", 3, "5", "+1.25 kg", idx = 2),
  ex("Overhead Press", 3, "5", "+1.25 kg", idx = 3),
  ex("Romanian Deadlift", 3, "8", "", idx = 4),
  ex("Chin-Up / Pulldown", 3, "8", "", idx = 5),
  ex("Incline DB Press", 3, "8", "", idx = 6),
  ex("Lateral Raises", 3, "10", "", idx = 7))),
  SessionTemplate(2, "Day 2 · Workout B", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5", "", idx = 0),
  ex("Deadlift", 2, "5", "+2.5 kg per session", idx = 1),
  ex("Barbell Row", 3, "5", "", idx = 2),
  ex("Overhead Press", 3, "5", "", idx = 3),
  ex("Bench Press", 3, "5", "", idx = 4),
  ex("Dips", 3, "8", "", idx = 5),
  ex("Leg Press", 3, "10", "", idx = 6),
  ex("Leg Curl", 3, "10", "", idx = 7))),
), "Strength")

val PROG_10_NSUNS_531_5DAY = buildProgram("nSuns 5/3/1 (5-Day)", 12, "5", listOf(
  SessionTemplate(1, "Day 1 · Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 1, "5 @ 75%TM", "", idx = 0),
  ex("Squat", 1, "3 @ 85%TM", "", idx = 1),
  ex("Squat", 1, "1+ @ 95%TM", "AMRAP  -  determines next week TM", idx = 2),
  ex("Squat (back-off)", 4, "3,3,3,3 @ 90/85/80/75%TM", "Descending back-off", idx = 3),
  ex("Sumo DL", 6, "6,5,3,5,5,5", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Tuesday - OHP + Bench Accessory", "Chest / Triceps / Shoulders", listOf(
  ex("Overhead Press", 1, "5 @ 75%TM", "", idx = 0),
  ex("Overhead Press", 1, "3 @ 85%TM", "", idx = 1),
  ex("Overhead Press", 1, "1+ @ 95%TM", "AMRAP", idx = 2),
  ex("Bench Press (acc)", 6, "6,5,3,5,5,5", "Accessory bench back-offs", idx = 3),
  ex("DB Row", 3, "10 - 15", "", idx = 4))),
  SessionTemplate(3, "Day 3 · Wednesday - Deadlift", "Posterior Chain", listOf(
  ex("Deadlift", 1, "5 @ 75%TM", "", idx = 0),
  ex("Deadlift", 1, "3 @ 85%TM", "", idx = 1),
  ex("Deadlift", 1, "1+ @ 95%TM", "AMRAP", idx = 2),
  ex("Deadlift (acc)", 4, "3,3,3,3 desc.", "", idx = 3),
  ex("Front Squat", 3, "5", "Optional accessory", idx = 4))),
  SessionTemplate(4, "Day 4 · Thursday - Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 1, "5 @ 75%TM", "", idx = 0),
  ex("Bench Press", 1, "3 @ 85%TM", "", idx = 1),
  ex("Bench Press", 1, "1+ @ 95%TM", "AMRAP", idx = 2),
  ex("Bench (acc)", 6, "6,5,3,5,5,5", "", idx = 3),
  ex("OHP (acc)", 3, "10", "", idx = 4))),
  SessionTemplate(5, "Day 5 · Squat + Deadlift Acc", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 1, "5 @ 75%TM", "Lighter Fri squat", idx = 0),
  ex("Squat", 1, "3 @ 85%TM", "", idx = 1),
  ex("Squat", 1, "1+ @ 95%TM", "", idx = 2),
  ex("Deadlift", 3, "5", "Accessory", idx = 3))),
), "Strength")

val PROG_11_531_BORING_BUT_BIG = buildProgram("5/3/1 Boring But Big", 4, "1", listOf(
  SessionTemplate(1, "Day 1 · Squat Day", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "5/3/1+", "65/75/85%TM (AMRAP last set)", idx = 0),
  ex("Squat (BBB)", 5, "10", "@ 50%TM", idx = 1),
  ex("Pull-Ups", 5, "10", "", idx = 2),
  ex("Leg Raises", 5, "15", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Bench Day", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 3, "5/3/1+", "", idx = 0),
  ex("Bench Press (BBB)", 5, "10", "@ 50%TM", idx = 1),
  ex("DB Row", 5, "10", "", idx = 2),
  ex("Curls / Tri Ext", 5, "10", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Deadlift Day", "Posterior Chain", listOf(
  ex("Deadlift", 3, "5/3/1+", "", idx = 0),
  ex("Deadlift (BBB)", 5, "10", "@ 50%TM", idx = 1),
  ex("Leg Press", 5, "10", "", idx = 2),
  ex("Leg Curl", 5, "10", "", idx = 3))),
  SessionTemplate(4, "Day 4 · OHP Day", "Shoulders / Triceps / Upper Back", listOf(
  ex("Overhead Press", 3, "5/3/1+", "", idx = 0),
  ex("Overhead Press (BBB)", 5, "10", "@ 50%TM", idx = 1),
  ex("Chin-Ups", 5, "10", "", idx = 2),
  ex("Face Pulls", 5, "20", "", idx = 3))),
), "Strength")

val PROG_12_531_BUILDING_THE_MONOLITH = buildProgram("5/3/1 Building the Monolith", 6, "2", listOf(
  SessionTemplate(1, "Day 1 · Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "5/3/1+", "65/75/85%TM", idx = 0),
  ex("Squat (BBB)", 5, "10", "@ 50 - 70%TM", idx = 1),
  ex("Pull-Ups", 5, "10 (50 total)", "Throughout session", idx = 2))),
  SessionTemplate(2, "Day 2 · Wednesday - Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 3, "5/3/1+", "", idx = 0),
  ex("Bench Press (BBB)", 5, "10", "", idx = 1),
  ex("Dips", 5, "10 (50 total)", "Bodyweight or weighted", idx = 2))),
  SessionTemplate(3, "Day 3 · Deadlift", "Posterior Chain", listOf(
  ex("Deadlift", 3, "5/3/1+", "", idx = 0),
  ex("Deadlift (BBB)", 5, "10", "", idx = 1),
  ex("Barbell Row", 5, "10 (50 total)", "", idx = 2),
  ex("OHP (optional)", 3, "10", "", idx = 3))),
), "Strength")

val PROG_13_TEXAS_METHOD = buildProgram("Texas Method", 4, "3", listOf(
  SessionTemplate(1, "Day 1 · Volume Day", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 5, "5", "~90% of 5RM", idx = 0),
  ex("Bench/Press", 5, "5", "~90% of 5RM (alternate weekly)", idx = 1),
  ex("Deadlift", 1, "5", "~90% of 5RM", idx = 2))),
  SessionTemplate(2, "Day 2 · Wednesday - Recovery Day", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 2, "5", "~80% of Volume Day weight", idx = 0),
  ex("Overhead Press", 3, "5", "~80% (alt. Bench/Press)", idx = 1),
  ex("Pull-Ups / Rows", 3, "8", "", idx = 2),
  ex("Back Extensions", 3, "10", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Intensity Day", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 1, "5 (PR)", "New 5RM - heavier than Monday", idx = 0),
  ex("Bench/Press", 1, "5 (PR)", "New 5RM", idx = 1),
  ex("Power Clean", 5, "3", "Or Deadlift variation", idx = 2))),
), "Strength")

val PROG_14_MADCOW_5X5 = buildProgram("Madcow 5x5", 4, "4", listOf(
  SessionTemplate(1, "Day 1 · Heavy", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 5, "5 (ramped)", "Set 1 - 4 ramp up; Set 5 = top set", idx = 0),
  ex("Bench Press", 5, "5 (ramped)", "", idx = 1),
  ex("Barbell Row", 5, "5 (ramped)", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Wednesday - Light", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "5", "~80% of Mon top set", idx = 0),
  ex("Overhead Press", 4, "5", "~80%", idx = 1),
  ex("Deadlift", 4, "5", "~80%", idx = 2))),
  SessionTemplate(3, "Day 3 · Heavy+", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 5, "5 (ramped)+1 heavy triple", "Top set heavier than Mon", idx = 0),
  ex("Bench Press", 5, "5 (ramped)", "", idx = 1),
  ex("Barbell Row", 5, "5 (ramped)", "", idx = 2),
  ex("Deadlift", 2, "5", "Optional", idx = 3))),
), "Strength")

val PROG_15_CANDITO_6WEEK_STRENGTH = buildProgram("Candito 6-Week Strength", 6, "5", listOf(
  SessionTemplate(1, "Day 1 · Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "6 - 10", "Week 1 - 2 hypertrophy block, moderate weight", idx = 0),
  ex("Pause Squat", 2, "3", "", idx = 1),
  ex("Leg Press", 3, "8 - 12", "", idx = 2),
  ex("Leg Curl", 3, "10 - 12", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 3, "6 - 10", "", idx = 0),
  ex("Close-Grip Bench", 3, "6 - 8", "", idx = 1),
  ex("DB Incline Press", 3, "10", "", idx = 2),
  ex("Cable Row", 3, "10 - 12", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Deadlift", "Posterior Chain", listOf(
  ex("Deadlift", 3, "6 - 10", "", idx = 0),
  ex("Romanian DL", 3, "8 - 10", "", idx = 1),
  ex("Front Squat", 2, "5", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Bench Variation", "Chest / Triceps / Shoulders", listOf(
  ex("Incline Bench", 3, "6 - 10", "", idx = 0),
  ex("OHP", 3, "8 - 10", "", idx = 1),
  ex("Pull-Ups", 3, "AMRAP", "", idx = 2),
  ex("Tricep Dips", 3, "10", "", idx = 3))),
), "Strength")

val PROG_16_CANDITO_LINEAR_PROGRESSION = buildProgram("Candito Linear Progression", 4, "1", listOf(
  SessionTemplate(1, "Day 1 · Lower A", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5", "Add weight each session", idx = 0),
  ex("Romanian DL", 3, "8", "", idx = 1),
  ex("Leg Press", 3, "10", "", idx = 2),
  ex("Leg Curl", 3, "10", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Upper A", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 3, "5", "", idx = 0),
  ex("Barbell Row", 3, "5", "", idx = 1),
  ex("DB OHP", 3, "10", "", idx = 2),
  ex("Tricep Ext.", 3, "12", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Lower B", "Quads / Hamstrings / Glutes", listOf(
  ex("Deadlift", 3, "5", "Add weight each session", idx = 0),
  ex("Squat (light)", 3, "8", "50 - 60% of squat", idx = 1),
  ex("Leg Curl", 3, "10", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Upper B", "Chest / Back / Shoulders", listOf(
  ex("Overhead Press", 3, "5", "", idx = 0),
  ex("Weighted Chin-Ups", 3, "5", "", idx = 1),
  ex("Incline DB", 3, "10", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3))),
), "Strength")

val PROG_17_GZCL_THE_RIPPLER = buildProgram("GZCL: The Rippler", 10, "2", listOf(
  SessionTemplate(1, "Day 1 · Day 1 - Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat (T1)", 5, "4", "First wave: 5×4 sets", idx = 0),
  ex("Bench Press (T2)", 3, "8", "", idx = 1),
  ex("Lat Pulldown (T3)", 3, "15", "", idx = 2),
  ex("Tricep Pushdown (T3)", 3, "15", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Day 2 - Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press (T1)", 5, "4", "", idx = 0),
  ex("Deadlift (T2)", 3, "8", "", idx = 1),
  ex("Incline DB Press (T3)", 3, "12", "", idx = 2),
  ex("DB Row (T3)", 3, "12", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Day 3 - OHP", "Shoulders / Triceps / Upper Back", listOf(
  ex("OHP (T1)", 5, "4", "", idx = 0),
  ex("Squat (T2)", 3, "8", "", idx = 1),
  ex("Cable Row (T3)", 3, "15", "", idx = 2),
  ex("Lateral Raises (T3)", 3, "15", "", idx = 3))),
  SessionTemplate(4, "Day 4 · Day 4 - Deadlift", "Posterior Chain", listOf(
  ex("Deadlift (T1)", 5, "4", "", idx = 0),
  ex("OHP (T2)", 3, "8", "", idx = 1),
  ex("Leg Press (T3)", 3, "12", "", idx = 2),
  ex("Curl (T3)", 3, "15", "", idx = 3))),
), "Strength")

val PROG_18_GZCL_JACKED_AND_TAN_20 = buildProgram("GZCL: Jacked and Tan 2.0", 10, "3", listOf(
  SessionTemplate(1, "Day 1 · Day 1 - Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat (T1)", 3, "3+ AMRAP", "Cluster sets; rest-pause", idx = 0),
  ex("RDL (T2)", 3, "10", "", idx = 1),
  ex("Leg Press (T3)", 4, "15 - 20", "", idx = 2),
  ex("Leg Curl (T3)", 4, "15 - 20", "", idx = 3),
  ex("Ab Work", 3, "15", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Day 2 - Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press (T1)", 3, "3+ AMRAP", "", idx = 0),
  ex("Incline DB Press (T2)", 3, "10", "", idx = 1),
  ex("Cable Fly (T3)", 4, "15 - 20", "", idx = 2),
  ex("Tricep Pushdown (T3)", 4, "15 - 20", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Day 3 - OHP", "Shoulders / Triceps / Upper Back", listOf(
  ex("OHP (T1)", 3, "3+ AMRAP", "", idx = 0),
  ex("Bent-Over Row (T2)", 3, "10", "", idx = 1),
  ex("Face Pulls (T3)", 4, "15 - 20", "", idx = 2),
  ex("Lateral Raises (T3)", 4, "15 - 20", "", idx = 3))),
  SessionTemplate(4, "Day 4 · Day 4 - Deadlift", "Posterior Chain", listOf(
  ex("Deadlift (T1)", 3, "3+ AMRAP", "", idx = 0),
  ex("Front Squat (T2)", 3, "10", "", idx = 1),
  ex("Lat Pulldown (T3)", 4, "15 - 20", "", idx = 2),
  ex("Seated Row (T3)", 4, "15 - 20", "", idx = 3))),
), "Powerbuilding")

val PROG_19_GREG_NUCKOLS_28_PROGRAMS = buildProgram("Greg Nuckols 28 Programs", 4, "4", listOf(
  SessionTemplate(1, "Day 1 · Heavy Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "3 @ ~87% 1RM", "Primary: heavy triples", idx = 0),
  ex("Bench Press", 3, "5 @ ~80%", "Accessory main lift", idx = 1),
  ex("Pull-Ups", 3, "8", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Wednesday - Volume Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 4, "6 @ ~75% 1RM", "Volume accumulation", idx = 0),
  ex("OHP", 3, "8 @ ~70%", "", idx = 1),
  ex("Barbell Row", 3, "8", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Speed/Rep Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 5, "2 @ ~80% 1RM", "Speed / technique focus", idx = 0),
  ex("Bench or OHP", 4, "5 @ ~80%", "", idx = 1),
  ex("Chin-Ups", 3, "8", "", idx = 2))),
), "Strength")

val PROG_20_SHEIKO_29_INTERMEDIATE = buildProgram("Sheiko #29 (Intermediate)", 4, "5", listOf(
  SessionTemplate(1, "Day 1 · Monday", "Quads / Hamstrings / Glutes", listOf(
  ex("Bench Press", 4, "5 @ 70%", "", idx = 0),
  ex("Squat", 5, "4 @ 70%", "", idx = 1),
  ex("Bench Press", 4, "5 @ 70%", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Wednesday", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 5, "5 @ 70%", "", idx = 0),
  ex("Deadlift", 5, "4 @ 70%", "", idx = 1),
  ex("Squat", 3, "5 @ 65%", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Friday", "Quads / Hamstrings / Glutes", listOf(
  ex("Bench Press", 5, "4 @ 75%", "", idx = 0),
  ex("Squat", 5, "4 @ 75%", "", idx = 1),
  ex("Bench Press", 4, "5 @ 65%", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Saturday", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "4 @ 75%", "", idx = 0),
  ex("Deadlift", 4, "4 @ 75%", "", idx = 1))),
), "Powerlifting")

val PROG_21_JUGGERNAUT_METHOD_BASE = buildProgram("Juggernaut Method Base", 16, "1", listOf(
  SessionTemplate(1, "Day 1 · Squat (10s Wave Wk1)", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "10 @ 60%", "", idx = 0),
  ex("Squat (AMRAP)", 1, "AMRAP @ 60%", "Aim 15+ reps", idx = 1),
  ex("Front Squat", 3, "6", "", idx = 2),
  ex("Leg Press", 3, "10", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Bench (10s Wave)", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 3, "10 @ 60%", "", idx = 0),
  ex("Bench (AMRAP)", 1, "AMRAP", "", idx = 1),
  ex("Close-Grip Bench", 3, "8", "", idx = 2),
  ex("DB Row", 3, "10", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Deadlift", "Posterior Chain", listOf(
  ex("Deadlift", 3, "10 @ 60%", "", idx = 0),
  ex("DL (AMRAP)", 1, "AMRAP", "", idx = 1),
  ex("RDL", 3, "8", "", idx = 2),
  ex("Lat Pulldown", 3, "10", "", idx = 3))),
  SessionTemplate(4, "Day 4 · OHP", "Shoulders / Triceps / Upper Back", listOf(
  ex("OHP", 3, "10 @ 60%", "", idx = 0),
  ex("OHP (AMRAP)", 1, "AMRAP", "", idx = 1),
  ex("Push Press", 3, "5", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3))),
), "Strength")

val PROG_22_CUBE_METHOD = buildProgram("Cube Method", 10, "2", listOf(
  SessionTemplate(1, "Day 1 · Heavy Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 1, "1 heavy (PR attempt)", "Work to heavy single", idx = 0),
  ex("Box Squat", 5, "5", "~60 - 70%", idx = 1),
  ex("Leg Press", 4, "10", "", idx = 2),
  ex("Leg Curl", 4, "10", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Wednesday - Rep Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 2, "AMRAP sets", "Rep focused  -  multiple max-rep sets", idx = 0),
  ex("DB Incline", 4, "12", "", idx = 1),
  ex("Tricep Dips", 4, "15", "", idx = 2),
  ex("Lat Raise", 4, "15", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Explosive Deadlift", "Posterior Chain", listOf(
  ex("Deadlift", 8, "2 speed reps @ ~60%", "Fast / explosive focus", idx = 0),
  ex("RDL", 4, "8", "", idx = 1),
  ex("Good Morning", 3, "10", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Saturday - Bench Volume", "Chest / Triceps / Shoulders", listOf(
  ex("Floor Press", 4, "5", "Accessory bench variation", idx = 0),
  ex("Rows", 4, "10", "", idx = 1),
  ex("Curls", 3, "15", "", idx = 2))),
), "Powerlifting")

val PROG_23_SBS_BEGINNERINTERMEDIATE = buildProgram("SBS Beginner/Intermediate", 12, "3", listOf(
  SessionTemplate(1, "Day 1 · Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "4 @ RPE 8", "RPE 8 = ~2 reps in reserve", idx = 0),
  ex("Romanian DL", 3, "8", "", idx = 1),
  ex("Leg Press", 3, "10", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Tuesday - Upper", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 4, "4 @ RPE 8", "", idx = 0),
  ex("Barbell Row", 4, "4", "", idx = 1),
  ex("OHP", 3, "6 @ RPE 7", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Thursday - Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Deadlift", 3, "8 @ RPE 7", "", idx = 0),
  ex("Squat", 3, "10 @ RPE 6", "", idx = 1),
  ex("Leg Curl", 3, "12", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Upper", "Chest / Back / Shoulders", listOf(
  ex("Incline Bench", 3, "10 @ RPE 7", "", idx = 0),
  ex("Lat Pulldown", 3, "10", "", idx = 1),
  ex("DB OHP", 3, "12", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3))),
), "Powerbuilding")

val PROG_24_BARBELL_MEDICINE_BRIDGE = buildProgram("Barbell Medicine Bridge", 4, "4", listOf(
  SessionTemplate(1, "Day 1 · Squat / Press", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 3, "5 @ RPE 8", "", idx = 0),
  ex("Overhead Press", 3, "5 @ RPE 8", "", idx = 1),
  ex("Romanian DL", 2, "8", "", idx = 2),
  ex("Pull-Ups", 3, "10", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Bench / Row", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 3, "5 @ RPE 8", "", idx = 0),
  ex("Barbell Row", 3, "5 @ RPE 8", "", idx = 1),
  ex("DB Incline", 2, "12", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Thursday - DL / OHP", "Posterior Chain", listOf(
  ex("Deadlift", 3, "5 @ RPE 8", "", idx = 0),
  ex("OHP", 3, "5 @ RPE 8", "", idx = 1),
  ex("Squat (light)", 2, "10", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Bench / Row", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 3, "5 @ RPE 8", "", idx = 0),
  ex("Barbell Row", 3, "5 @ RPE 8", "", idx = 1),
  ex("Face Pulls", 3, "15", "", idx = 2))),
), "Strength")

val PROG_25_GZCLP_4DAY = buildProgram("GZCLP 4-Day", 12, "5", listOf(
  SessionTemplate(1, "Day 1 · Day 1 - Squat / Bench", "Quads / Glutes / Lower Back", listOf(
  ex("Squat (T1)", 5, "3+", "Linear progress; AMRAP last set", idx = 0),
  ex("Bench Press (T2)", 4, "6+", "", idx = 1),
  ex("Lat Pulldown (T3)", 3, "10 - 15", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Day 2 - OHP / Deadlift", "Posterior Chain", listOf(
  ex("OHP (T1)", 5, "3+", "", idx = 0),
  ex("Deadlift (T2)", 4, "6+", "", idx = 1),
  ex("DB Row (T3)", 3, "10 - 15", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Day 3 - Bench / Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Bench Press (T1)", 5, "3+", "", idx = 0),
  ex("Squat (T2)", 4, "6+", "", idx = 1),
  ex("Cable Row (T3)", 3, "10 - 15", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Day 4 - Deadlift / OHP", "Posterior Chain", listOf(
  ex("Deadlift (T1)", 5, "3+", "", idx = 0),
  ex("OHP (T2)", 4, "6+", "", idx = 1),
  ex("Tricep Pushdown (T3)", 3, "10 - 15", "", idx = 2))),
), "Strength")

val PROG_26_REDDIT_PPL_METALLICADPA = buildProgram("Reddit PPL (Metallicadpa)", 4, "1", listOf(
  SessionTemplate(1, "Day 1 · Push", "Chest / Shoulders / Triceps", listOf(
  ex("Bench Press", 4, "5, 5, 5, 5+", "Linear progression AMRAP last set", idx = 0),
  ex("OHP", 3, "8, 8, 8+", "", idx = 1),
  ex("Incline DB Press", 3, "10", "", idx = 2),
  ex("Tricep Pushdown", 3, "12", "", idx = 3),
  ex("Lateral Raises", 3, "15", "", idx = 4),
  ex("Overhead Tri Ext", 3, "15", "", idx = 5))),
  SessionTemplate(2, "Day 2 · Tuesday - Pull", "Back / Biceps / Rear Delts", listOf(
  ex("Deadlift", 1, "5+", "AMRAP", idx = 0),
  ex("Barbell Row", 3, "8, 8, 8+", "", idx = 1),
  ex("Lat Pulldown", 3, "10", "", idx = 2),
  ex("Cable Row", 3, "10", "", idx = 3),
  ex("Face Pulls", 3, "15", "", idx = 4),
  ex("Hammer Curls", 3, "12", "", idx = 5),
  ex("Barbell Curl", 3, "12", "", idx = 6))),
  SessionTemplate(3, "Day 3 · Wednesday - Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "5, 5, 5, 5+", "", idx = 0),
  ex("Romanian DL", 3, "8", "", idx = 1),
  ex("Leg Press", 3, "10", "", idx = 2),
  ex("Leg Curl", 3, "10", "", idx = 3),
  ex("Calf Raises", 4, "15", "", idx = 4))),
), "Hypertrophy")

val PROG_27_PHUL = buildProgram("PHUL", 4, "2", listOf(
  SessionTemplate(1, "Day 1 · Upper Power", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 3, "3 - 5", "Heavy, compound strength focus", idx = 0),
  ex("Incline DB Press", 3, "6 - 10", "", idx = 1),
  ex("Bent-Over Row", 3, "3 - 5", "", idx = 2),
  ex("Lat Pulldown", 3, "6 - 10", "", idx = 3),
  ex("OHP", 2, "5 - 8", "", idx = 4),
  ex("Barbell Curl", 3, "6 - 10", "", idx = 5),
  ex("Skull Crushers", 3, "6 - 10", "", idx = 6))),
  SessionTemplate(2, "Day 2 · Tuesday - Lower Power", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "3 - 5", "", idx = 0),
  ex("Deadlift", 3, "3 - 5", "", idx = 1),
  ex("Leg Press", 3, "10 - 15", "", idx = 2),
  ex("Leg Curl", 3, "6 - 10", "", idx = 3),
  ex("Calf Raises", 4, "6 - 10", "", idx = 4))),
  SessionTemplate(3, "Day 3 · Thursday - Upper Hypertrophy", "Chest / Back / Shoulders", listOf(
  ex("Incline Bench", 3, "8 - 12", "", idx = 0),
  ex("Flat DB Press", 3, "8 - 12", "", idx = 1),
  ex("Cable Row", 3, "8 - 12", "", idx = 2),
  ex("One-Arm DB Row", 3, "8 - 12", "", idx = 3),
  ex("DB Shoulder Press", 3, "8 - 12", "", idx = 4),
  ex("Lateral Raises", 3, "8 - 12", "", idx = 5),
  ex("Tricep Pushdown", 3, "8 - 12", "", idx = 6),
  ex("EZ Bar Curl", 3, "8 - 12", "", idx = 7))),
  SessionTemplate(4, "Day 4 · Lower Hypertrophy", "Quads / Hamstrings / Glutes", listOf(
  ex("Front Squat", 3, "8 - 12", "", idx = 0),
  ex("Barbell Lunge", 3, "8 - 12", "", idx = 1),
  ex("Leg Extension", 3, "10 - 15", "", idx = 2),
  ex("Leg Curl", 3, "10 - 15", "", idx = 3),
  ex("Seated Calf Raise", 3, "8 - 12", "", idx = 4),
  ex("Calf Press", 3, "8 - 12", "", idx = 5))),
), "Powerbuilding")

val PROG_28_PHAT = buildProgram("PHAT", 4, "3", listOf(
  SessionTemplate(1, "Day 1 · Upper Power", "Chest / Back / Shoulders", listOf(
  ex("Pendlay Row", 3, "3 - 5", "", idx = 0),
  ex("Pull-Ups", 2, "6 - 10", "", idx = 1),
  ex("Rack Chin", 2, "6 - 10", "", idx = 2),
  ex("Bench Press", 3, "3 - 5", "", idx = 3),
  ex("DB Bench", 2, "6 - 10", "", idx = 4),
  ex("OHP", 3, "3 - 5", "", idx = 5),
  ex("Barbell Curl", 3, "6 - 10", "", idx = 6),
  ex("Skull Crushers", 3, "6 - 10", "", idx = 7))),
  SessionTemplate(2, "Day 2 · Tuesday - Lower Power", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "3 - 5", "", idx = 0),
  ex("Hack Squat", 2, "6 - 10", "", idx = 1),
  ex("Leg Press", 2, "6 - 10", "", idx = 2),
  ex("Stiff-Leg DL", 3, "5 - 8", "", idx = 3),
  ex("Leg Curl", 2, "6 - 10", "", idx = 4))),
  SessionTemplate(3, "Day 3 · Thursday - Back/Shoulders Hyp.", "Lats / Upper Back / Biceps", listOf(
  ex("Pendlay Row (speed)", 6, "3 @ 65%", "Speed sets ~60 - 70% of power day", idx = 0),
  ex("Rack Chin", 3, "8 - 12", "", idx = 1),
  ex("Cable Row", 3, "8 - 12", "", idx = 2),
  ex("Lat Pulldown", 3, "8 - 12", "", idx = 3),
  ex("DB Rear Delt Raise", 3, "12 - 15", "", idx = 4),
  ex("OHP (speed)", 6, "3 @ 65%", "", idx = 5),
  ex("DB Lateral Raise", 3, "12 - 15", "", idx = 6))),
  SessionTemplate(4, "Day 4 · Lower Hyp.", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat (speed)", 6, "3 @ 65%", "", idx = 0),
  ex("Hack Squat", 3, "8 - 12", "", idx = 1),
  ex("Leg Press", 3, "12 - 15", "", idx = 2),
  ex("Leg Ext.", 3, "15 - 20", "", idx = 3),
  ex("SLDL", 3, "8 - 12", "", idx = 4),
  ex("Leg Curl", 3, "12 - 15", "", idx = 5),
  ex("Seated Calf Raise", 4, "10", "", idx = 6))),
  SessionTemplate(5, "Day 5 · Saturday - Chest/Arms Hyp.", "Chest / Front Delts / Triceps", listOf(
  ex("Bench (speed)", 6, "3 @ 65%", "", idx = 0),
  ex("Incline DB Press", 3, "8 - 12", "", idx = 1),
  ex("Fly", 3, "12 - 15", "", idx = 2),
  ex("Barbell Curl", 3, "8 - 12", "", idx = 3),
  ex("Cable Curl", 3, "12 - 15", "", idx = 4),
  ex("Tricep Pushdown", 3, "8 - 12", "", idx = 5),
  ex("Overhead Ext.", 3, "12 - 15", "", idx = 6))),
), "Powerbuilding")

val PROG_29_ARNOLD_SPLIT = buildProgram("Arnold Split", 4, "4", listOf(
  SessionTemplate(1, "Day 1 · Mon / Thu - Chest + Back", "Chest / Front Delts / Triceps", listOf(
  ex("Bench Press", 4, "8 - 10", "", idx = 0),
  ex("Incline DB Press", 3, "10", "", idx = 1),
  ex("Flat DB Fly", 3, "12", "", idx = 2),
  ex("Weighted Pull-Ups", 4, "8", "", idx = 3),
  ex("Barbell Row", 4, "8 - 10", "", idx = 4),
  ex("Seated Cable Row", 3, "10", "", idx = 5),
  ex("Straight-Arm Pulldown", 3, "12", "", idx = 6))),
  SessionTemplate(2, "Day 2 · Tue / Fri - Shoulders + Arms", "Shoulders / Rear Delts / Traps", listOf(
  ex("Arnold Press", 4, "10", "", idx = 0),
  ex("Lateral Raises", 3, "12", "", idx = 1),
  ex("Front Raises", 3, "12", "", idx = 2),
  ex("Barbell Curl", 4, "10", "", idx = 3),
  ex("Incline DB Curl", 3, "12", "", idx = 4),
  ex("Close-Grip Bench", 4, "10", "", idx = 5),
  ex("Overhead Tri Ext.", 3, "12", "", idx = 6))),
  SessionTemplate(3, "Day 3 · Wed / Sat - Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "8 - 10", "", idx = 0),
  ex("Leg Press", 3, "10", "", idx = 1),
  ex("Leg Extension", 3, "12", "", idx = 2),
  ex("Leg Curl", 3, "12", "", idx = 3),
  ex("Standing Calf Raise", 4, "15", "", idx = 4))),
), "Hypertrophy")

val PROG_30_PPLUL_5DAY = buildProgram("PPLUL (5-Day)", 4, "5", listOf(
  SessionTemplate(1, "Day 1 · Push", "Chest / Shoulders / Triceps", listOf(
  ex("Bench Press", 4, "5", "Linear progression", idx = 0),
  ex("OHP", 3, "8", "", idx = 1),
  ex("Incline DB Press", 3, "10", "", idx = 2),
  ex("Lateral Raises", 3, "15", "", idx = 3),
  ex("Tricep Pushdown", 3, "12", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Tuesday - Pull", "Back / Biceps / Rear Delts", listOf(
  ex("Deadlift", 3, "5", "", idx = 0),
  ex("Barbell Row", 3, "8", "", idx = 1),
  ex("Lat Pulldown", 3, "10", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3),
  ex("Barbell Curl", 3, "12", "", idx = 4))),
  SessionTemplate(3, "Day 3 · Wednesday - Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "5", "", idx = 0),
  ex("Romanian DL", 3, "8", "", idx = 1),
  ex("Leg Press", 3, "10", "", idx = 2),
  ex("Leg Curl", 3, "12", "", idx = 3),
  ex("Calf Raises", 4, "15", "", idx = 4))),
  SessionTemplate(4, "Day 4 · Thursday - Upper", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 3, "8", "", idx = 0),
  ex("Cable Row", 3, "8", "", idx = 1),
  ex("DB Shoulder Press", 3, "10", "", idx = 2),
  ex("Chin-Ups", 3, "AMRAP", "", idx = 3))),
  SessionTemplate(5, "Day 5 · Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat (light)", 3, "8", "", idx = 0),
  ex("Deadlift (light)", 3, "5", "", idx = 1),
  ex("Leg Ext.", 3, "12", "", idx = 2),
  ex("Leg Curl", 3, "12", "", idx = 3))),
), "Hypertrophy")

val PROG_31_KINOBODY_GREEK_GOD = buildProgram("Kinobody Greek God", 4, "1", listOf(
  SessionTemplate(1, "Day 1 · Chest / Shoulder / Triceps", "Chest / Front Delts / Triceps", listOf(
  ex("Weighted Pull-Ups (RPT)", 3, "6, 8, 10", "Drop 10% each set", idx = 0),
  ex("Incline DB Press (RPT)", 3, "6, 8, 10", "RPT - heaviest first", idx = 1),
  ex("Arnold Press", 3, "8, 10, 12", "", idx = 2),
  ex("Lateral Raises", 3, "12", "", idx = 3),
  ex("Skull Crushers", 3, "8 - 10", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Wednesday - Back / Biceps", "Lats / Upper Back / Biceps", listOf(
  ex("Weighted Pull-Ups (RPT)", 3, "5, 6, 8", "RPT", idx = 0),
  ex("Barbell Row (RPT)", 3, "6, 8, 10", "", idx = 1),
  ex("Incline DB Curl", 3, "8, 10, 12", "RPT", idx = 2),
  ex("Hammer Curl", 2, "10", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("Bulgarian Split Squat", 3, "8, 10, 12", "RPT", idx = 0),
  ex("Romanian DL", 3, "8 - 10", "", idx = 1),
  ex("Calf Raises", 4, "15", "", idx = 2),
  ex("Ab Rollout", 3, "10", "", idx = 3))),
), "Hypertrophy")

val PROG_32_REDDIT_BODYWEIGHT_RR = buildProgram("Reddit Bodyweight RR", 4, "2", listOf(
  SessionTemplate(1, "Day 1 · Any 3 days / wk", "Quads / Hamstrings / Glutes", listOf(
  ex("Handstand / Crow Pose", 3, "30-60s", "Skill work: handstand progression", idx = 0),
  ex("Tuck Front Lever / Row", 3, "30s hold", "Pair with vertical pull", idx = 1),
  ex("Tuck Planche / Push-Up", 3, "30s hold", "Pair with vertical push", idx = 2),
  ex("Rows (horizontal pull)", 3, "5 - 8", "Progression: row → archer row", idx = 3),
  ex("Push-Ups (horiz. push)", 3, "5 - 8", "Progression: push-up → dips", idx = 4),
  ex("Pull-Ups (vertical pull)", 3, "5 - 8", "", idx = 5),
  ex("Pike Push-Up (vert. push)", 3, "5 - 8", "→ HSPU", idx = 6),
  ex("Romanian DL / Nordic Curl", 2, "8", "Leg work", idx = 7),
  ex("Squat Progression", 2, "8 - 10", "Air → Pistol squat", idx = 8))),
), "GPP")

val PROG_33_GZCLP_HYPERTROPHY_TEMPLATE = buildProgram("GZCLP Hypertrophy Template", 12, "3", listOf(
  SessionTemplate(1, "Day 1 · Day 1 - Squat Focus", "Quads / Glutes / Lower Back", listOf(
  ex("Squat (T1)", 3, "5 @ ~80%", "", idx = 0),
  ex("Leg Press (T2)", 3, "10 - 12", "", idx = 1),
  ex("Leg Curl (T3)", 4, "12 - 15", "", idx = 2),
  ex("Leg Extension (T3)", 4, "12 - 15", "", idx = 3),
  ex("Calf Raises (T3)", 4, "15 - 20", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Day 2 - Bench / Pull", "Back / Biceps / Rear Delts", listOf(
  ex("Bench Press (T1)", 3, "5 @ ~80%", "", idx = 0),
  ex("Lat Pulldown (T2)", 3, "10 - 12", "", idx = 1),
  ex("DB Incline (T3)", 4, "12 - 15", "", idx = 2),
  ex("Cable Row (T3)", 4, "12 - 15", "", idx = 3),
  ex("Face Pulls", 3, "20", "", idx = 4))),
  SessionTemplate(3, "Day 3 · Day 3 - OHP / Row", "Shoulders / Triceps / Upper Back", listOf(
  ex("OHP (T1)", 3, "5 @ ~80%", "", idx = 0),
  ex("Barbell Row (T2)", 3, "10", "", idx = 1),
  ex("Lateral Raises (T3)", 4, "15 - 20", "", idx = 2),
  ex("Rear Delt Fly (T3)", 4, "15 - 20", "", idx = 3))),
  SessionTemplate(4, "Day 4 · Day 4 - Deadlift", "Posterior Chain", listOf(
  ex("Deadlift (T1)", 3, "5 @ ~80%", "", idx = 0),
  ex("RDL (T2)", 3, "10", "", idx = 1),
  ex("Pull-Ups (T3)", 4, "AMRAP", "", idx = 2),
  ex("Curls (T3)", 3, "12 - 15", "", idx = 3))),
), "Hypertrophy")

val PROG_34_ERIC_HELMS_INT_BODYBUILDING = buildProgram("Eric Helms Int. Bodybuilding", 16, "4", listOf(
  SessionTemplate(1, "Day 1 · Lower Strength", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "4 - 6 @ RPE 8", "", idx = 0),
  ex("Romanian DL", 3, "6 - 8", "", idx = 1),
  ex("Leg Press", 3, "8 - 10", "", idx = 2),
  ex("Leg Curl", 3, "10 - 12", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Upper Strength", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 4, "4 - 6 @ RPE 8", "", idx = 0),
  ex("Barbell Row", 4, "4 - 6", "", idx = 1),
  ex("OHP", 3, "6 - 8", "", idx = 2),
  ex("Pull-Ups", 3, "AMRAP", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Lower Hyp.", "Quads / Hamstrings / Glutes", listOf(
  ex("Deadlift", 3, "6 - 8 @ RPE 8", "", idx = 0),
  ex("Squat (light)", 3, "10 - 12", "", idx = 1),
  ex("Leg Extension", 3, "12 - 15", "", idx = 2),
  ex("Leg Curl", 3, "12 - 15", "", idx = 3))),
  SessionTemplate(4, "Day 4 · Upper Hyp.", "Chest / Back / Shoulders", listOf(
  ex("Incline Bench", 3, "8 - 10 @ RPE 8", "", idx = 0),
  ex("Lat Pulldown", 3, "10 - 12", "", idx = 1),
  ex("DB Shoulder", 3, "10 - 12", "", idx = 2),
  ex("Cable Fly", 3, "12 - 15", "", idx = 3),
  ex("Face Pulls", 3, "15 - 20", "", idx = 4))),
), "Hypertrophy")

val PROG_35_MASS_IMPACT = buildProgram("MASS IMPACT", 12, "5", listOf(
  SessionTemplate(1, "Day 1 · Chest / Triceps", "Chest / Front Delts / Triceps", listOf(
  ex("Flat Bench", 4, "6 - 8", "", idx = 0),
  ex("Incline DB", 4, "8 - 10", "", idx = 1),
  ex("Cable Fly", 3, "12 - 15", "", idx = 2),
  ex("Tricep Dips", 3, "10", "", idx = 3),
  ex("Pushdown", 3, "12", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Tuesday - Back / Biceps", "Lats / Upper Back / Biceps", listOf(
  ex("Deadlift", 3, "5", "", idx = 0),
  ex("Bent-Over Row", 4, "8", "", idx = 1),
  ex("Pull-Ups", 4, "AMRAP", "", idx = 2),
  ex("Cable Row", 3, "12", "", idx = 3),
  ex("Barbell Curl", 3, "10", "", idx = 4),
  ex("Hammer Curl", 3, "12", "", idx = 5))),
  SessionTemplate(3, "Day 3 · Wednesday - Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "6 - 8", "", idx = 0),
  ex("Romanian DL", 3, "8 - 10", "", idx = 1),
  ex("Leg Press", 3, "12", "", idx = 2),
  ex("Leg Curl", 3, "12", "", idx = 3),
  ex("Calf Raises", 4, "15", "", idx = 4))),
  SessionTemplate(4, "Day 4 · Thursday - Shoulders", "Shoulders / Rear Delts / Traps", listOf(
  ex("OHP", 4, "6 - 8", "", idx = 0),
  ex("DB Arnold Press", 3, "10", "", idx = 1),
  ex("Lateral Raises", 4, "15", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3),
  ex("Shrugs", 3, "12", "", idx = 4))),
  SessionTemplate(5, "Day 5 · Full Body", "Full Body", listOf(
  ex("Squat", 3, "5", "", idx = 0),
  ex("Bench Press", 3, "5", "", idx = 1),
  ex("Deadlift", 2, "5", "", idx = 2),
  ex("Pull-Ups", 3, "8", "", idx = 3))),
), "Hypertrophy")

val PROG_36_STRONG_CURVES = buildProgram("Strong Curves", 16, "1", listOf(
  SessionTemplate(1, "Day 1 · Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Barbell Hip Thrust", 3, "10", "Main glute movement", idx = 0),
  ex("Squat", 3, "10", "", idx = 1),
  ex("Romanian DL", 3, "12", "", idx = 2),
  ex("Side-Lying Clam", 3, "15", "", idx = 3),
  ex("Single-Leg Hip Thrust", 3, "10", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Tuesday - Upper", "Chest / Back / Shoulders", listOf(
  ex("DB Bench Press", 3, "10", "", idx = 0),
  ex("One-Arm DB Row", 3, "10", "", idx = 1),
  ex("DB OHP", 3, "12", "", idx = 2),
  ex("Lat Pulldown", 3, "12", "", idx = 3),
  ex("DB Curl", 2, "12", "", idx = 4),
  ex("Tricep Pushdown", 2, "12", "", idx = 5))),
  SessionTemplate(3, "Day 3 · Thursday - Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Barbell Hip Thrust", 3, "10", "", idx = 0),
  ex("Deadlift", 3, "8", "", idx = 1),
  ex("Leg Press", 3, "12", "", idx = 2),
  ex("Leg Curl", 3, "12", "", idx = 3),
  ex("Calf Raises", 3, "15", "", idx = 4))),
  SessionTemplate(4, "Day 4 · Upper", "Chest / Back / Shoulders", listOf(
  ex("Incline DB Press", 3, "10", "", idx = 0),
  ex("Cable Row", 3, "12", "", idx = 1),
  ex("Rear Delt Fly", 3, "15", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3))),
), "Hypertrophy")

val PROG_37_FRANKOMAN_DUMBBELL_PPL = buildProgram("Frankoman Dumbbell PPL", 4, "2", listOf(
  SessionTemplate(1, "Day 1 · Push", "Chest / Shoulders / Triceps", listOf(
  ex("DB Bench Press", 4, "8 - 12", "", idx = 0),
  ex("DB Shoulder Press", 4, "8 - 12", "", idx = 1),
  ex("Incline DB Press", 3, "10", "", idx = 2),
  ex("Lateral Raises", 3, "15", "", idx = 3),
  ex("Tricep Kickback", 3, "12", "", idx = 4),
  ex("Overhead Tri Ext.", 3, "12", "", idx = 5))),
  SessionTemplate(2, "Day 2 · Tuesday - Pull", "Back / Biceps / Rear Delts", listOf(
  ex("DB Row", 4, "8 - 12", "", idx = 0),
  ex("DB Deadlift", 3, "10", "", idx = 1),
  ex("DB Pullover", 3, "12", "", idx = 2),
  ex("Face Pull (band)", 3, "15", "", idx = 3),
  ex("DB Hammer Curl", 3, "12", "", idx = 4),
  ex("DB Curl", 3, "12", "", idx = 5))),
  SessionTemplate(3, "Day 3 · Wednesday - Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("DB Goblet Squat", 4, "10 - 12", "", idx = 0),
  ex("DB Romanian DL", 3, "10", "", idx = 1),
  ex("DB Lunge", 3, "12", "", idx = 2),
  ex("DB Step-Up", 3, "12", "", idx = 3),
  ex("Calf Raises", 4, "15", "", idx = 4))),
), "Hypertrophy")

val PROG_38_DUMBBELL_PPL = buildProgram("Dumbbell PPL", 4, "3", listOf(
  SessionTemplate(1, "Day 1 · Monday/Thursday - Push", "Chest / Shoulders / Triceps", listOf(
  ex("DB Bench Press", 4, "10", "", idx = 0),
  ex("DB Incline Press", 3, "10", "", idx = 1),
  ex("DB Shoulder Press", 3, "10", "", idx = 2),
  ex("Lateral Raises", 3, "15", "", idx = 3),
  ex("Front Raises", 3, "12", "", idx = 4),
  ex("Tricep Ext.", 3, "12", "", idx = 5))),
  SessionTemplate(2, "Day 2 · Tuesday/Friday - Pull", "Back / Biceps / Rear Delts", listOf(
  ex("DB Row", 4, "10", "", idx = 0),
  ex("DB Deadlift", 3, "10", "", idx = 1),
  ex("Rear Delt Fly", 3, "15", "", idx = 2),
  ex("DB Curl", 3, "12", "", idx = 3),
  ex("Hammer Curl", 3, "12", "", idx = 4))),
  SessionTemplate(3, "Day 3 · Wednesday/Saturday - Legs", "Quads / Hamstrings / Glutes", listOf(
  ex("DB Goblet Squat", 4, "10", "", idx = 0),
  ex("DB Romanian DL", 3, "10", "", idx = 1),
  ex("DB Lunge", 3, "12", "", idx = 2),
  ex("Leg Curl (band)", 3, "12", "", idx = 3),
  ex("Calf Raises", 4, "15", "", idx = 4))),
), "Hypertrophy")

val PROG_39_UPPERLOWER_4DAY_SPLIT = buildProgram("Upper/Lower 4-Day Split", 4, "4", listOf(
  SessionTemplate(1, "Day 1 · Upper", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 4, "4 - 6", "", idx = 0),
  ex("Barbell Row", 4, "4 - 6", "", idx = 1),
  ex("OHP", 3, "5 - 8", "", idx = 2),
  ex("Weighted Chin-Up", 3, "5 - 8", "", idx = 3),
  ex("Tricep Dip", 3, "8", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Tuesday - Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "4 - 6", "", idx = 0),
  ex("Deadlift", 3, "4 - 6", "", idx = 1),
  ex("Romanian DL", 3, "8", "", idx = 2),
  ex("Leg Press", 3, "10", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Upper (Hyp.)", "Chest / Back / Shoulders", listOf(
  ex("Incline DB", 3, "10 - 12", "", idx = 0),
  ex("Lat Pulldown", 3, "10 - 12", "", idx = 1),
  ex("DB Shoulder", 3, "10 - 12", "", idx = 2),
  ex("Cable Row", 3, "12", "", idx = 3),
  ex("Curls + Ext.", 3, "12", "", idx = 4))),
  SessionTemplate(4, "Day 4 · Lower (Hyp.)", "Quads / Hamstrings / Glutes", listOf(
  ex("Front Squat", 3, "8 - 10", "", idx = 0),
  ex("Leg Curl", 3, "10 - 12", "", idx = 1),
  ex("Leg Extension", 3, "12 - 15", "", idx = 2),
  ex("Calf Raises", 4, "15", "", idx = 3))),
), "Strength")

val PROG_40_MYO_REPS_RESTPAUSE = buildProgram("MYO Reps / Rest-Pause", 4, "5", listOf(
  SessionTemplate(1, "Day 1 · Push", "Chest / Shoulders / Triceps", listOf(
  ex("Bench Press (Myo)", 3, "15-20 + 4×4", "Rest 5 deep breaths between minis", idx = 0),
  ex("OHP (Myo)", 3, "15-20 + 4×4", "", idx = 1),
  ex("Tricep Pushdown", 3, "15 + 3×5", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Wednesday - Pull", "Back / Biceps / Rear Delts", listOf(
  ex("Lat Pulldown (Myo)", 3, "15 + 4×4", "", idx = 0),
  ex("Cable Row (Myo)", 3, "15 + 4×4", "", idx = 1),
  ex("Barbell Curl (Myo)", 3, "15 + 3×5", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Legs / Full", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat (Myo)", 3, "15 + 4×4", "", idx = 0),
  ex("Romanian DL", 3, "12", "Traditional sets", idx = 1),
  ex("Leg Press (Myo)", 3, "20 + 3×5", "", idx = 2))),
), "Hypertrophy")

val PROG_41_SMOLOV_SQUAT_SPECIALIZATION = buildProgram("Smolov (Squat Specialization)", 13, "1", listOf(
  SessionTemplate(1, "Day 1 · Base Meso Wk1", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "9 @ 70%", "Base mesocycle Day 1", idx = 0))),
), "Strength")

val PROG_42_SMOLOV_JR_4WEEK_PEAK = buildProgram("Smolov Jr (4-week peak)", 4, "2", listOf(
  SessionTemplate(1, "Day 1 · Week 1", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat/Bench", 6, "6 @ 70%", "Wk1 Mon", idx = 0))),
  SessionTemplate(2, "Day 2 · Wednesday - Week 1", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat/Bench", 7, "5 @ 75%", "Wk1 Wed", idx = 0))),
  SessionTemplate(3, "Day 3 · Saturday - Week 1", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat/Bench", 10, "3 @ 85%", "Wk1 Sat - heaviest day", idx = 0))),
), "Powerlifting")

val PROG_43_WESTSIDE_CONJUGATE_TEMPLATE = buildProgram("Westside Conjugate (template)", 4, "3", listOf(
  SessionTemplate(1, "Day 1 · ME Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Box Squat or DL Variation (ME)", 1, "1RM", "Work to 1RM RPE 10", idx = 0),
  ex("Good Morning", 4, "6", "Accessory", idx = 1),
  ex("Leg Press", 3, "10", "", idx = 2),
  ex("Leg Curl", 3, "12", "", idx = 3),
  ex("Abs", 3, "15", "", idx = 4))),
  SessionTemplate(2, "Day 2 · Wednesday - ME Upper", "Chest / Back / Shoulders", listOf(
  ex("Floor Press or Board Press (ME)", 1, "1RM", "ME press variation", idx = 0),
  ex("JM Press", 3, "8", "", idx = 1),
  ex("Barbell Row", 3, "8", "", idx = 2),
  ex("Lat Pulldown", 3, "10", "", idx = 3),
  ex("Rear Delt Work", 3, "15", "", idx = 4))),
  SessionTemplate(3, "Day 3 · DE Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Box Squat (DE)", 10, "2 @ 50 - 60%", "Explosive; bands/chains optional", idx = 0),
  ex("Deadlift (DE)", 8, "1 @ 60 - 70%", "Speed pulls", idx = 1),
  ex("Reverse Hyper", 3, "15", "", idx = 2),
  ex("Abs", 3, "15", "", idx = 3))),
  SessionTemplate(4, "Day 4 · Saturday - DE Upper", "Chest / Back / Shoulders", listOf(
  ex("Bench Press (DE)", 9, "3 @ 50 - 60%", "Explosive; accommodate resistance", idx = 0),
  ex("Dumbbell Press", 3, "10", "", idx = 1),
  ex("Tricep Pushdown", 3, "12", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3))),
), "Powerlifting")

val PROG_44_531_SVR_II = buildProgram("5/3/1 SVR II", 16, "4", listOf(
  SessionTemplate(1, "Day 1 · Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Squat", 5, "5+ @ 65/75/85%TM", "Leader: 5s PRO; AMRAP on 85%", idx = 0),
  ex("Squat (SSL)", 3, "5 @ 85%TM", "Second Set Last", idx = 1),
  ex("Romanian DL", 3, "10", "", idx = 2),
  ex("Leg Raises", 5, "15", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Bench", "Chest / Triceps / Shoulders", listOf(
  ex("Bench Press", 5, "5+ @ 65/75/85%TM", "", idx = 0),
  ex("Bench Press (SSL)", 3, "5 @ 85%TM", "", idx = 1),
  ex("DB Row", 5, "10", "", idx = 2),
  ex("Facepull", 5, "20", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Deadlift", "Posterior Chain", listOf(
  ex("Deadlift", 5, "5+ @ 65/75/85%TM", "", idx = 0),
  ex("Deadlift (SSL)", 3, "5 @ 85%TM", "", idx = 1),
  ex("Leg Press", 3, "10", "", idx = 2),
  ex("Abs", 5, "10", "", idx = 3))),
  SessionTemplate(4, "Day 4 · OHP", "Shoulders / Triceps / Upper Back", listOf(
  ex("OHP", 5, "5+ @ 65/75/85%TM", "", idx = 0),
  ex("OHP (SSL)", 3, "5 @ 85%TM", "", idx = 1),
  ex("Chin-Ups", 5, "10", "", idx = 2))),
), "Strength")

val PROG_45_BULGARIAN_METHOD_SIMPLIFIED = buildProgram("Bulgarian Method (simplified)", 4, "5", listOf(
  SessionTemplate(1, "Day 1 · Fri", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat (daily max)", 1, "1 @ RPE 9 - 10", "Work to a heavy single; stop if form breaks", idx = 0),
  ex("Squat (back-off)", 1, "1 @ ~90% of daily max", "", idx = 1),
  ex("Bench Press", 1, "1 @ RPE 9", "Optional second main lift", idx = 2),
  ex("Pull-Ups", 3, "AMRAP", "Accessory - keep easy", idx = 3))),
  SessionTemplate(2, "Day 2 · Saturday - Light", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 3, "5 @ ~70%", "Recovery / technique day", idx = 0))),
), "Strength")

val PROG_46_GZCL_UHF_5WEEK = buildProgram("GZCL: UHF 5-Week", 5, "1", listOf(
  SessionTemplate(1, "Day 1 · SBD", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat (T1)", 5, "3 clusters", "", idx = 0),
  ex("Bench (T2)", 3, "8", "", idx = 1),
  ex("Deadlift (T2)", 3, "8", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Tuesday - Bench + Squat", "Quads / Glutes / Lower Back", listOf(
  ex("Bench (T1)", 5, "3 clusters", "", idx = 0),
  ex("Squat (T2)", 3, "8", "", idx = 1),
  ex("Row (T3)", 3, "15", "", idx = 2))),
  SessionTemplate(3, "Day 3 · Wednesday - Deadlift + OHP", "Posterior Chain", listOf(
  ex("Deadlift (T1)", 5, "3 clusters", "", idx = 0),
  ex("OHP (T2)", 3, "8", "", idx = 1),
  ex("Pull-Ups (T3)", 3, "AMRAP", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Thursday - Bench + DL", "Posterior Chain", listOf(
  ex("Bench (T1)", 5, "3 clusters", "", idx = 0),
  ex("Deadlift (T2)", 3, "5", "", idx = 1),
  ex("Row (T3)", 3, "15", "", idx = 2))),
  SessionTemplate(5, "Day 5 · Squat + Bench", "Quads / Glutes / Lower Back", listOf(
  ex("Squat (T1)", 5, "3 clusters", "", idx = 0),
  ex("Bench (T2)", 3, "5", "", idx = 1),
  ex("OHP (T3)", 3, "10", "", idx = 2))),
), "Powerlifting")

val PROG_47_GZCL_UHF_9WEEK = buildProgram("GZCL: UHF 9-Week", 9, "2", listOf(
  SessionTemplate(1, "Day 1 · Monday", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat (T1)", 7, "2 clusters", "Wk1 starts higher volume", idx = 0),
  ex("Bench (T2)", 4, "8", "", idx = 1),
  ex("Deadlift (T2)", 4, "8", "", idx = 2))),
  SessionTemplate(2, "Day 2 · Tuesday", "Quads / Hamstrings / Glutes", listOf(
  ex("Bench (T1)", 7, "2 clusters", "", idx = 0),
  ex("Squat (T2)", 4, "8", "", idx = 1),
  ex("Accessories", 3, "12 - 15", "T3 choice", idx = 2))),
  SessionTemplate(3, "Day 3 · Wednesday", "Full Body", listOf(
  ex("Deadlift (T1)", 7, "2 clusters", "", idx = 0),
  ex("OHP (T2)", 4, "8", "", idx = 1),
  ex("Row (T3)", 3, "15", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Thursday", "Quads / Hamstrings / Glutes", listOf(
  ex("Bench (T1)", 7, "2 clusters", "", idx = 0),
  ex("Squat (T2)", 4, "6", "", idx = 1))),
  SessionTemplate(5, "Day 5 · Friday", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat (T1)", 5, "2 clusters", "", idx = 0),
  ex("Deadlift (T2)", 3, "5", "", idx = 1),
  ex("Bench (T3)", 3, "10", "", idx = 2))),
), "Powerlifting")

val PROG_48_REDDIT_RECOMMENDED_ROUTINE = buildProgram("Reddit Recommended Routine", 4, "3", listOf(
  SessionTemplate(1, "Day 1 · Mon / Wed / Fri", "Quads / Hamstrings / Glutes", listOf(
  ex("Handstand Practice", 3, "5 min", "Skill: freestanding HS progression", idx = 0),
  ex("Support Hold (parallel bars)", 1, "60s", "Skill: dip support", idx = 1),
  ex("Tuck L-Sit", 1, "10-60s", "Skill: L-sit progression", idx = 2),
  ex("Vertical Pull (Chin-Up)", 3, "5 - 8", "Progression pair 1A", idx = 3),
  ex("Vertical Push (Pike PU→HSPU)", 3, "5 - 8", "Progression pair 1B", idx = 4),
  ex("Horizontal Pull (Row)", 3, "5 - 8", "Pair 2A", idx = 5),
  ex("Horizontal Push (Push-Up)", 3, "5 - 8", "Pair 2B", idx = 6),
  ex("Hip Hinge (Nordic/RDL)", 3, "8", "Pair 3A", idx = 7),
  ex("Squat (Pistol progression)", 3, "8", "Pair 3B", idx = 8))),
), "GPP")

val PROG_49_PHDEADLIFT_BASE_BUILDING = buildProgram("Phdeadlift Base Building", 12, "4", listOf(
  SessionTemplate(1, "Day 1 · Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "6 @ RPE 7", "", idx = 0),
  ex("Romanian DL", 3, "8", "", idx = 1),
  ex("Leg Press", 3, "10", "", idx = 2),
  ex("Leg Curl", 3, "10", "", idx = 3))),
  SessionTemplate(2, "Day 2 · Tuesday - Upper", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 4, "6 @ RPE 7", "", idx = 0),
  ex("OHP", 3, "8", "", idx = 1),
  ex("Lat Pulldown", 3, "10", "", idx = 2),
  ex("Face Pulls", 3, "15", "", idx = 3))),
  SessionTemplate(3, "Day 3 · Thursday - Lower", "Quads / Hamstrings / Glutes", listOf(
  ex("Deadlift", 3, "4 @ RPE 8", "", idx = 0),
  ex("Front Squat", 3, "6", "", idx = 1),
  ex("Good Morning", 3, "8", "", idx = 2))),
  SessionTemplate(4, "Day 4 · Upper", "Chest / Back / Shoulders", listOf(
  ex("Bench Press", 3, "4 @ RPE 8", "", idx = 0),
  ex("Barbell Row", 4, "5", "", idx = 1),
  ex("DB Incline", 3, "10", "", idx = 2),
  ex("Rear Delt Work", 3, "15", "", idx = 3))),
), "Powerbuilding")

val PROG_50_METALLICADPA_4DAY_PPL = buildProgram("Metallicadpa 4-Day PPL", 4, "5", listOf(
  SessionTemplate(1, "Day 1 · Push + Legs A", "Quads / Hamstrings / Glutes", listOf(
  ex("Squat", 4, "5, 5, 5, 5+", "AMRAP last set", idx = 0),
  ex("Bench Press", 4, "5, 5, 5, 5+", "", idx = 1),
  ex("OHP", 3, "8, 8, 8+", "", idx = 2),
  ex("Incline DB", 3, "10", "", idx = 3),
  ex("Leg Press", 3, "10", "", idx = 4),
  ex("Leg Curl", 3, "10", "", idx = 5),
  ex("Tricep Pushdown", 3, "12", "", idx = 6))),
  SessionTemplate(2, "Day 2 · Tuesday - Pull + Legs B", "Quads / Hamstrings / Glutes", listOf(
  ex("Deadlift", 1, "5+", "", idx = 0),
  ex("Romanian DL", 3, "8", "", idx = 1),
  ex("Barbell Row", 3, "8, 8, 8+", "", idx = 2),
  ex("Lat Pulldown", 3, "10", "", idx = 3),
  ex("Cable Row", 3, "10", "", idx = 4),
  ex("Face Pulls", 3, "15", "", idx = 5),
  ex("Hammer Curl", 3, "12", "", idx = 6))),
  SessionTemplate(3, "Day 3 · Thursday - Push + Legs A", "Quads / Hamstrings / Glutes", listOf(
  ex("(Repeat Monday)", 3, "8-12", "", idx = 0))),
  SessionTemplate(4, "Day 4 · Pull + Legs B", "Quads / Hamstrings / Glutes", listOf(
  ex("(Repeat Tuesday)", 3, "8-12", "", idx = 0))),
), "Hypertrophy")

// ─── REGISTRY ─────────────────────────────────────────────────────────────────

val ALL_DEFAULT_PROGRAMS = listOf(
  // ── Hypertrophy ──────────────────────────────────────────────────────────
  PROGRAM_FULL_BODY,
  PROGRAM_ANT_POST,
  PROGRAM_UPPER_LOWER,
  PROGRAM_PPLUL,
  PROGRAM_BRO_SPLIT,
  PROGRAM_PPL_6DAY,
  PROG_26_REDDIT_PPL_METALLICADPA,
  PROG_29_ARNOLD_SPLIT,
  PROG_30_PPLUL_5DAY,
  PROG_31_KINOBODY_GREEK_GOD,
  PROG_33_GZCLP_HYPERTROPHY_TEMPLATE,
  PROG_34_ERIC_HELMS_INT_BODYBUILDING,
  PROG_35_MASS_IMPACT,
  PROG_36_STRONG_CURVES,
  PROG_37_FRANKOMAN_DUMBBELL_PPL,
  PROG_38_DUMBBELL_PPL,
  PROG_40_MYO_REPS_RESTPAUSE,
  PROG_50_METALLICADPA_4DAY_PPL,
  // ── Strength ─────────────────────────────────────────────────────────────
  PROGRAM_531,
  PROGRAM_TEXAS_METHOD,
  PROGRAM_GREYSKULL,
  PROG_01_STARTING_STRENGTH,
  PROG_02_STRONGLIFTS_5X5,
  PROG_03_RFITNESS_BASIC_BEGINNER,
  PROG_04_531_FOR_BEGINNERS,
  PROG_05_GREYSKULL_LP,
  PROG_06_GZCLP,
  PROG_07_PHRAKS_GREYSKULL_LP,
  PROG_08_FIERCE_5,
  PROG_09_ALL_PRO_BEGINNER,
  PROG_10_NSUNS_531_5DAY,
  PROG_11_531_BORING_BUT_BIG,
  PROG_12_531_BUILDING_THE_MONOLITH,
  PROG_13_TEXAS_METHOD,
  PROG_14_MADCOW_5X5,
  PROG_15_CANDITO_6WEEK_STRENGTH,
  PROG_16_CANDITO_LINEAR_PROGRESSION,
  PROG_17_GZCL_THE_RIPPLER,
  PROG_19_GREG_NUCKOLS_28_PROGRAMS,
  PROG_21_JUGGERNAUT_METHOD_BASE,
  PROG_24_BARBELL_MEDICINE_BRIDGE,
  PROG_25_GZCLP_4DAY,
  PROG_39_UPPERLOWER_4DAY_SPLIT,
  PROG_41_SMOLOV_SQUAT_SPECIALIZATION,
  PROG_44_531_SVR_II,
  PROG_45_BULGARIAN_METHOD_SIMPLIFIED,
  // ── Powerlifting ─────────────────────────────────────────────────────────
  PROGRAM_POWERLIFTING,
  PROGRAM_CONJUGATE,
  PROGRAM_SBD_PEAKING,
  PROG_20_SHEIKO_29_INTERMEDIATE,
  PROG_22_CUBE_METHOD,
  PROG_42_SMOLOV_JR_4WEEK_PEAK,
  PROG_43_WESTSIDE_CONJUGATE_TEMPLATE,
  PROG_46_GZCL_UHF_5WEEK,
  PROG_47_GZCL_UHF_9WEEK,
  // ── Powerbuilding ────────────────────────────────────────────────────────
  PROG_18_GZCL_JACKED_AND_TAN_20,
  PROG_23_SBS_BEGINNERINTERMEDIATE,
  PROG_27_PHUL,
  PROG_28_PHAT,
  PROG_49_PHDEADLIFT_BASE_BUILDING,
  // ── GPP ──────────────────────────────────────────────────────────────────
  PROGRAM_GPP_3DAY,
  PROGRAM_ATHLETIC_4DAY,
  PROG_32_REDDIT_BODYWEIGHT_RR,
  PROG_48_REDDIT_RECOMMENDED_ROUTINE,
  // ── Conditioning ─────────────────────────────────────────────────────────
  PROGRAM_METCON_3DAY
)
