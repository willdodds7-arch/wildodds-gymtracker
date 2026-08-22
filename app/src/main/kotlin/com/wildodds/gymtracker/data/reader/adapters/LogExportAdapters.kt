package com.wildodds.gymtracker.data.reader.adapters

import com.wildodds.gymtracker.data.importer.ExerciseRef
import com.wildodds.gymtracker.data.importer.LoadKind
import com.wildodds.gymtracker.data.importer.LoadSpec
import com.wildodds.gymtracker.data.importer.Modifier
import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.ProgramStructure
import com.wildodds.gymtracker.data.importer.RepKind
import com.wildodds.gymtracker.data.importer.RepSpec
import com.wildodds.gymtracker.data.importer.RestSpec
import com.wildodds.gymtracker.data.importer.SessionDraft
import com.wildodds.gymtracker.data.importer.SetKind
import com.wildodds.gymtracker.data.importer.SetPrescription
import com.wildodds.gymtracker.data.importer.SlotDraft
import com.wildodds.gymtracker.data.importer.WeekDraft
import com.wildodds.gymtracker.data.importer.WeightUnit
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument
import com.wildodds.gymtracker.data.reader.ingest.Grid

/**
 * The four big training-log exports: **Strong**, **Hevy**, **FitNotes** and **JEFIT**.
 *
 * All four share a shape — one row per SET, grouped by workout — so they share [LogRowAdapter],
 * which does the grouping, the per-set folding and the draft assembly once. Each concrete adapter
 * supplies only its column names and its signature.
 *
 * What "one row per set" means for the model, and why it matters: five rows of `Bench Press` at
 * 8, 8, 8, 6, 5 reps is ONE exercise with five genuinely different sets. Collapsing that to
 * "5 x 8" would be inventing the last two numbers, so it lands as a five-element `perSet` list.
 * Identical sets fold back to a plain count, because "3 x 8" is what the source actually says then.
 *
 * These are histories, not prescriptions: the weights are what the user lifted. That is recorded as
 * an `ABSOLUTE` load and stated in [ProgramImportDraft.source], so the review screen can say so.
 */
abstract class LogRowAdapter : ExportAdapter {

  /** Column names to try, in order, for each role. First match wins. */
  protected abstract val exerciseCols: List<String>
  protected abstract val repsCols: List<String>
  protected abstract val weightCols: List<String>
  protected open val workoutNameCols: List<String> = emptyList()
  protected open val dateCols: List<String> = emptyList()
  protected open val setOrderCols: List<String> = emptyList()
  protected open val notesCols: List<String> = emptyList()
  protected open val rpeCols: List<String> = emptyList()
  protected open val durationCols: List<String> = emptyList()
  protected open val distanceCols: List<String> = emptyList()
  protected open val supersetCols: List<String> = emptyList()
  protected open val setTypeCols: List<String> = emptyList()
  /** Fixed unit when the export always uses one; null when a column states it. */
  protected open val fixedUnit: WeightUnit? = null
  protected open val unitCols: List<String> = emptyList()

  override fun parse(doc: ExtractedDocument, fileName: String): ProgramImportDraft? {
    val grid = doc.firstTable() ?: return null
    val h = grid.headerRow()
    val cEx = Header.indexOf(h, *exerciseCols.toTypedArray())
    if (cEx < 0) return null
    val cReps = Header.indexOf(h, *repsCols.toTypedArray())
    val cWeight = Header.indexOf(h, *weightCols.toTypedArray())
    val cWorkout = Header.indexOf(h, *workoutNameCols.toTypedArray())
    val cDate = Header.indexOf(h, *dateCols.toTypedArray())
    val cOrder = Header.indexOf(h, *setOrderCols.toTypedArray())
    val cNotes = Header.indexOf(h, *notesCols.toTypedArray())
    val cRpe = Header.indexOf(h, *rpeCols.toTypedArray())
    val cDur = Header.indexOf(h, *durationCols.toTypedArray())
    val cDist = Header.indexOf(h, *distanceCols.toTypedArray())
    val cSuper = Header.indexOf(h, *supersetCols.toTypedArray())
    val cType = Header.indexOf(h, *setTypeCols.toTypedArray())
    val cUnit = Header.indexOf(h, *unitCols.toTypedArray())

    fun cell(r: List<String>, i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""

    // Group by session key, preserving the order rows appear in — that is the workout order, and
    // sorting by a locale-parsed date would make the result device-dependent.
    val sessions = LinkedHashMap<String, MutableList<List<String>>>()
    for (row in grid.dataRows()) {
      if (cell(row, cEx).isBlank()) continue
      val key = listOf(cell(row, cDate), cell(row, cWorkout)).filter { it.isNotBlank() }
        .joinToString(" · ").ifBlank { "Workout" }
      sessions.getOrPut(key) { mutableListOf() } += row
    }
    if (sessions.isEmpty()) return null

    val drafts = sessions.entries.mapIndexed { dayIdx, (key, rows) ->
      // Within a session, consecutive rows for the same exercise are its sets.
      val byExercise = LinkedHashMap<String, MutableList<List<String>>>()
      for (r in rows) byExercise.getOrPut(cell(r, cEx)) { mutableListOf() } += r

      val slots = byExercise.entries.mapIndexed { i, (name, setRows) ->
        val perSet = setRows.mapIndexed { si, r ->
          val unit = when {
            cUnit >= 0 -> if (cell(r, cUnit).lowercase().startsWith("lb")) WeightUnit.LB else WeightUnit.KG
            else -> fixedUnit
          }
          val w = cell(r, cWeight).replace(',', '.').toDoubleOrNull()
          val reps = cell(r, cReps).toIntOrNull()
          val seconds = cell(r, cDur).toIntOrNull()
          val distance = cell(r, cDist).replace(',', '.').toDoubleOrNull()
          val rpe = cell(r, cRpe).replace(',', '.').toDoubleOrNull()
          SetPrescription(
            rawText = r.joinToString(", ").take(300),
            count = 1,
            reps = when {
              reps != null && reps > 0 -> RepSpec(RepKind.COUNT, reps)
              seconds != null && seconds > 0 -> RepSpec(RepKind.TIME_SEC, seconds)
              distance != null && distance > 0 -> RepSpec(RepKind.DISTANCE_M, distanceMetres(distance))
              // Never a guess: a set with no stated reps has no reps.
              else -> null
            },
            load = when {
              w != null && w > 0 -> LoadSpec(LoadKind.ABSOLUTE, w, unit = unit, text = cell(r, cWeight))
              rpe != null -> LoadSpec(LoadKind.RPE, rpe, text = cell(r, cRpe))
              else -> null
            },
            modifiers = modifiersFor(cell(r, cType)),
            isFinalSetAmrap = false
          ).let { if (cOrder >= 0) it else it }.also { _ -> si }
        }

        val folded = fold(perSet)
        SlotDraft(
          rawText = "$name — ${setRows.size} set(s) from ${displayName}",
          exercise = ExerciseRef(name, name),
          sets = listOf(folded),
          rest = RestSpec(),
          supersetGroup = if (cSuper >= 0) cell(setRows.first(), cSuper) else "",
          notes = if (cNotes >= 0) setRows.mapNotNull { cell(it, cNotes).takeIf(String::isNotBlank) }
            .distinct().joinToString(" · ") else "",
          orderIndex = i,
          // A native read of a known schema is as certain as this subsystem gets.
          confidence = 0.95
        )
      }
      SessionDraft(label = key, dayNumber = dayIdx + 1, slots = slots)
    }

    return ProgramImportDraft(
      name = fileName.substringBeforeLast('.').ifBlank { displayName },
      source = "$displayName export",
      weeks = listOf(WeekDraft(1, sessions = drafts)),
      structure = ProgramStructure.FLAT,
      notes = listOf(
        "Imported from a $displayName export. The weights are what was LOGGED, not a prescription.")
    )
  }

  /**
   * Identical sets collapse to a count; anything else stays as a per-set list.
   *
   * The collapse is not a simplification — it is the honest reading. Five identical rows say
   * "5 sets of 8"; five different ones say five different things, and averaging them would put a
   * number in the program that appears nowhere in the source.
   */
  private fun fold(sets: List<SetPrescription>): SetPrescription {
    if (sets.isEmpty()) return SetPrescription("", SetKind.UNKNOWN)
    val first = sets.first()
    val allSame = sets.all { it.reps == first.reps && it.load == first.load }
    val raw = sets.joinToString(" | ") { it.rawText }.take(500)
    return if (allSame) {
      first.copy(rawText = raw, count = sets.size, perSet = emptyList())
    } else {
      first.copy(rawText = raw, count = sets.size, perSet = sets)
    }
  }

  private fun modifiersFor(setType: String): List<String> = when (setType.lowercase()) {
    "warmup", "warm up", "warm-up" -> emptyList()
    "dropset", "drop set", "drop" -> listOf(Modifier.DROP_SET)
    "failure", "to failure" -> listOf(Modifier.TO_FAILURE)
    else -> emptyList()
  }

  /** Exports state distance in km or miles depending on the app; the model stores metres. */
  protected open fun distanceMetres(v: Double): Int = (v * 1000).toInt()
}

/**
 * **Strong** — one row per set. Older exports use `;`, newer use `,`; the delimiter is inferred
 * upstream so both land here identically.
 */
object StrongAdapter : LogRowAdapter() {
  override val id = "strong"
  override val displayName = "Strong"
  override val exerciseCols = listOf("Exercise Name")
  override val repsCols = listOf("Reps")
  override val weightCols = listOf("Weight")
  override val workoutNameCols = listOf("Workout Name")
  override val dateCols = listOf("Date")
  override val setOrderCols = listOf("Set Order")
  override val notesCols = listOf("Notes")
  override val rpeCols = listOf("RPE")
  override val durationCols = listOf("Seconds")
  override val distanceCols = listOf("Distance")
  // Strong exports in the user's chosen unit without stating it, so it stays UNKNOWN rather than
  // being assumed — the review step asks. Assuming kg would silently halve every American import.
  override val fixedUnit: WeightUnit? = null

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return Header.hasAll(h, "Exercise Name", "Set Order", "Workout Name")
  }
}

/** **Hevy** — one row per set, snake_case headers, weight always in kg. */
object HevyAdapter : LogRowAdapter() {
  override val id = "hevy"
  override val displayName = "Hevy"
  override val exerciseCols = listOf("exercise_title", "exercise_name")
  override val repsCols = listOf("reps")
  override val weightCols = listOf("weight_kg")
  override val workoutNameCols = listOf("title")
  override val dateCols = listOf("start_time")
  override val setOrderCols = listOf("set_index")
  override val notesCols = listOf("exercise_notes", "description")
  override val rpeCols = listOf("rpe")
  override val durationCols = listOf("duration_seconds")
  override val distanceCols = listOf("distance_km")
  override val supersetCols = listOf("superset_id")
  override val setTypeCols = listOf("set_type")
  override val fixedUnit = WeightUnit.KG

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return Header.hasAll(h, "exercise_title", "weight_kg") ||
      Header.hasAll(h, "exercise_title", "set_index")
  }
}

/** **FitNotes** — one row per set, with an explicit weight-unit column. */
object FitNotesAdapter : LogRowAdapter() {
  override val id = "fitnotes"
  override val displayName = "FitNotes"
  override val exerciseCols = listOf("Exercise")
  override val repsCols = listOf("Reps")
  override val weightCols = listOf("Weight")
  override val dateCols = listOf("Date")
  override val notesCols = listOf("Comment")
  override val durationCols = listOf("Time")
  override val distanceCols = listOf("Distance")
  override val unitCols = listOf("Weight Unit")

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return Header.hasAll(h, "Exercise", "Weight Unit") ||
      (Header.hasAll(h, "Exercise", "Category", "Weight", "Reps") && Header.indexOf(h, "Date") >= 0)
  }
}

/**
 * **JEFIT** — one row per logged set. JEFIT's export has changed shape across versions; the
 * signature keys on the columns common to the ones seen in the wild, and anything that does not
 * match falls through to the general pipeline rather than being forced.
 */
object JefitAdapter : LogRowAdapter() {
  override val id = "jefit"
  override val displayName = "JEFIT"
  override val exerciseCols = listOf("exercise", "ename", "exercise_name")
  override val repsCols = listOf("reps", "rep")
  override val weightCols = listOf("weight", "wt")
  override val dateCols = listOf("mydate", "date")
  override val notesCols = listOf("notes", "comment")
  override val unitCols = listOf("unit", "weight unit")

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return Header.indexOf(h, "belongSys") >= 0 ||
      Header.hasAll(h, "mydate", "exercise") ||
      (fileName.contains("jefit", true) && Header.hasAll(h, "exercise", "reps"))
  }
}
