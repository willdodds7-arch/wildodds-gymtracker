package com.wildodds.gymtracker.data.builder

import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-readable, versioned JSON for a [PlanProgram].
 *
 * Local file operations only — this object produces and consumes strings; the caller writes them
 * through the platform file picker. Nothing here touches the network.
 *
 * The round trip must be lossless: [decode] of [encode] returns an equal program apart from the
 * regenerated ids, which are internal handles rather than user data. `ProgramPlanJsonTest` asserts
 * that on a fully-populated program.
 *
 * Hand-written with org.json rather than adding a serialization dependency, and hand-indented so a
 * user opening the file in a text editor can read and edit it.
 */
object ProgramPlanJson {

  const val SCHEMA_VERSION = 1
  const val MIME = "application/json"

  // ── Encode ─────────────────────────────────────────────────────────────────

  fun encode(program: PlanProgram): String = JSONObject().apply {
    put("schemaVersion", SCHEMA_VERSION)
    put("kind", "wildodds.program")
    put("name", program.name)
    put("description", program.description)
    put("goal", program.goal.name)
    put("experience", program.experience.name)
    put("durationWeeks", program.durationWeeks)
    put("trackRpe", program.trackRpe)
    put("trackOneRm", program.trackOneRm)
    put("folder", program.folder)
    program.coverImage?.let { put("coverImage", it) }
    put("progression", encodeProgression(program.progression))
    put("workouts", JSONArray(program.workouts.map { encodeWorkout(it) }))
  }.toString(2)

  private fun encodeProgression(p: ProgressionSettings) = JSONObject().apply {
    put("method", p.method.name)
    put("repRangeLow", p.repRangeLow)
    put("repRangeHigh", p.repRangeHigh)
    put("loadIncrement", p.loadIncrement.toDouble())
    put("requiredSuccessfulSets", p.requiredSuccessfulSets)
    put("rirTarget", p.rirTarget)
    put("autoRecommend", p.autoRecommend)
    put("requireApproval", p.requireApproval)
    put("deloadEveryWeeks", p.deloadEveryWeeks)
  }

  private fun encodeWorkout(w: PlanWorkout) = JSONObject().apply {
    put("name", w.name)
    put("kind", w.kind.name)
    put("weekday", w.weekday)
    put("note", w.note)
    put("isOptional", w.isOptional)
    put("groups", JSONArray(w.groups.map { g ->
      JSONObject().apply {
        put("id", g.id)
        put("label", g.label)
        put("rounds", g.rounds)
        put("restBetweenExercisesSeconds", g.restBetweenExercisesSeconds)
        put("restAfterRoundSeconds", g.restAfterRoundSeconds)
      }
    }))
    put("exercises", JSONArray(w.exercises.map { encodeExercise(it) }))
  }

  private fun encodeExercise(e: PlanExercise) = JSONObject().apply {
    put("name", e.name)
    put("notes", e.notes)
    put("isOptional", e.isOptional)
    e.supersetGroupId?.let { put("supersetGroupId", it) }
    put("alternatives", JSONArray(e.alternatives))
    put("sets", JSONArray(e.sets.map { s ->
      JSONObject().apply {
        put("type", s.type.name)
        put("load", s.load)
        put("reps", s.reps)
        put("restSeconds", s.restSeconds)
        put("rpe", s.rpe)
        put("tempo", s.tempo)
        put("notes", s.notes)
      }
    }))
  }

  // ── Decode ─────────────────────────────────────────────────────────────────

  sealed class Result {
    data class Ok(val program: PlanProgram, val notes: List<String>) : Result()
    data class Invalid(val reason: String) : Result()
  }

  /**
   * Parse and validate. Never throws — a malformed file returns [Result.Invalid] so the caller can
   * show the reason and preview before writing anything.
   */
  fun decode(raw: String): Result {
    val root = runCatching { JSONObject(raw) }.getOrNull()
      ?: return Result.Invalid("That file isn't valid JSON.")

    val kind = root.optString("kind")
    if (kind.isNotBlank() && kind != "wildodds.program") {
      return Result.Invalid("That JSON isn't a Wild Odds program file.")
    }

    val version = root.optInt("schemaVersion", 0)
    if (version > SCHEMA_VERSION) {
      return Result.Invalid(
        "That file was written by a newer version of the app (format $version, this build reads " +
          "$SCHEMA_VERSION). Update the app and try again."
      )
    }

    val notes = mutableListOf<String>()
    if (version == 0) notes += "File had no version stamp; read as format $SCHEMA_VERSION."

    val workoutsJson = root.optJSONArray("workouts")
    if (workoutsJson == null || workoutsJson.length() == 0) {
      return Result.Invalid("That program has no workouts in it.")
    }

    val workouts = (0 until workoutsJson.length()).mapNotNull { i ->
      workoutsJson.optJSONObject(i)?.let { decodeWorkout(it, notes) }
    }
    if (workouts.isEmpty()) return Result.Invalid("None of the workouts in that file could be read.")

    val program = PlanProgram(
      name = root.optString("name").ifBlank { PlanProgram.DEFAULT_NAME },
      description = root.optString("description"),
      goal = enumOr(root.optString("goal"), ProgramGoal.HYPERTROPHY, notes, "goal"),
      experience = enumOr(root.optString("experience"), ExperienceLevel.INTERMEDIATE, notes, "experience"),
      durationWeeks = root.optInt("durationWeeks", 0).coerceAtLeast(0),
      workouts = workouts,
      progression = root.optJSONObject("progression")?.let { decodeProgression(it, notes) }
        ?: ProgressionSettings(),
      trackRpe = root.optBoolean("trackRpe", false),
      trackOneRm = root.optBoolean("trackOneRm", false),
      coverImage = root.optString("coverImage").takeIf { it.isNotBlank() },
      folder = root.optString("folder")
    )
    return Result.Ok(program, notes)
  }

  private fun decodeProgression(o: JSONObject, notes: MutableList<String>) = ProgressionSettings(
    method = enumOr(o.optString("method"), ProgressionMethod.MANUAL, notes, "progression method"),
    repRangeLow = o.optInt("repRangeLow", 8),
    repRangeHigh = o.optInt("repRangeHigh", 12),
    loadIncrement = o.optDouble("loadIncrement", 2.5).toFloat(),
    requiredSuccessfulSets = o.optInt("requiredSuccessfulSets", 3),
    rirTarget = o.optInt("rirTarget", 2),
    autoRecommend = o.optBoolean("autoRecommend", true),
    requireApproval = o.optBoolean("requireApproval", true),
    deloadEveryWeeks = o.optInt("deloadEveryWeeks", 0)
  )

  private fun decodeWorkout(o: JSONObject, notes: MutableList<String>): PlanWorkout {
    val groupsJson = o.optJSONArray("groups") ?: JSONArray()
    // Group ids are referenced by exercises, so remap old ids onto freshly-generated ones.
    val groups = (0 until groupsJson.length()).mapNotNull { i ->
      groupsJson.optJSONObject(i)?.let { g ->
        PlanGroup(
          label = g.optString("label", "A"),
          rounds = g.optInt("rounds", 3),
          restBetweenExercisesSeconds = g.optInt("restBetweenExercisesSeconds", 0),
          restAfterRoundSeconds = g.optInt("restAfterRoundSeconds", 90)
        ) to g.optString("id")
      }
    }
    val idMap = groups.associate { (group, oldId) -> oldId to group.id }

    val exJson = o.optJSONArray("exercises") ?: JSONArray()
    val exercises = (0 until exJson.length()).mapNotNull { i ->
      exJson.optJSONObject(i)?.let { decodeExercise(it, idMap) }
    }

    return PlanWorkout(
      name = o.optString("name"),
      kind = enumOr(o.optString("kind"), WorkoutKind.TRAINING, notes, "workout kind"),
      exercises = exercises,
      groups = groups.map { it.first },
      weekday = o.optInt("weekday", 0).coerceIn(0, 7),
      note = o.optString("note"),
      isOptional = o.optBoolean("isOptional", false)
    )
  }

  private fun decodeExercise(o: JSONObject, groupIds: Map<String, String>): PlanExercise {
    val setsJson = o.optJSONArray("sets") ?: JSONArray()
    val sets = (0 until setsJson.length()).mapNotNull { i ->
      setsJson.optJSONObject(i)?.let { s ->
        PlanSet(
          type = runCatching { SetType.valueOf(s.optString("type")) }.getOrDefault(SetType.WORKING),
          load = s.optString("load"),
          reps = s.optString("reps"),
          restSeconds = s.optInt("restSeconds", 90),
          rpe = s.optString("rpe"),
          tempo = s.optString("tempo"),
          notes = s.optString("notes")
        )
      }
    }
    val altJson = o.optJSONArray("alternatives") ?: JSONArray()
    return PlanExercise(
      name = o.optString("name"),
      sets = sets,
      notes = o.optString("notes"),
      supersetGroupId = o.optString("supersetGroupId").takeIf { it.isNotBlank() }?.let { groupIds[it] },
      alternatives = (0 until altJson.length()).map { altJson.optString(it) }.filter { it.isNotBlank() },
      isOptional = o.optBoolean("isOptional", false)
    )
  }

  /** Read an enum by name, falling back and recording a note rather than failing the import. */
  private inline fun <reified E : Enum<E>> enumOr(
    raw: String,
    fallback: E,
    notes: MutableList<String>,
    label: String
  ): E {
    if (raw.isBlank()) return fallback
    return runCatching { enumValueOf<E>(raw) }.getOrElse {
      notes += "Unknown $label \"$raw\" — used ${fallback.name} instead."
      fallback
    }
  }

  /** Safe, descriptive filename for the share sheet. */
  fun fileNameFor(program: PlanProgram): String {
    val slug = PlanProgram.resolvedName(program.name)
      .lowercase()
      .replace(Regex("[^a-z0-9]+"), "-")
      .trim('-')
      .take(40)
      .ifBlank { "program" }
    return "$slug.json"
  }
}
