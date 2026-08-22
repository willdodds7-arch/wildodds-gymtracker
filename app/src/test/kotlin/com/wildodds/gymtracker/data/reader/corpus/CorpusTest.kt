package com.wildodds.gymtracker.data.reader.corpus

import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.ResolutionStatus
import com.wildodds.gymtracker.data.reader.ProgramReader
import com.wildodds.gymtracker.data.reader.ingest.IngestError
import com.wildodds.gymtracker.data.reader.ingest.MiniJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The golden corpus: 158 documents, one expected-draft snapshot each, and a scoreboard.
 *
 * The 63 tests the subsystem had were a floor, not a target — they pinned behaviours someone had
 * already thought of. A corpus is different in kind: it is a fixed body of real-shaped documents
 * that produces a NUMBER, and that number is the thing to optimise. Every phase of this rewrite was
 * measured against it.
 *
 * Two failure modes, both caught here:
 *  - a **snapshot** regression: one document now reads differently than it used to. The diff shows
 *    exactly which exercise moved, and whether that is an improvement or a bug is a human decision.
 *  - a **scoreboard** regression: overall parse rate, mean confidence, resolution hit rate or
 *    unconsumed-character rate got worse. That is never an improvement.
 *
 * Regenerating: `-Dreader.corpus.update=true` rewrites both files. Doing that carelessly defeats the
 * whole test, so the diff it produces is the review — which is why the snapshots are one file rather
 * than 158, and why they are compact enough to read.
 */
class CorpusTest {

  private val reader = ProgramReader()

  private val json = Json { prettyPrint = true; encodeDefaults = true }

  // ── the snapshot model ───────────────────────────────────────────────────────

  /**
   * One document's reading, compactly. Not the whole draft: a full serialisation would be unreadable
   * in a diff and would churn on every internal field rename. This is what a REVIEWER needs to see
   * to judge whether a change is an improvement — the shape of the program and every number in it.
   */
  @Serializable
  data class Snapshot(
    val doc: String,
    val outcome: String,           // "parsed" | "blocked" | "error:<type>"
    val route: String = "",
    val name: String = "",
    val weeks: Int = 0,
    val sessions: Int = 0,
    val restDays: Int = 0,
    val exercises: Int = 0,
    val resolvedExactly: Int = 0,
    /** Confidence, bucketed to 5% — a tiny scoring tweak should not churn 158 snapshots. */
    val confidenceBucket: Int = 0,
    val flags: List<String> = emptyList(),
    /** "Bench Press 3x8 @80kg" per exercise, in order. The readable heart of the snapshot. */
    val rows: List<String> = emptyList()
  )

  @Serializable
  data class Scoreboard(
    val documents: Int,
    val parsed: Int,
    val blocked: Int,
    val errors: Int,
    /** Fraction of documents that produced at least one exercise. */
    val parseRate: Double,
    val meanConfidence: Double,
    /** Fraction of exercise names resolved EXACT or ALIAS. */
    val resolutionHitRate: Double,
    /** Mean fraction of source-line characters no recognised token consumed. */
    val unconsumedRate: Double,
    val totalExercises: Int
  )

  // ── the run ──────────────────────────────────────────────────────────────────

  private data class Reading(val snapshot: Snapshot, val draft: ProgramImportDraft?)

  private fun readAll(): List<Reading> = corpusFiles().map { file ->
    val rel = file.relativeTo(corpusRoot()).path.replace('\\', '/')
    val bytes = file.readBytes()
    try {
      val r = reader.read(bytes, file.name)
      val draft = r.importResult.draft
      val slots = draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }
      val blocked = r.importResult.flags.any {
        it.severity == com.wildodds.gymtracker.data.importer.FlagSeverity.BLOCKER
      }
      Reading(Snapshot(
        doc = rel,
        outcome = if (blocked) "blocked" else "parsed",
        route = r.route,
        name = draft.name,
        weeks = draft.weeks.size,
        sessions = draft.weeks.sumOf { it.sessions.size },
        restDays = draft.weeks.sumOf { w ->
          w.sessions.count { it.type != com.wildodds.gymtracker.data.importer.SessionType.TRAINING } },
        exercises = slots.size,
        resolvedExactly = slots.count {
          it.exercise.status == ResolutionStatus.EXACT || it.exercise.status == ResolutionStatus.ALIAS
        },
        confidenceBucket = bucket(r.importResult.overallConfidence),
        flags = r.importResult.flags.map { it.severity.name + ":" + it.message.take(60) }.distinct().sorted(),
        rows = slots.map { describe(it) }
      ), draft)
    } catch (e: IngestError) {
      Reading(Snapshot(doc = rel, outcome = "error:" + e::class.java.simpleName), null)
    } catch (e: Exception) {
      // Anything that is not an IngestError is a contract violation, and the snapshot records it as
      // one rather than quietly absorbing it.
      Reading(Snapshot(doc = rel, outcome = "CRASH:" + e::class.java.simpleName + ":" + e.message?.take(60)), null)
    }
  }

  private fun describe(slot: com.wildodds.gymtracker.data.importer.SlotDraft): String {
    val p = slot.sets.firstOrNull()
    val sb = StringBuilder(slot.exercise.resolvedName.ifBlank { slot.exercise.original })
    p?.count?.let { sb.append(" ").append(it).append("x") }
    p?.reps?.let { r ->
      if (p.count == null) sb.append(" ")
      sb.append(when (r.kind) {
        com.wildodds.gymtracker.data.importer.RepKind.RANGE -> "${r.min}-${r.max}"
        com.wildodds.gymtracker.data.importer.RepKind.AMRAP -> "AMRAP"
        com.wildodds.gymtracker.data.importer.RepKind.AMRAP_MIN -> "${r.min}+"
        com.wildodds.gymtracker.data.importer.RepKind.TIME_SEC -> "${r.min}s"
        com.wildodds.gymtracker.data.importer.RepKind.DISTANCE_M -> "${r.min}m"
        com.wildodds.gymtracker.data.importer.RepKind.CALORIES -> "${r.min}cal"
        else -> r.min?.toString() ?: r.text
      })
    }
    p?.load?.takeIf { it.kind != com.wildodds.gymtracker.data.importer.LoadKind.NONE }?.let { l ->
      sb.append(" @").append(l.kind.name).append(l.value?.let { v -> ":" + fmt(v) } ?: "")
      l.valueMax?.let { sb.append("-").append(fmt(it)) }
      l.unit?.let { sb.append(it.name) }
    }
    if (p?.perSet?.isNotEmpty() == true) sb.append(" perSet=").append(p.perSet.size)
    if (p?.kind != null && p.kind != com.wildodds.gymtracker.data.importer.SetKind.STRAIGHT)
      sb.append(" [").append(p.kind.name).append("]")
    if (slot.modifiers.isNotEmpty()) sb.append(" +").append(slot.modifiers.joinToString("+"))
    if (slot.supersetGroup.isNotBlank()) sb.append(" ss=").append(slot.supersetGroup).append(slot.supersetOrder ?: "")
    slot.rest.lowSec?.let { sb.append(" rest=").append(it).append(slot.rest.highSec?.let { h -> "-$h" } ?: "") }
    if (p?.tempo?.isNotBlank() == true) sb.append(" tempo=").append(p.tempo)
    return sb.toString()
  }

  private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

  private fun bucket(c: Double) = (c * 20).toInt() * 5

  private fun scoreboardOf(readings: List<Reading>): Scoreboard {
    val parsed = readings.count { it.snapshot.outcome == "parsed" && it.snapshot.exercises > 0 }
    val blocked = readings.count { it.snapshot.outcome == "blocked" }
    val errors = readings.count { it.snapshot.outcome.startsWith("error") || it.snapshot.outcome.startsWith("CRASH") }
    val withExercises = readings.filter { it.snapshot.exercises > 0 }
    val totalEx = withExercises.sumOf { it.snapshot.exercises }
    val resolved = withExercises.sumOf { it.snapshot.resolvedExactly }
    val allSlots = readings.mapNotNull { it.draft }
      .flatMap { d -> d.weeks.flatMap { w -> w.sessions.flatMap { it.slots } } }
    val meanConfidence = if (allSlots.isEmpty()) 0.0 else allSlots.sumOf { it.confidence } / allSlots.size
    val unconsumed = if (allSlots.isEmpty()) 0.0
      else allSlots.sumOf { 1.0 - it.breakdown.notationCoverage } / allSlots.size
    return Scoreboard(
      documents = readings.size,
      parsed = parsed,
      blocked = blocked,
      errors = errors,
      parseRate = round3(parsed.toDouble() / readings.size),
      meanConfidence = round3(meanConfidence),
      resolutionHitRate = round3(if (totalEx == 0) 0.0 else resolved.toDouble() / totalEx),
      unconsumedRate = round3(unconsumed),
      totalExercises = totalEx
    )
  }

  private fun round3(d: Double) = kotlin.math.round(d * 1000) / 1000.0

  // ── the tests ────────────────────────────────────────────────────────────────

  @Test fun `the corpus is present and large enough to mean something`() {
    val files = corpusFiles()
    assertTrue("corpus not found at ${corpusRoot()} — run tools/build_corpus.py", files.isNotEmpty())
    assertTrue("expected at least 120 documents, found ${files.size}", files.size >= 120)
  }

  @Test fun `no document crashes the reader`() {
    // The contract is absolute: an IngestError is fine, anything else is a bug that would take the
    // app down while the user is holding a file they were told to try.
    val crashes = readAll().filter { it.snapshot.outcome.startsWith("CRASH") }
    assertTrue("documents crashed the reader:\n" + crashes.joinToString("\n") {
      "  ${it.snapshot.doc} -> ${it.snapshot.outcome}"
    }, crashes.isEmpty())
  }

  @Test fun `every document reads the same way it did before`() {
    val readings = readAll()
    val actual = readings.map { it.snapshot }
    val file = snapshotFile()

    if (shouldUpdate() || !file.exists()) {
      file.parentFile.mkdirs()
      file.writeText(json.encodeToString(actual))
      println("CORPUS: wrote ${actual.size} snapshots to ${file.path}")
      return
    }

    val expected = json.decodeFromString<List<Snapshot>>(file.readText())
    val expectedByDoc = expected.associateBy { it.doc }
    val diffs = ArrayList<String>()
    for (a in actual) {
      val e = expectedByDoc[a.doc]
      if (e == null) { diffs += "  NEW      ${a.doc}"; continue }
      if (e != a) diffs += describeDiff(e, a)
    }
    for (e in expected) if (actual.none { it.doc == e.doc }) diffs += "  MISSING  ${e.doc}"

    if (diffs.isNotEmpty()) {
      fail("${diffs.size} corpus document(s) read differently:\n" + diffs.joinToString("\n") +
        "\n\nIf these changes are improvements, re-run with -Dreader.corpus.update=true and " +
        "review the diff — the diff IS the review.")
    }
  }

  private fun describeDiff(e: Snapshot, a: Snapshot): String = buildString {
    append("  CHANGED  ").append(a.doc).append('\n')
    if (e.outcome != a.outcome) append("      outcome ${e.outcome} -> ${a.outcome}\n")
    if (e.route != a.route) append("      route ${e.route} -> ${a.route}\n")
    if (e.name != a.name) append("      name '${e.name}' -> '${a.name}'\n")
    if (e.weeks != a.weeks) append("      weeks ${e.weeks} -> ${a.weeks}\n")
    if (e.sessions != a.sessions) append("      sessions ${e.sessions} -> ${a.sessions}\n")
    if (e.restDays != a.restDays) append("      restDays ${e.restDays} -> ${a.restDays}\n")
    if (e.exercises != a.exercises) append("      exercises ${e.exercises} -> ${a.exercises}\n")
    if (e.resolvedExactly != a.resolvedExactly)
      append("      resolved ${e.resolvedExactly} -> ${a.resolvedExactly}\n")
    if (e.confidenceBucket != a.confidenceBucket)
      append("      confidence ${e.confidenceBucket}% -> ${a.confidenceBucket}%\n")
    if (e.rows != a.rows) {
      for (i in 0 until maxOf(e.rows.size, a.rows.size)) {
        val before = e.rows.getOrNull(i)
        val after = a.rows.getOrNull(i)
        if (before != after) append("      row $i: '$before' -> '$after'\n")
      }
    }
    if (e.flags != a.flags) {
      (a.flags - e.flags.toSet()).forEach { append("      +flag $it\n") }
      (e.flags - a.flags.toSet()).forEach { append("      -flag $it\n") }
    }
  }

  @Test fun `the scoreboard has not regressed`() {
    val board = scoreboardOf(readAll())
    val file = baselineFile()

    println(buildString {
      append("\n").append("═".repeat(66)).append("\n")
      append("READER CORPUS SCOREBOARD\n")
      append("═".repeat(66)).append("\n")
      append("  documents            ${board.documents}\n")
      append("  parsed               ${board.parsed}  (${(board.parseRate * 100).toInt()}%)\n")
      append("  blocked (correctly)  ${board.blocked}\n")
      append("  errors               ${board.errors}\n")
      append("  exercises read       ${board.totalExercises}\n")
      append("  mean confidence      ${(board.meanConfidence * 100).toInt()}%\n")
      append("  resolution hit rate  ${(board.resolutionHitRate * 100).toInt()}%\n")
      append("  unconsumed chars     ${(board.unconsumedRate * 100).toInt()}%\n")
      append("═".repeat(66)).append("\n")
    })

    if (shouldUpdate() || !file.exists()) {
      file.parentFile.mkdirs()
      file.writeText(json.encodeToString(board))
      println("CORPUS: wrote the scoreboard baseline to ${file.path}")
      return
    }

    val base = json.decodeFromString<Scoreboard>(file.readText())
    val problems = ArrayList<String>()
    // A small tolerance, because these are means over 158 documents and a rounding change should
    // not fail the build. A real regression moves them further than this.
    fun check(name: String, before: Double, now: Double, higherIsBetter: Boolean) {
      val delta = now - before
      val bad = if (higherIsBetter) delta < -0.01 else delta > 0.01
      if (bad) problems += "  $name: $before -> $now (${if (delta > 0) "+" else ""}${round3(delta)})"
    }
    check("parse rate", base.parseRate, board.parseRate, true)
    check("mean confidence", base.meanConfidence, board.meanConfidence, true)
    check("resolution hit rate", base.resolutionHitRate, board.resolutionHitRate, true)
    check("unconsumed rate", base.unconsumedRate, board.unconsumedRate, false)
    if (board.errors > base.errors)
      problems += "  errors: ${base.errors} -> ${board.errors}"

    if (problems.isNotEmpty()) {
      fail("the corpus scoreboard regressed:\n" + problems.joinToString("\n") +
        "\n\nIf this is a deliberate trade, re-run with -Dreader.corpus.update=true and say so in " +
        "the commit message.")
    }
  }

  // ── the numbers that matter, asserted as floors ──────────────────────────────

  @Test fun `the corpus parse rate clears its floor`() {
    val board = scoreboardOf(readAll())
    // Not 100%: fourteen documents are deliberately hostile and MUST NOT parse — a recipe, a
    // shopping list, an empty file, a blog post. Refusing them is the correct behaviour, so the
    // floor accounts for them rather than pretending they should read.
    assertTrue("parse rate fell to ${board.parseRate}", board.parseRate >= 0.80)
  }

  @Test fun `the corpus resolution hit rate clears its floor`() {
    val board = scoreboardOf(readAll())
    assertTrue("resolution hit rate fell to ${board.resolutionHitRate}", board.resolutionHitRate >= 0.70)
  }

  @Test fun `hostile documents do not become programs`() {
    val hostile = readAll().filter { it.snapshot.doc.startsWith("hostile/") }
    assertTrue("no hostile documents in the corpus", hostile.isNotEmpty())
    // A recipe and a shopping list must not import as programs. Some hostile documents legitimately
    // contain a program (the mixed-content note does), so this checks the ones that do not.
    val mustNotParse = listOf("01-recipe", "02-shopping-list", "03-empty", "04-whitespace",
      "05-lorem", "09-json-not-a-program", "10-html-blog")
    for (stem in mustNotParse) {
      val r = hostile.firstOrNull { it.snapshot.doc.contains(stem) } ?: continue
      assertTrue("${r.snapshot.doc} imported as a program with ${r.snapshot.exercises} exercises: " +
        r.snapshot.rows.take(3),
        r.snapshot.outcome != "parsed" || r.snapshot.exercises == 0)
    }
  }

  @Test fun `a corrupt file fails with a typed error, never a crash`() {
    val corrupt = readAll().filter { it.snapshot.doc.contains("corrupt") || it.snapshot.doc.contains("truncated") }
    assertTrue("no corrupt documents in the corpus", corrupt.isNotEmpty())
    for (r in corrupt) {
      assertTrue("${r.snapshot.doc} -> ${r.snapshot.outcome}",
        r.snapshot.outcome.startsWith("error") || r.snapshot.outcome == "blocked" ||
          r.snapshot.exercises == 0)
    }
  }

  // ── locating the corpus ──────────────────────────────────────────────────────

  private fun moduleDir(): File {
    val cwd = File(".").absoluteFile.normalize()
    return if (File(cwd, "src/test/resources").isDirectory) cwd else File(cwd, "app")
  }

  private fun corpusRoot() = File(moduleDir(), "src/test/resources/reader/corpus")
  private fun snapshotFile() = File(moduleDir(), "src/test/resources/reader/corpus-expected.json")
  private fun baselineFile() = File(moduleDir(), "src/test/resources/reader/scoreboard-baseline.json")

  private fun corpusFiles(): List<File> = corpusRoot()
    .walkTopDown().filter { it.isFile }
    // Sorted by path so the run order — and therefore any accumulated state — is identical
    // everywhere. A corpus whose result depends on file-system ordering is not a baseline.
    .sortedBy { it.path.replace('\\', '/') }
    .toList()

  private fun shouldUpdate() = System.getProperty("reader.corpus.update") == "true"
}
