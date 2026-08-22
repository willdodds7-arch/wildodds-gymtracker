package com.wildodds.gymtracker.data.importer.layout

import com.wildodds.gymtracker.data.importer.SetNotationParser
import com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary
import com.wildodds.gymtracker.data.reader.ingest.Grid

/** What a column of a training spreadsheet is for. */
enum class ColumnRole {
  EXERCISE, SETS, REPS, LOAD, PERCENT, RPE, REST, TEMPO, NOTES,
  WEEK, DAY, DATE, SUPERSET, ORDER, MUSCLE, IGNORE
}

/**
 * Decides what every column of a sheet is, **globally**.
 *
 * The previous classifier walked the header row and took the first keyword that matched, per column,
 * independently. Two failures follow from that directly: a sheet with columns headed `Set` and
 * `Sets` gives both to SETS and loses one; and a sheet with no header row at all gives nothing to
 * anything, which is why header-less coach sheets imported as a single column of text.
 *
 * Here every column is scored against every role from BOTH header text and cell-content statistics,
 * and the assignment is then solved as a whole so each role is claimed at most once by the column
 * that fits it best. That is what makes `Set` / `Sets` resolve correctly and what makes a
 * header-less sheet work from content alone.
 *
 * The solver is the Hungarian algorithm on a 16-role by N-column matrix. N is small (a training
 * sheet is rarely wider than 20 meaningful columns) so the cubic cost is irrelevant, and unlike a
 * greedy assignment it cannot be led astray by claiming a strong-but-wrong pairing first.
 */
object ColumnRoles {

  private val vocab = ReaderVocabulary

  data class Assignment(
    val roles: Map<Int, ColumnRole>,
    /** Per-column confidence, so a weak assignment can be surfaced rather than trusted. */
    val confidence: Map<Int, Double>,
    val headerRow: Int?
  ) {
    fun columnFor(role: ColumnRole): Int? = roles.entries.firstOrNull { it.value == role }?.key
    operator fun get(col: Int): ColumnRole = roles[col] ?: ColumnRole.IGNORE
  }

  /** Roles that can only ever be filled once. NOTES is excluded — sheets legitimately have several. */
  private val UNIQUE_ROLES = ColumnRole.entries.filter { it != ColumnRole.IGNORE && it != ColumnRole.NOTES }

  fun assign(grid: Grid, headerRow: Int?): Assignment {
    val cols = grid.colCount
    if (cols == 0) return Assignment(emptyMap(), emptyMap(), headerRow)

    val scores = Array(cols) { c -> scoreColumn(grid, c, headerRow) }

    // Hungarian on cost = 1 - score, padded to a square matrix.
    val n = maxOf(cols, UNIQUE_ROLES.size)
    val cost = Array(n) { DoubleArray(n) { 1.0 } }
    for (c in 0 until cols) for ((ri, role) in UNIQUE_ROLES.withIndex()) {
      cost[c][ri] = 1.0 - (scores[c][role] ?: 0.0)
    }
    val match = Hungarian.solve(cost)

    val roles = LinkedHashMap<Int, ColumnRole>()
    val conf = LinkedHashMap<Int, Double>()
    for (c in 0 until cols) {
      val ri = match[c]
      val role = UNIQUE_ROLES.getOrNull(ri)
      val s = if (role != null) scores[c][role] ?: 0.0 else 0.0
      // An assignment nobody wanted is IGNORE, not a wrong guess with a shrug.
      if (role != null && s >= MIN_ROLE_SCORE) { roles[c] = role; conf[c] = s }
      else {
        // A column the solver could not place may still be a NOTES column, which is not unique.
        val notes = scores[c][ColumnRole.NOTES] ?: 0.0
        if (notes >= MIN_ROLE_SCORE) { roles[c] = ColumnRole.NOTES; conf[c] = notes }
        else { roles[c] = ColumnRole.IGNORE; conf[c] = 0.0 }
      }
    }
    return Assignment(roles, conf, headerRow)
  }

  /** Below this, no role is claimed — an unassigned column is better than a wrong one. */
  private const val MIN_ROLE_SCORE = 0.35

  /** Headings that name an exercise column unambiguously. */
  private val STRONG_EXERCISE_HEADERS = setOf(
    "exercise", "movement", "lift", "drill", "übung", "ubung", "ejercicio", "exercice")

  /** Headings that MIGHT name an exercise column, and might name anything at all. */
  private val WEAK_EXERCISE_HEADERS = setOf("name", "ex", "item", "title")

  /**
   * Header text and content statistics, combined. Content is weighted heavily on purpose: headers
   * lie ("Load" over a column of RPEs), get merged, get renamed, or are absent entirely, whereas a
   * column of values between 1 and 10 that are almost all integers really is a set count.
   */
  private fun scoreColumn(grid: Grid, col: Int, headerRow: Int?): Map<ColumnRole, Double> {
    val header = headerRow?.let { vocab.lower(grid.cell(it, col).trim()) }.orEmpty()
    val start = (headerRow ?: -1) + 1
    val values = (start until grid.rowCount).map { grid.cell(it, col).trim() }.filter { it.isNotEmpty() }
    val stats = ColumnStats.of(values, grid, col, start)

    val out = LinkedHashMap<ColumnRole, Double>()
    fun put(role: ColumnRole, headerWeight: Double, contentWeight: Double) {
      val h = if (header.isBlank()) 0.0 else headerScore(header, role)
      // A header hit is decisive when present; content carries the column when it is not.
      val s = if (h > 0) 0.65 * h + 0.35 * contentWeight else contentWeight * headerWeight
      if (s > 0) out[role] = s.coerceIn(0.0, 1.0)
    }

    // The exercise column is the anchor everything else hangs off, so a WEAK header hint is not
    // enough on its own. "name" and "ex" are exercise headings in a coach's sheet and ordinary
    // column names in arbitrary data; a JSON dump of users has a "name" column too, and reading it
    // as the exercise column turns two people into two exercises with the id column as their set
    // count. A weak hint therefore needs the CONTENT to agree.
    val weakExerciseHeader = header.isNotBlank() &&
      WEAK_EXERCISE_HEADERS.any { it == header } &&
      !STRONG_EXERCISE_HEADERS.any { header.contains(it) }
    if (!(weakExerciseHeader && stats.exerciseLikeness < 0.5)) {
      put(ColumnRole.EXERCISE, 1.0, stats.exerciseLikeness)
    }
    put(ColumnRole.SETS, 1.0, stats.setsLikeness)
    put(ColumnRole.REPS, 1.0, stats.repsLikeness)
    put(ColumnRole.LOAD, 1.0, stats.loadLikeness)
    put(ColumnRole.PERCENT, 1.0, stats.percentLikeness)
    put(ColumnRole.RPE, 1.0, stats.rpeLikeness)
    put(ColumnRole.REST, 1.0, stats.restLikeness)
    put(ColumnRole.TEMPO, 1.0, stats.tempoLikeness)
    put(ColumnRole.NOTES, 1.0, stats.notesLikeness)
    put(ColumnRole.WEEK, 1.0, stats.weekLikeness)
    put(ColumnRole.DAY, 1.0, stats.dayLikeness)
    put(ColumnRole.DATE, 1.0, stats.dateLikeness)
    put(ColumnRole.SUPERSET, 1.0, stats.supersetLikeness)
    put(ColumnRole.ORDER, 1.0, stats.orderLikeness)
    put(ColumnRole.MUSCLE, 1.0, stats.muscleLikeness)
    return out
  }

  /** Header surface forms come from the SHARED vocabulary, so the CSV path cannot drift from this. */
  private fun headerScore(header: String, role: ColumnRole): Double {
    val key = when (role) {
      ColumnRole.EXERCISE -> "exercise"; ColumnRole.SETS -> "sets"; ColumnRole.REPS -> "reps"
      ColumnRole.LOAD -> "load"; ColumnRole.PERCENT -> "percent"; ColumnRole.RPE -> "rpe"
      ColumnRole.REST -> "rest"; ColumnRole.TEMPO -> "tempo"; ColumnRole.NOTES -> "notes"
      ColumnRole.WEEK -> "week"; ColumnRole.DAY -> "day"; ColumnRole.DATE -> "date"
      ColumnRole.SUPERSET -> "superset"; ColumnRole.ORDER -> "order"; ColumnRole.MUSCLE -> "muscle"
      ColumnRole.IGNORE -> return 0.0
    }
    val forms = vocab.columnRoleHints[key].orEmpty()
    return when {
      forms.any { it == header } -> 1.0
      forms.any { header.startsWith("$it ") || header.startsWith("$it(") } -> 0.9
      forms.any { header.contains(it) } -> 0.7
      else -> 0.0
    }
  }
}

/**
 * What a column's VALUES look like. Every likeness is in [0,1] and is computed from the values
 * alone, which is what lets a header-less sheet be read at all.
 */
internal class ColumnStats private constructor(
  val exerciseLikeness: Double, val setsLikeness: Double, val repsLikeness: Double,
  val loadLikeness: Double, val percentLikeness: Double, val rpeLikeness: Double,
  val restLikeness: Double, val tempoLikeness: Double, val notesLikeness: Double,
  val weekLikeness: Double, val dayLikeness: Double, val dateLikeness: Double,
  val supersetLikeness: Double, val orderLikeness: Double, val muscleLikeness: Double
) {
  companion object {
    private val vocab = ReaderVocabulary
    private val INT = Regex("""^\d{1,3}$""")
    private val RANGE = Regex("""^\d{1,3}\s*[-–]\s*\d{1,3}$""")
    private val PERCENT = Regex("""^\d{1,3}(\.\d+)?\s*%$""")
    private val WEIGHT = Regex("""(?i)^\d{1,4}(\.\d+)?\s*(kg|kgs|lb|lbs|#)?$""")
    private val TIME = Regex("""(?i)^(\d{1,3}\s*(s|sec|secs|m|min|mins)|\d{1,2}:\d{2})$""")
    private val TEMPO = Regex("""^([\dX]{4}|[\dX]-[\dX]-[\dX]-[\dX])$""")
    private val SUPERSET = Regex("""^[A-Ha-h]\d?$""")
    private val DATE = Regex("""^\d{1,4}[/.\-]\d{1,2}([/.\-]\d{1,4})?$""")
    private val WEEK = Regex("""(?i)^(w|wk|week)\s*\d{1,2}$|^\d{1,2}$""")
    private val DAY = Regex("""(?i)^(d|day|session|workout)\s*[\dA-Z]{1,3}$""")

    fun of(values: List<String>, grid: Grid, col: Int, start: Int): ColumnStats {
      if (values.isEmpty()) return ColumnStats(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
      val n = values.size.toDouble()
      fun frac(p: (String) -> Boolean) = values.count(p) / n

      val ints = values.mapNotNull { it.toIntOrNull() }
      val intFrac = ints.size / n
      val meanInt = if (ints.isEmpty()) 0.0 else ints.average()

      val resolves = frac { ExerciseIndex.find(it) != null }
      val wordy = frac { it.split(' ').size in 1..6 && it.any { c -> c.isLetter() } && !it.any { c -> c.isDigit() } }
      val longText = frac { it.split(' ').size > 6 }

      // A set count is a small integer, almost always 1-10, and almost never has a unit.
      val setsLike = if (intFrac > 0.7 && meanInt in 1.0..12.0) 0.55 + 0.35 * intFrac else 0.0
      // Reps are small integers OR ranges OR AMRAP OR a time.
      val repsLike = 0.9 * frac { INT.matches(it) || RANGE.matches(it) ||
        it.equals("amrap", true) || TIME.matches(it) }
      // A load is a bigger number, often with a unit.
      val loadLike = if (frac { WEIGHT.matches(it) } > 0.7 && meanInt > 15) 0.8 else
        0.6 * frac { WEIGHT.matches(it) && it.any { c -> c.isLetter() } }
      val percentLike = 0.95 * frac { PERCENT.matches(it) }
      // RPE lives in 5..10, usually with a decimal half.
      val rpeLike = if (intFrac + frac { it.toDoubleOrNull() != null } > 0.7 &&
        values.mapNotNull { it.toDoubleOrNull() }.all { it in 4.0..10.5 }) 0.75 else 0.0
      val restLike = 0.9 * frac { TIME.matches(it) }
      val tempoLike = 0.95 * frac { TEMPO.matches(it) }
      val notesLike = 0.7 * longText
      val weekLike = 0.8 * frac { WEEK.matches(it) }
      val dayLike = 0.85 * frac { DAY.matches(it) || vocab.weekdayFor(it) != null }
      val dateLike = 0.95 * frac { DATE.matches(it) }
      val supersetLike = 0.85 * frac { SUPERSET.matches(it) }
      // An order column counts up and repeats: 1,2,3,1,2,3.
      val orderLike = if (intFrac > 0.9 && ints.size >= 3 && ints.max() <= 30 &&
        ints.zipWithNext().count { (a, b) -> b == a + 1 } >= ints.size / 2) 0.7 else 0.0
      val muscleLike = 0.7 * frac { it.lowercase() in MUSCLES }

      // The exercise column is the one whose values are NAMES: resolvable, or at least wordy, and
      // never numeric. This is the anchor everything else hangs off, so it is scored generously
      // when the values resolve and harshly when they do not.
      // A column of words is only an EXERCISE column when the words are movements. Without that
      // check a shopping list ("Item / Eggs / Milk / Oats") scores as a program and imports as one,
      // because "Eggs" is a perfectly good one-word name and "12" is a perfectly good set count.
      // A named header overrides this — see scoreColumn, where a header hit dominates the blend.
      val exerciseLike = when {
        resolves > 0.5 -> 0.85 + 0.15 * resolves
        resolves > 0.2 -> 0.6 + resolves
        wordy > 0.6 && intFrac < 0.1 -> 0.30
        else -> 0.0
      }.coerceAtMost(1.0)

      return ColumnStats(
        exerciseLike, setsLike, repsLike, loadLike, percentLike, rpeLike, restLike,
        tempoLike, notesLike, weekLike, dayLike, dateLike, supersetLike, orderLike, muscleLike)
    }

    private val MUSCLES = setOf(
      "chest", "back", "legs", "shoulders", "arms", "biceps", "triceps", "quads", "hamstrings",
      "glutes", "calves", "abs", "core", "lats", "traps", "delts", "forearms", "full body")
  }
}

/**
 * The Hungarian (Kuhn-Munkres) algorithm for minimum-cost assignment on a square matrix.
 *
 * Used here for one reason: greedy assignment gets column roles wrong in a way that is invisible.
 * Given columns headed `Set` and `Sets`, greedy takes whichever it reaches first and leaves the
 * other unassigned; given a sheet where the LOAD column looks slightly more like REPS than the real
 * reps column does, greedy takes it and the whole sheet shifts by one. Solving the assignment as a
 * whole cannot do either.
 *
 * The O(n^3) Jonker-Volgenant style implementation with potentials; n is the number of columns,
 * which is a handful.
 */
internal object Hungarian {

  /** @return for each row, the column it is assigned to (or -1). */
  fun solve(cost: Array<DoubleArray>): IntArray {
    val n = cost.size
    if (n == 0) return IntArray(0)
    val m = cost[0].size
    val u = DoubleArray(n + 1)
    val v = DoubleArray(m + 1)
    val p = IntArray(m + 1)
    val way = IntArray(m + 1)

    for (i in 1..n) {
      p[0] = i
      var j0 = 0
      val minv = DoubleArray(m + 1) { Double.MAX_VALUE }
      val used = BooleanArray(m + 1)
      do {
        used[j0] = true
        val i0 = p[j0]
        var delta = Double.MAX_VALUE
        var j1 = 0
        for (j in 1..m) {
          if (used[j]) continue
          val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
          if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
          if (minv[j] < delta) { delta = minv[j]; j1 = j }
        }
        for (j in 0..m) {
          if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
        }
        j0 = j1
      } while (p[j0] != 0)
      do {
        val j1 = way[j0]
        p[j0] = p[j1]
        j0 = j1
      } while (j0 != 0)
    }

    val result = IntArray(n) { -1 }
    for (j in 1..m) if (p[j] in 1..n) result[p[j] - 1] = j - 1
    return result
  }
}
