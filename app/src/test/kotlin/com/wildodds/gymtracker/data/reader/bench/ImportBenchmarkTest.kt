package com.wildodds.gymtracker.data.reader.bench

import com.wildodds.gymtracker.data.importer.layout.WorkbookFixtures
import com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.reader.ProgramReader
import com.wildodds.gymtracker.data.reader.ingest.ExtractorRegistry
import com.wildodds.gymtracker.data.reader.ingest.OcrEngine
import com.wildodds.gymtracker.data.reader.ingest.OcrExtractor
import com.wildodds.gymtracker.data.reader.ingest.OcrLine
import com.wildodds.gymtracker.data.reader.ingest.PageImage
import com.wildodds.gymtracker.data.reader.ingest.PageImageSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The import ACCURACY benchmark: documents with answer keys, scored field by field.
 *
 * The corpus test (CorpusTest) pins behaviour and measures whether something parsed. This measures
 * whether it parsed RIGHT — every day, every exercise name, every set count and rep target compared
 * with what a person reading the document would write down. That is the number a user feels.
 *
 * Documents come from three places:
 *  - `bench/text-pack.txt` and `bench/grid-pack.txt`: text-shaped documents (pasted text, .txt,
 *    .md, .csv, .tsv), each with its answer key inline.
 *  - [workbooks]: real .xlsx files built in code, in the layouts coaches actually use.
 *  - [screenshots]: OCR output shaped exactly like ML Kit's — one fragment per visual cell, with
 *    geometry and the usual confusables — fed through the real OcrExtractor.
 *
 * Run: `./gradlew :app:testOfflineDebugUnitTest --tests '*ImportBenchmarkTest*'`. The report is
 * printed and written to build/reports/import-benchmark.txt. The floor in [benchmarkMeetsTheFloor]
 * only ever moves up.
 */
class ImportBenchmarkTest {

  // ── answer keys ──────────────────────────────────────────────────────────────

  data class ExpectedExercise(val name: String, val sets: String, val reps: String)
  data class ExpectedDay(val label: String, val exercises: List<ExpectedExercise>)
  /** week number → its training days, in order. */
  data class Expected(val weeks: Map<Int, List<ExpectedDay>>)

  sealed class Source {
    data class Bytes(val fileName: String, val bytes: ByteArray) : Source()
    data class Ocr(val lines: List<OcrLine>) : Source()
  }

  data class BenchDoc(val id: String, val format: String, val source: Source, val expected: Expected)

  // ── result model ─────────────────────────────────────────────────────────────

  data class DocScore(
    val doc: BenchDoc,
    val expectedExercises: Int,
    val namesRight: Int,
    val setsRight: Int,
    val repsRight: Int,
    val extras: Int,
    val daysExpected: Int,
    val daysRight: Int,
    val problems: List<String>,
    val crashed: String? = null
  ) {
    val perfect: Boolean get() = crashed == null && problems.isEmpty()
    /** Every field of every exercise right — the per-document score a user actually experiences. */
    val fieldAccuracy: Double
      get() = if (expectedExercises == 0) 0.0
      else (namesRight + setsRight + repsRight).toDouble() / (3 * expectedExercises)
  }

  // ── loading ──────────────────────────────────────────────────────────────────

  private fun resource(path: String): String =
    javaClass.classLoader!!.getResourceAsStream(path)!!.readBytes().toString(Charsets.UTF_8)

  /** "=== name" … "--- expect" … blocks. Lines starting with '#' before the first block are comments. */
  private fun loadPack(path: String): List<BenchDoc> {
    val docs = ArrayList<BenchDoc>()
    val lines = resource(path).replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size && !lines[i].startsWith("=== ")) i++
    while (i < lines.size) {
      val name = lines[i].removePrefix("=== ").trim(); i++
      val body = StringBuilder()
      while (i < lines.size && lines[i] != "--- expect") { body.append(lines[i]).append('\n'); i++ }
      i++ // skip "--- expect"
      val key = ArrayList<String>()
      while (i < lines.size && !lines[i].startsWith("=== ")) { key += lines[i]; i++ }
      val text = body.toString().trimEnd() + "\n"
      docs += BenchDoc(name, name.substringAfterLast('.', "txt"),
        Source.Bytes(name, text.toByteArray(Charsets.UTF_8)), parseKey(key))
    }
    return docs
  }

  private fun parseKey(lines: List<String>): Expected {
    val weeks = LinkedHashMap<Int, MutableList<ExpectedDay>>()
    var week = 1
    var dayLabel: String? = null
    var exercises = ArrayList<ExpectedExercise>()
    fun flush() {
      dayLabel?.let { weeks.getOrPut(week) { ArrayList() } += ExpectedDay(it, exercises) }
      dayLabel = null; exercises = ArrayList()
    }
    for (raw in lines) {
      val l = raw.trim()
      when {
        l.isEmpty() || l.startsWith("#") -> {}
        l.startsWith("WEEK ") -> { flush(); week = l.removePrefix("WEEK ").trim().toInt() }
        l.startsWith("DAY") -> { flush(); dayLabel = l.removePrefix("DAY").trim() }
        else -> {
          val parts = l.split("|").map { it.trim() }
          require(parts.size == 3) { "bad key line: $l" }
          if (dayLabel == null) dayLabel = "1"
          exercises += ExpectedExercise(parts[0], parts[1], parts[2])
        }
      }
    }
    flush()
    return Expected(weeks)
  }

  private fun key(vararg lines: String) = parseKey(lines.toList())

  // ── workbooks: the layouts coaches actually ship ─────────────────────────────

  private fun workbooks(): List<BenchDoc> {
    fun xlsx(id: String, expected: Expected, vararg sheets: WorkbookFixtures.Sheet) =
      BenchDoc(id, "xlsx", Source.Bytes("$id.xlsx", WorkbookFixtures.workbook(*sheets)), expected)
    fun sheet(name: String, vararg rows: List<String>) = WorkbookFixtures.Sheet(name, rows.toList())

    val pushPullKey = key(
      "DAY Push", "Bench Press | 4 | 6-8", "Overhead Press | 3 | 8-10", "Tricep Pushdown | 3 | 12",
      "DAY Pull", "Barbell Row | 4 | 8", "Lat Pulldown | 3 | 10-12", "Barbell Curl | 3 | 12"
    )
    return listOf(
      // One row per exercise with a Day column.
      xlsx("xlsx-day-column", pushPullKey, sheet("Program",
        listOf("Day", "Exercise", "Sets", "Reps", "Rest"),
        listOf("Push", "Bench Press", "4", "6-8", "2 min"),
        listOf("Push", "Overhead Press", "3", "8-10", "90s"),
        listOf("Push", "Tricep Pushdown", "3", "12", "60s"),
        listOf("Pull", "Barbell Row", "4", "8", "2 min"),
        listOf("Pull", "Lat Pulldown", "3", "10-12", "90s"),
        listOf("Pull", "Barbell Curl", "3", "12", "60s"))),
      // Day only on the first row of its block (how a merged/visual sheet reads).
      xlsx("xlsx-day-first-row-only", pushPullKey, sheet("Program",
        listOf("Day", "Exercise", "Sets", "Reps"),
        listOf("Push", "Bench Press", "4", "6-8"),
        listOf("", "Overhead Press", "3", "8-10"),
        listOf("", "Tricep Pushdown", "3", "12"),
        listOf("Pull", "Barbell Row", "4", "8"),
        listOf("", "Lat Pulldown", "3", "10-12"),
        listOf("", "Barbell Curl", "3", "12"))),
      // Day header rows separating blocks, title rows above the header.
      xlsx("xlsx-day-header-blocks", pushPullKey, sheet("Sheet1",
        listOf("COACH JAMIE — 8 WEEK BLOCK"),
        listOf(""),
        listOf("DAY 1 - PUSH"),
        listOf("Exercise", "Sets", "Reps", "Notes"),
        listOf("Bench Press", "4", "6-8", "pause first rep"),
        listOf("Overhead Press", "3", "8-10", ""),
        listOf("Tricep Pushdown", "3", "12", ""),
        listOf(""),
        listOf("DAY 2 - PULL"),
        listOf("Exercise", "Sets", "Reps", "Notes"),
        listOf("Barbell Row", "4", "8", ""),
        listOf("Lat Pulldown", "3", "10-12", ""),
        listOf("Barbell Curl", "3", "12", ""))),
      // One sheet per day.
      xlsx("xlsx-sheet-per-day", pushPullKey,
        sheet("Push", listOf("Exercise", "Sets", "Reps"),
          listOf("Bench Press", "4", "6-8"), listOf("Overhead Press", "3", "8-10"), listOf("Tricep Pushdown", "3", "12")),
        sheet("Pull", listOf("Exercise", "Sets", "Reps"),
          listOf("Barbell Row", "4", "8"), listOf("Lat Pulldown", "3", "10-12"), listOf("Barbell Curl", "3", "12"))),
      // Sets x reps in one cell.
      xlsx("xlsx-setsreps-one-cell", pushPullKey, sheet("Plan",
        listOf("Day", "Exercise", "Prescription"),
        listOf("Push", "Bench Press", "4x6-8"),
        listOf("Push", "Overhead Press", "3x8-10"),
        listOf("Push", "Tricep Pushdown", "3x12"),
        listOf("Pull", "Barbell Row", "4x8"),
        listOf("Pull", "Lat Pulldown", "3x10-12"),
        listOf("Pull", "Barbell Curl", "3x12"))),
      // Weeks as columns, each cell "sets x reps".
      xlsx("xlsx-week-columns", key(
        "WEEK 1", "DAY 1", "Squat | 3 | 5", "Bench Press | 3 | 8", "Deadlift | 1 | 5",
        "WEEK 2", "DAY 1", "Squat | 3 | 4", "Bench Press | 3 | 6", "Deadlift | 1 | 4",
        "WEEK 3", "DAY 1", "Squat | 3 | 3", "Bench Press | 3 | 5", "Deadlift | 1 | 3"
      ), sheet("Block",
        listOf("Exercise", "Week 1", "Week 2", "Week 3"),
        listOf("Squat", "3x5", "3x4", "3x3"),
        listOf("Bench Press", "3x8", "3x6", "3x5"),
        listOf("Deadlift", "1x5", "1x4", "1x3"))),
      // One row per SET (how logging apps and some coaches export).
      xlsx("xlsx-row-per-set", key(
        "DAY 1", "Squat | 3 | 5", "Bench Press | 3 | 8"
      ), sheet("Log",
        listOf("Day", "Exercise", "Set", "Reps", "Weight"),
        listOf("1", "Squat", "1", "5", "100"),
        listOf("1", "Squat", "2", "5", "100"),
        listOf("1", "Squat", "3", "5", "100"),
        listOf("1", "Bench Press", "1", "8", "70"),
        listOf("1", "Bench Press", "2", "8", "70"),
        listOf("1", "Bench Press", "3", "8", "70"))),
      // Week + Day columns, multi-week.
      xlsx("xlsx-week-day-columns", key(
        "WEEK 1", "DAY 1", "Squat | 4 | 6", "Leg Curl | 3 | 10", "DAY 2", "Bench Press | 4 | 6", "Barbell Row | 3 | 10",
        "WEEK 2", "DAY 1", "Squat | 4 | 5", "Leg Curl | 3 | 12", "DAY 2", "Bench Press | 4 | 5", "Barbell Row | 3 | 12"
      ), sheet("Program",
        listOf("Week", "Day", "Exercise", "Sets", "Reps"),
        listOf("1", "1", "Squat", "4", "6"),
        listOf("1", "1", "Leg Curl", "3", "10"),
        listOf("1", "2", "Bench Press", "4", "6"),
        listOf("1", "2", "Barbell Row", "3", "10"),
        listOf("2", "1", "Squat", "4", "5"),
        listOf("2", "1", "Leg Curl", "3", "12"),
        listOf("2", "2", "Bench Press", "4", "5"),
        listOf("2", "2", "Barbell Row", "3", "12"))),
      // Days side by side as column blocks (a common "visual" coach sheet).
      xlsx("xlsx-days-side-by-side", key(
        "DAY Upper", "Bench Press | 4 | 6", "Barbell Row | 4 | 8", "Overhead Press | 3 | 10",
        "DAY Lower", "Squat | 4 | 6", "Romanian Deadlift | 3 | 8", "Leg Press | 3 | 12"
      ), sheet("Week",
        listOf("UPPER", "", "", "", "LOWER", "", ""),
        listOf("Exercise", "Sets", "Reps", "", "Exercise", "Sets", "Reps"),
        listOf("Bench Press", "4", "6", "", "Squat", "4", "6"),
        listOf("Barbell Row", "4", "8", "", "Romanian Deadlift", "3", "8"),
        listOf("Overhead Press", "3", "10", "", "Leg Press", "3", "12")))
    )
  }

  // ── screenshots: what ML Kit actually hands back ─────────────────────────────

  private class ScriptedEngine(private val lines: List<OcrLine>) : OcrEngine {
    override fun recognise(image: PageImage): List<OcrLine> = lines
  }

  private class OnePage(private val w: Int = 1080, private val h: Int = 2400) : PageImageSource {
    override fun pageCount() = 1
    override fun render(pageIndex: Int, targetWidthPx: Int) =
      PageImage(pageIndex, w, h, IntArray(w * h) { 0xFFFFFFFF.toInt() })
  }

  /**
   * A screenshot as ML Kit returns it. [rows] are visual rows; each string in a row is one
   * separately-recognised fragment at the matching x in [columns]. Baselines wobble by a few pixels
   * across a row, exactly as real recognition does.
   */
  private fun screenshot(id: String, expected: Expected, columns: List<Int>, rows: List<List<String>>): BenchDoc {
    val out = ArrayList<OcrLine>()
    var y = 180
    rows.forEachIndexed { r, row ->
      if (row.isEmpty()) { y += 40; return@forEachIndexed }
      row.forEachIndexed { c, text ->
        if (text.isBlank()) return@forEachIndexed
        val left = columns.getOrElse(c) { columns.last() + 120 * (c - columns.size + 1) }
        val wobble = ((r * 7 + c * 3) % 5) - 2
        val width = (text.length * 22).coerceAtLeast(30)
        out += OcrLine(text, 0.93, left, y + wobble, left + width, y + wobble + 42)
      }
      y += 70
    }
    return BenchDoc(id, "screenshot", Source.Ocr(out), expected)
  }

  private fun screenshots(): List<BenchDoc> = listOf(
    // A notes-app screenshot: one line of text per row, a single fragment each.
    screenshot("shot-notes-app", key(
      "DAY Push", "Bench Press | 4 | 8", "Incline Dumbbell Press | 3 | 10", "Cable Fly | 3 | 15",
      "DAY Pull", "Pull Up | 4 | 8", "Barbell Row | 4 | 10", "Hammer Curl | 3 | 12"
    ), listOf(60), listOf(
      listOf("Push day"), listOf("Bench press 4x8"), listOf("Incline DB press 3x10"), listOf("Cable fly 3x15"),
      emptyList(),
      listOf("Pull day"), listOf("Pull ups 4x8"), listOf("Barbell row 4x10"), listOf("Hammer curls 3x12")
    )),
    // A table screenshot: every cell is its own fragment.
    screenshot("shot-table", key(
      "DAY Upper", "Bench Press | 4 | 6-8", "Barbell Row | 4 | 8-10", "Overhead Press | 3 | 10", "Lateral Raise | 3 | 15"
    ), listOf(60, 640, 820), listOf(
      listOf("UPPER BODY"),
      listOf("Exercise", "Sets", "Reps"),
      listOf("Bench Press", "4", "6-8"),
      listOf("Barbell Row", "4", "8-10"),
      listOf("Overhead Press", "3", "10"),
      listOf("Lateral Raise", "3", "15")
    )),
    // A coaching-app screenshot: name on one line, the prescription on the next, smaller.
    screenshot("shot-app-cards", key(
      "DAY Lower", "Back Squat | 4 | 5", "Romanian Deadlift | 3 | 8", "Walking Lunge | 3 | 12"
    ), listOf(60), listOf(
      listOf("Lower Body"),
      listOf("Back Squat"), listOf("4 sets × 5 reps"),
      listOf("Romanian Deadlift"), listOf("3 sets × 8 reps"),
      listOf("Walking Lunges"), listOf("3 sets × 12 reps")
    )),
    // A table screenshot with OCR confusables in the numbers.
    screenshot("shot-table-confusables", key(
      "DAY Legs", "Squat | 5 | 5", "Leg Press | 4 | 10", "Leg Curl | 3 | 12", "Calf Raise | 4 | 15"
    ), listOf(60, 640, 820), listOf(
      listOf("LEGS"),
      listOf("Exercise", "Sets", "Reps"),
      listOf("Squat", "5", "S"),
      listOf("Leg Press", "4", "l0"),
      listOf("Leg Curl", "3", "l2"),
      listOf("Calf Raise", "4", "l5")
    )),
    // Two days side by side in one screenshot (a 2-column layout).
    screenshot("shot-two-columns", key(
      "DAY Day 1", "Squat | 3 | 5", "Bench Press | 3 | 5",
      "DAY Day 2", "Deadlift | 1 | 5", "Overhead Press | 3 | 5"
    ), listOf(60, 560), listOf(
      listOf("Day 1", "Day 2"),
      listOf("Squat 3x5", "Deadlift 1x5"),
      listOf("Bench Press 3x5", "Overhead Press 3x5")
    ))
  )

  // ── running + scoring ────────────────────────────────────────────────────────

  private fun run(doc: BenchDoc): ParsedProgram = when (val s = doc.source) {
    is Source.Bytes -> ProgramReader().read(s.bytes, s.fileName).importResult.program
    is Source.Ocr -> {
      val registry = ExtractorRegistry.pureOnly().plus(listOf(OcrExtractor(ScriptedEngine(s.lines), OnePage())))
      // A real PNG signature so the sniffer routes it exactly as it would a camera-roll image.
      val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
      ProgramReader(registry).read(png, "${doc.id}.png").importResult.program
    }
  }

  private fun norm(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
      .split(" ").filter { it.isNotEmpty() }
      .map { w -> if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w }
      .joinToString(" ")

  private fun canon(s: String): String = ExerciseIndex.find(s)?.canonical?.let(::norm) ?: norm(s)

  private fun nameMatches(expected: String, actual: String): Boolean =
    norm(expected) == norm(actual) || canon(expected) == canon(actual) ||
      // Same words, different order: "Triceps Rope Pushdown" is "Rope Tricep Pushdown".
      norm(expected).split(' ').toSet() == norm(actual).split(' ').toSet()

  /** "8 – 12 reps" → "8-12"; "1 min" → "60s"; "to failure" → "amrap"; "12, 10, 8" → "12/10/8". */
  private fun normReps(raw: String): String {
    var s = raw.lowercase().trim()
      .replace('–', '-').replace('—', '-')
      .replace(Regex("\\b(reps?|repetitions?)\\b"), "")
      .replace(Regex("\\s*(each( side| leg| arm)?|per (side|leg|arm)|/\\s*(side|leg|arm)|e/?s)\\b.*$"), "")
      .trim()
    if (s.matches(Regex("(amrap|max|failure|to failure|f|max reps|af|amap)\\+?"))) return "amrap"
    Regex("^(\\d+(?:\\.\\d+)?)\\s*(min|mins|minute|minutes|m(?=$))$").find(s)?.let { m ->
      if (m.groupValues[2] != "m") return "${(m.groupValues[1].toDouble() * 60).toInt()}s"
    }
    s = s.replace(Regex("\\s*(sec|secs|second|seconds)\\b"), "s")
      .replace(Regex("\\s*(metres|meters|metre|meter)\\b"), "m")
      .replace(Regex("\\s+"), "")
    if (s.contains(',') || s.count { it == '/' } >= 1) {
      s = s.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
    }
    return s
  }

  private fun setsMatch(expected: String, actual: Int): Boolean {
    val range = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(expected.trim())
    return if (range != null) actual in range.groupValues[1].toInt()..range.groupValues[2].toInt()
    else expected.trim().toIntOrNull() == actual
  }

  private fun repsMatch(expected: String, actual: String): Boolean {
    val e = normReps(expected)
    val a = normReps(actual)
    if (e == a) return true
    // A ladder whose rungs are all equal is the plain count: "5/5/5" == "5".
    val rungs = a.split('/')
    return rungs.size > 1 && rungs.all { it == e }
  }

  private fun score(doc: BenchDoc): DocScore {
    val program = try { run(doc) } catch (e: Throwable) {
      val n = doc.expected.weeks.values.sumOf { d -> d.sumOf { it.exercises.size } }
      return DocScore(doc, n, 0, 0, 0, 0, doc.expected.weeks.values.sumOf { it.size }, 0,
        listOf("CRASH ${e::class.java.simpleName}: ${e.message?.take(120)}"), crashed = e::class.java.simpleName)
    }
    val problems = ArrayList<String>()
    var expectedN = 0; var names = 0; var sets = 0; var reps = 0; var extras = 0
    var daysExpected = 0; var daysRight = 0

    val training = program.sessions.filter { it.sessionType == "TRAINING" && it.exercises.isNotEmpty() }
    for ((week, days) in doc.expected.weeks) {
      val actualDays = training.filter { it.weekNumber == week }.sortedBy { it.dayNumber }
      daysExpected += days.size
      if (actualDays.size == days.size) daysRight += days.size
      else problems += "week $week: expected ${days.size} day(s), got ${actualDays.size}" +
        " [${actualDays.joinToString { "\"${it.name}\"(${it.exercises.size})" }}]"

      days.forEachIndexed { d, day ->
        val actual = actualDays.getOrNull(d)?.exercises?.sortedBy { it.orderIndex }.orEmpty()
        val used = BooleanArray(actual.size)
        day.exercises.forEachIndexed { e, exp ->
          expectedN++
          var idx = actual.indices.firstOrNull { !used[it] && nameMatches(exp.name, actual[it].name) }
          val byName = idx != null
          if (idx == null && e < actual.size && !used[e]) idx = e
          if (idx == null) { problems += "W$week D${d + 1}: MISSING ${exp.name} ${exp.sets}x${exp.reps}"; return@forEachIndexed }
          used[idx] = true
          val a = actual[idx]
          if (byName) names++ else problems += "W$week D${d + 1}: NAME '${exp.name}' read as '${a.name}'"
          if (setsMatch(exp.sets, a.sets)) sets++ else problems += "W$week D${d + 1}: SETS ${exp.name} expected ${exp.sets} got ${a.sets}"
          if (repsMatch(exp.reps, a.repsTarget)) reps++ else problems += "W$week D${d + 1}: REPS ${exp.name} expected ${exp.reps} got '${a.repsTarget}'"
        }
        actual.indices.filter { !used[it] }.forEach {
          extras++; problems += "W$week D${d + 1}: EXTRA '${actual[it].name}' ${actual[it].sets}x${actual[it].repsTarget}"
        }
      }
    }
    return DocScore(doc, expectedN, names, sets, reps, extras, daysExpected, daysRight, problems)
  }

  private fun allDocs(): List<BenchDoc> =
    loadPack("reader/bench/text-pack.txt") + loadPack("reader/bench/grid-pack.txt") + workbooks() + screenshots() +
      loadPack("reader/bench/holdout-pack.txt")

  data class Board(
    val docs: Int, val perfect: Int, val exercises: Int,
    val names: Double, val sets: Double, val reps: Double, val days: Double, val extras: Int, val crashes: Int
  ) {
    val perfectRate: Double get() = perfect.toDouble() / docs
    val fieldAccuracy: Double get() = (names + sets + reps) / 3
  }

  private fun board(scores: List<DocScore>): Board {
    val ex = scores.sumOf { it.expectedExercises }.coerceAtLeast(1)
    return Board(
      docs = scores.size,
      perfect = scores.count { it.perfect },
      exercises = ex,
      names = scores.sumOf { it.namesRight }.toDouble() / ex,
      sets = scores.sumOf { it.setsRight }.toDouble() / ex,
      reps = scores.sumOf { it.repsRight }.toDouble() / ex,
      days = scores.sumOf { it.daysRight }.toDouble() / scores.sumOf { it.daysExpected }.coerceAtLeast(1),
      extras = scores.sumOf { it.extras },
      crashes = scores.count { it.crashed != null }
    )
  }

  private fun pct(d: Double) = "%5.1f%%".format(d * 100)

  private fun report(scores: List<DocScore>): String = buildString {
    val all = board(scores)
    appendLine("IMPORT ACCURACY BENCHMARK")
    appendLine("  documents          ${all.docs}   perfect ${all.perfect} (${pct(all.perfectRate)})   crashes ${all.crashes}")
    appendLine("  exercises          ${all.exercises}")
    appendLine("  names right        ${pct(all.names)}")
    appendLine("  sets right         ${pct(all.sets)}")
    appendLine("  reps right         ${pct(all.reps)}")
    appendLine("  day split right    ${pct(all.days)}")
    appendLine("  extra exercises    ${all.extras}")
    appendLine("  FIELD ACCURACY     ${pct(all.fieldAccuracy)}")
    appendLine()
    appendLine("  by format:")
    scores.groupBy { it.doc.format }.toSortedMap().forEach { (fmt, s) ->
      val b = board(s)
      appendLine("    %-11s %3d docs  perfect %s  fields %s".format(fmt, b.docs, pct(b.perfectRate), pct(b.fieldAccuracy)))
    }
    appendLine()
    scores.filter { !it.perfect }.sortedBy { it.fieldAccuracy }.forEach { s ->
      appendLine("✗ ${s.doc.id}  (${pct(s.fieldAccuracy)})")
      s.problems.take(12).forEach { appendLine("    $it") }
      if (s.problems.size > 12) appendLine("    … ${s.problems.size - 12} more")
    }
  }

  @Test
  fun benchmarkMeetsTheFloor() {
    val scores = allDocs().map(::score)
    val text = report(scores)
    println(text)
    File("build/reports").mkdirs()
    File("build/reports/import-benchmark.txt").writeText(text)
    val b = board(scores)
    assertTrue("crashes: ${scores.filter { it.crashed != null }.map { it.doc.id }}", b.crashes == 0)
    assertTrue("field accuracy ${pct(b.fieldAccuracy)} fell below the floor", b.fieldAccuracy >= FLOOR_FIELD_ACCURACY)
    assertTrue("perfect-document rate ${pct(b.perfectRate)} fell below the floor", b.perfectRate >= FLOOR_PERFECT_RATE)
  }

  companion object {
    // Raised as the reader improves; never lowered.
    const val FLOOR_FIELD_ACCURACY = 0.98
    const val FLOOR_PERFECT_RATE = 0.92
  }
}
