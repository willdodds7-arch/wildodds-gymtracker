package com.wildodds.gymtracker.data

/**
 * Comprehensive exercise -> muscle group mapping.
 *
 * [primary]  - muscles doing the lion's share of the work (count fully toward weekly sets)
 * [secondary] - muscles that assist significantly (count at 0.5x toward weekly sets)
 */
data class ExerciseInfo(
  val primary: List<MuscleGroup>,
  val secondary: List<MuscleGroup> = emptyList()
)

enum class MuscleGroup(val display: String, val category: String) {
  CHEST  ("Chest",  "Push"),
  FRONT_DELT  ("Front Delts",  "Push"),
  SIDE_DELT  ("Side Delts",  "Push"),
  REAR_DELT  ("Rear Delts",  "Pull"),
  UPPER_BACK  ("Upper Back",  "Pull"),
  LATS  ("Lats",  "Pull"),
  LOWER_BACK  ("Lower Back",  "Pull"),
  BICEPS  ("Biceps",  "Pull"),
  TRICEPS  ("Triceps",  "Push"),
  FOREARMS  ("Forearms",  "Arms"),
  QUADS  ("Quads",  "Legs"),
  HAMSTRINGS  ("Hamstrings",  "Legs"),
  GLUTES  ("Glutes",  "Legs"),
  CALVES  ("Calves",  "Legs"),
  ABS  ("Abs",  "Core"),
  OBLIQUES  ("Obliques",  "Core"),
  TRAPS  ("Traps",  "Pull"),
  NECK  ("Neck",  "Other"),
  HIP_FLEXORS  ("Hip Flexors",  "Legs"),
  ADDUCTORS  ("Adductors",  "Legs"),
  ABDUCTORS  ("Abductors",  "Legs")
}

object ExerciseLibrary {

  /** Look up muscle groups for a given exercise name (case-insensitive, fuzzy). */
  fun lookup(name: String): ExerciseInfo? {
  val lower = name.lowercase().trim()
  return LIBRARY.entries.firstOrNull { (key, _) ->
  lower == key || lower.contains(key) || key.split("|").any { lower.contains(it) }
  }?.value ?: fuzzyMatch(lower)
  }

  private fun fuzzyMatch(lower: String): ExerciseInfo? {
  return when {
  lower.contains("squat") && lower.contains("hack")  -> LIBRARY["hack squat"]
  lower.contains("squat") && lower.contains("bulgarian") -> LIBRARY["bulgarian split squat"]
  lower.contains("squat") && lower.contains("split")  -> LIBRARY["bulgarian split squat"]
  lower.contains("squat")  -> LIBRARY["squat"]
  lower.contains("deadlift") && lower.contains("romanian") -> LIBRARY["romanian deadlift"]
  lower.contains("deadlift") && lower.contains("sumo")  -> LIBRARY["sumo deadlift"]
  lower.contains("rdl")  -> LIBRARY["romanian deadlift"]
  lower.contains("deadlift")  -> LIBRARY["deadlift"]
  lower.contains("bench") && lower.contains("incline") && lower.contains("dumbbell") -> LIBRARY["incline dumbbell press"]
  lower.contains("bench") && lower.contains("incline") -> LIBRARY["incline bench press"]
  lower.contains("bench") && lower.contains("decline") -> LIBRARY["decline bench press"]
  lower.contains("bench") && lower.contains("close grip") -> LIBRARY["close grip bench press"]
  lower.contains("bench")  -> LIBRARY["bench press"]
  lower.contains("overhead press") || lower.contains("ohp") -> LIBRARY["overhead press"]
  lower.contains("shoulder press") && lower.contains("arnold") -> LIBRARY["arnold press"]
  lower.contains("shoulder press")  -> LIBRARY["overhead press"]
  lower.contains("pull-up") || lower.contains("pullup") || lower.contains("pull up") -> LIBRARY["pull-up"]
  lower.contains("chin-up") || lower.contains("chinup") || lower.contains("chin up") -> LIBRARY["chin-up"]
  lower.contains("lat pulldown") || lower.contains("lat pull-down") -> LIBRARY["lat pulldown"]
  lower.contains("row") && lower.contains("barbell")  -> LIBRARY["barbell row"]
  lower.contains("row") && lower.contains("cable")  -> LIBRARY["cable row"]
  lower.contains("row") && lower.contains("dumbbell")  -> LIBRARY["dumbbell row"]
  lower.contains("row") && lower.contains("machine")  -> LIBRARY["machine row"]
  lower.contains("row") && lower.contains("t-bar")  -> LIBRARY["t-bar row"]
  lower.contains("row") && lower.contains("chest supported") -> LIBRARY["chest supported row"]
  lower.contains("row") && lower.contains("upright")  -> LIBRARY["upright row"]
  lower.contains("row")  -> LIBRARY["barbell row"]
  lower.contains("lunge")  -> LIBRARY["lunge"]
  lower.contains("step-up") || lower.contains("step up") -> LIBRARY["step-up"]
  lower.contains("leg press")  -> LIBRARY["leg press"]
  lower.contains("leg extension")  -> LIBRARY["leg extension"]
  lower.contains("leg curl") && lower.contains("seated") -> LIBRARY["seated leg curl"]
  lower.contains("leg curl")  -> LIBRARY["lying leg curl"]
  lower.contains("nordic curl") || lower.contains("nordic") -> LIBRARY["nordic curl"]
  lower.contains("hip thrust") || lower.contains("hip bridge") -> LIBRARY["hip thrust"]
  lower.contains("glute bridge")  -> LIBRARY["hip thrust"]
  lower.contains("calf raise") || lower.contains("calf press") -> LIBRARY["calf raise"]
  lower.contains("lateral raise") || lower.contains("lat raise") -> LIBRARY["lateral raise"]
  lower.contains("front raise")  -> LIBRARY["front raise"]
  lower.contains("face pull")  -> LIBRARY["face pull"]
  lower.contains("rear delt") || lower.contains("reverse fly") || lower.contains("reverse flye") -> LIBRARY["reverse fly"]
  lower.contains("shrug")  -> LIBRARY["shrug"]
  lower.contains("curl") && lower.contains("hammer")  -> LIBRARY["hammer curl"]
  lower.contains("curl") && lower.contains("preacher")  -> LIBRARY["preacher curl"]
  lower.contains("curl") && lower.contains("concentration") -> LIBRARY["concentration curl"]
  lower.contains("curl") && lower.contains("cable")  -> LIBRARY["cable curl"]
  lower.contains("curl") && lower.contains("incline")  -> LIBRARY["incline curl"]
  lower.contains("curl")  -> LIBRARY["barbell curl"]
  lower.contains("skullcrusher") || lower.contains("skull crusher") || lower.contains("ez bar") -> LIBRARY["skullcrusher"]
  lower.contains("tricep") && (lower.contains("pushdown") || lower.contains("push-down")) -> LIBRARY["tricep pushdown"]
  lower.contains("tricep") && lower.contains("extension") -> LIBRARY["overhead tricep extension"]
  lower.contains("tricep") && lower.contains("dip")  -> LIBRARY["tricep dip"]
  lower.contains("dip")  -> LIBRARY["dip"]
  lower.contains("pushup") || lower.contains("push-up") || lower.contains("push up") -> LIBRARY["push-up"]
  lower.contains("pec deck")  -> LIBRARY["pec deck"]
  lower.contains("fly") || lower.contains("flye") -> when {
  lower.contains("cable")  -> LIBRARY["cable fly"]
  lower.contains("pec deck") || lower.contains("machine") -> LIBRARY["pec deck"]
  else  -> LIBRARY["dumbbell fly"]
  }
  lower.contains("dumbbell press") && lower.contains("incline") -> LIBRARY["incline dumbbell press"]
  lower.contains("dumbbell press")  -> LIBRARY["dumbbell bench press"]
  lower.contains("chest press")  -> LIBRARY["machine chest press"]
  lower.contains("crunch")  -> LIBRARY["crunch"]
  lower.contains("plank")  -> LIBRARY["plank"]
  lower.contains("ab wheel") || lower.contains("ab rollout") -> LIBRARY["ab wheel"]
  lower.contains("leg raise")  -> LIBRARY["leg raise"]
  lower.contains("russian twist")  -> LIBRARY["russian twist"]
  lower.contains("hyperextension") || lower.contains("back extension") -> LIBRARY["back extension"]
  lower.contains("good morning")  -> LIBRARY["good morning"]
  lower.contains("wrist curl") || lower.contains("wrist roller") -> LIBRARY["wrist curl"]
  lower.contains("arnold")  -> LIBRARY["arnold press"]
  else -> null
  }
  }

  // ── The library ─────────────────────────────────────────────────────────────

  val LIBRARY: Map<String, ExerciseInfo> = mapOf(

  // Chest
  "bench press"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT, MuscleGroup.TRICEPS)),
  "incline bench press"  to ExerciseInfo(listOf(MuscleGroup.CHEST, MuscleGroup.FRONT_DELT), listOf(MuscleGroup.TRICEPS)),
  "decline bench press"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.TRICEPS)),
  "dumbbell bench press"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT, MuscleGroup.TRICEPS)),
  "incline dumbbell press"  to ExerciseInfo(listOf(MuscleGroup.CHEST, MuscleGroup.FRONT_DELT), listOf(MuscleGroup.TRICEPS)),
  "machine chest press"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT, MuscleGroup.TRICEPS)),
  "close grip bench press"  to ExerciseInfo(listOf(MuscleGroup.TRICEPS, MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT)),
  "push-up"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT, MuscleGroup.TRICEPS)),
  "dumbbell fly"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT)),
  "cable fly"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT)),
  "pec deck"  to ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.FRONT_DELT)),
  "dip"  to ExerciseInfo(listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), listOf(MuscleGroup.FRONT_DELT)),

  // Shoulders
  "overhead press"  to ExerciseInfo(listOf(MuscleGroup.FRONT_DELT, MuscleGroup.SIDE_DELT), listOf(MuscleGroup.TRICEPS, MuscleGroup.UPPER_BACK)),
  "arnold press"  to ExerciseInfo(listOf(MuscleGroup.FRONT_DELT, MuscleGroup.SIDE_DELT), listOf(MuscleGroup.TRICEPS)),
  "lateral raise"  to ExerciseInfo(listOf(MuscleGroup.SIDE_DELT)),
  "front raise"  to ExerciseInfo(listOf(MuscleGroup.FRONT_DELT)),
  "face pull"  to ExerciseInfo(listOf(MuscleGroup.REAR_DELT, MuscleGroup.UPPER_BACK), listOf(MuscleGroup.BICEPS)),
  "reverse fly"  to ExerciseInfo(listOf(MuscleGroup.REAR_DELT, MuscleGroup.UPPER_BACK)),
  "upright row"  to ExerciseInfo(listOf(MuscleGroup.SIDE_DELT, MuscleGroup.TRAPS), listOf(MuscleGroup.BICEPS)),
  "shrug"  to ExerciseInfo(listOf(MuscleGroup.TRAPS), listOf(MuscleGroup.FOREARMS)),

  // Back
  "pull-up"  to ExerciseInfo(listOf(MuscleGroup.LATS, MuscleGroup.UPPER_BACK), listOf(MuscleGroup.BICEPS, MuscleGroup.REAR_DELT)),
  "chin-up"  to ExerciseInfo(listOf(MuscleGroup.LATS, MuscleGroup.BICEPS), listOf(MuscleGroup.UPPER_BACK)),
  "lat pulldown"  to ExerciseInfo(listOf(MuscleGroup.LATS), listOf(MuscleGroup.BICEPS, MuscleGroup.REAR_DELT)),
  "barbell row"  to ExerciseInfo(listOf(MuscleGroup.UPPER_BACK, MuscleGroup.LATS), listOf(MuscleGroup.BICEPS, MuscleGroup.REAR_DELT)),
  "dumbbell row"  to ExerciseInfo(listOf(MuscleGroup.LATS, MuscleGroup.UPPER_BACK), listOf(MuscleGroup.BICEPS)),
  "cable row"  to ExerciseInfo(listOf(MuscleGroup.UPPER_BACK, MuscleGroup.LATS), listOf(MuscleGroup.BICEPS)),
  "machine row"  to ExerciseInfo(listOf(MuscleGroup.UPPER_BACK, MuscleGroup.LATS), listOf(MuscleGroup.BICEPS)),
  "t-bar row"  to ExerciseInfo(listOf(MuscleGroup.UPPER_BACK, MuscleGroup.LATS), listOf(MuscleGroup.BICEPS)),
  "chest supported row"  to ExerciseInfo(listOf(MuscleGroup.UPPER_BACK, MuscleGroup.REAR_DELT), listOf(MuscleGroup.BICEPS)),
  "back extension"  to ExerciseInfo(listOf(MuscleGroup.LOWER_BACK, MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS)),
  "good morning"  to ExerciseInfo(listOf(MuscleGroup.LOWER_BACK, MuscleGroup.HAMSTRINGS), listOf(MuscleGroup.GLUTES)),

  // Arms
  "barbell curl"  to ExerciseInfo(listOf(MuscleGroup.BICEPS), listOf(MuscleGroup.FOREARMS)),
  "dumbbell curl"  to ExerciseInfo(listOf(MuscleGroup.BICEPS), listOf(MuscleGroup.FOREARMS)),
  "hammer curl"  to ExerciseInfo(listOf(MuscleGroup.BICEPS, MuscleGroup.FOREARMS)),
  "preacher curl"  to ExerciseInfo(listOf(MuscleGroup.BICEPS)),
  "concentration curl"  to ExerciseInfo(listOf(MuscleGroup.BICEPS)),
  "cable curl"  to ExerciseInfo(listOf(MuscleGroup.BICEPS)),
  "incline curl"  to ExerciseInfo(listOf(MuscleGroup.BICEPS)),
  "skullcrusher"  to ExerciseInfo(listOf(MuscleGroup.TRICEPS)),
  "tricep pushdown"  to ExerciseInfo(listOf(MuscleGroup.TRICEPS)),
  "overhead tricep extension" to ExerciseInfo(listOf(MuscleGroup.TRICEPS)),
  "tricep dip"  to ExerciseInfo(listOf(MuscleGroup.TRICEPS), listOf(MuscleGroup.CHEST)),
  "wrist curl"  to ExerciseInfo(listOf(MuscleGroup.FOREARMS)),

  // Legs
  "squat"  to ExerciseInfo(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS, MuscleGroup.LOWER_BACK)),
  "hack squat"  to ExerciseInfo(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS)),
  "bulgarian split squat"  to ExerciseInfo(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS)),
  "leg press"  to ExerciseInfo(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS)),
  "leg extension"  to ExerciseInfo(listOf(MuscleGroup.QUADS)),
  "lying leg curl"  to ExerciseInfo(listOf(MuscleGroup.HAMSTRINGS), listOf(MuscleGroup.GLUTES)),
  "seated leg curl"  to ExerciseInfo(listOf(MuscleGroup.HAMSTRINGS)),
  "nordic curl"  to ExerciseInfo(listOf(MuscleGroup.HAMSTRINGS), listOf(MuscleGroup.GLUTES)),
  "deadlift"  to ExerciseInfo(listOf(MuscleGroup.LOWER_BACK, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES), listOf(MuscleGroup.QUADS, MuscleGroup.TRAPS)),
  "sumo deadlift"  to ExerciseInfo(listOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS, MuscleGroup.LOWER_BACK), listOf(MuscleGroup.QUADS, MuscleGroup.ADDUCTORS)),
  "romanian deadlift"  to ExerciseInfo(listOf(MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES), listOf(MuscleGroup.LOWER_BACK)),
  "hip thrust"  to ExerciseInfo(listOf(MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS)),
  "lunge"  to ExerciseInfo(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS)),
  "step-up"  to ExerciseInfo(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), listOf(MuscleGroup.HAMSTRINGS)),
  "calf raise"  to ExerciseInfo(listOf(MuscleGroup.CALVES)),

  // Core
  "crunch"  to ExerciseInfo(listOf(MuscleGroup.ABS)),
  "plank"  to ExerciseInfo(listOf(MuscleGroup.ABS), listOf(MuscleGroup.LOWER_BACK)),
  "ab wheel"  to ExerciseInfo(listOf(MuscleGroup.ABS, MuscleGroup.LOWER_BACK)),
  "leg raise"  to ExerciseInfo(listOf(MuscleGroup.ABS, MuscleGroup.HIP_FLEXORS)),
  "russian twist"  to ExerciseInfo(listOf(MuscleGroup.OBLIQUES, MuscleGroup.ABS)),
  )

  val allMuscleGroups: List<MuscleGroup> = MuscleGroup.entries.sortedBy { it.display }
}
