package com.wildodds.gymtracker.ui.programimport

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.reader.android.AndroidReader
import com.wildodds.gymtracker.data.reader.ingest.IngestError
import com.wildodds.gymtracker.data.reader.ingest.IngestProgress
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.importer.ImportFlag
import com.wildodds.gymtracker.data.importer.PrefsAliasStore
import com.wildodds.gymtracker.data.importer.ResolutionStatus
import com.wildodds.gymtracker.data.importer.UniversalProgramImporter
import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One editable exercise row in the review screen. [original] is the verbatim parsed name.
 *
 * [parsed] is the full compiled exercise, carried through untouched so that committing does not
 * silently drop everything the v26 schema can now hold (rest, tempo, superset, load kind, per-set
 * detail, modifiers, the source line). Rebuilding a `ParsedExercise` from the visible fields alone
 * was exactly the lossy step phase 2 removed from the compiler, and it would have crept straight
 * back in here.
 */
data class EditExercise(
  val original: String,
  val name: String,
  val sets: Int,
  val reps: String,
  val rpe: String,
  val pct: String,
  val notes: String,
  val needsReview: Boolean,
  val parsed: ParsedExercise,
  /** The verbatim source line, shown beside the parsed fields so the user can see what was read. */
  val sourceLine: String = "",
  /** Per-slot confidence, and the reasons behind it, so the UI explains rather than asserts. */
  val confidence: Double = 1.0,
  val confidenceReasons: List<String> = emptyList(),
  /** Ranked alternatives for a name below EXACT — one tap instead of a search. */
  val candidates: List<String> = emptyList(),
  /** True once the user has explicitly accepted or corrected this row. */
  val accepted: Boolean = false
)

data class EditSession(
  val label: String,
  val week: Int,
  val day: Int,
  val exercises: List<EditExercise>,
  /** Name of a [com.wildodds.gymtracker.data.importer.SessionType]; a REST day has no exercises. */
  val sessionType: String = "TRAINING"
)

data class ImportReviewState(
  val rawInput: String = "",
  val programName: String = "",
  val parsed: Boolean = false,
  val parsing: Boolean = false,
  val sessions: List<EditSession> = emptyList(),
  val flags: List<ImportFlag> = emptyList(),
  val overallConfidence: Double = 0.0,
  val exerciseCount: Int = 0,
  val multiWeek: Boolean = false,
  val weeksToRun: Int = 4,
  val committing: Boolean = false,
  val committedProgramId: Long? = null,
  val error: String? = null,
  /** Live extraction progress ("Reading page 3 of 12"). Empty when nothing long is running. */
  val progress: String = "",
  /** Which route read this: an adapter id, a layout id, or "text". Shown in the review header. */
  val route: String = "",
  /** The full flag list is shown; this is how many still need a decision. */
  val needingReview: Int = 0,
  /** A one-line explanation of the overall score, e.g. "82% - exercise inferred (x3)". */
  val confidenceExplanation: String = "",
  /** Set when the same document has been imported before: merge / replace / duplicate. */
  val duplicateOf: DuplicateImport? = null,
  /** Set after a commit so the user can undo it without leaving the screen. */
  val lastCommittedProgramId: Long? = null,
  val undone: Boolean = false,
  /** The bytes the current draft came from, kept for the idempotency hash and the import record. */
  val sourceBytes: ByteArray? = null,
  val sourceName: String = ""
)

/** A document that has been imported before, and what the user can do about it. */
data class DuplicateImport(
  val programId: Long,
  val programName: String,
  val importedAt: Long
)

/**
 * Drives the Universal Import review screen: parse pasted text/CSV → editable draft → commit. All
 * deterministic and on-device. Corrections the user makes are learned into the per-user alias store
 * so the next import is smarter (spec §5).
 */
class ImportReviewViewModel(app: Application) : AndroidViewModel(app) {

  private val db    = AppDatabase.getInstance(app)
  private val repo  = GymRepository(db)
  private val alias = PrefsAliasStore(app)
  private val history = com.wildodds.gymtracker.data.repository.ImportHistory(db)

  private val _state = MutableStateFlow(ImportReviewState())
  val state: StateFlow<ImportReviewState> = _state.asStateFlow()

  fun setRaw(text: String)  { _state.value = _state.value.copy(rawInput = text) }
  fun setName(name: String) { _state.value = _state.value.copy(programName = name) }
  fun setWeeksToRun(n: Int)  { _state.value = _state.value.copy(weeksToRun = n.coerceIn(1, 52)) }

  fun parse() {
    val s = _state.value
    if (s.rawInput.isBlank()) { _state.value = s.copy(error = "Paste a program first."); return }
    _state.value = s.copy(parsing = true, error = null)
    // Parsing is deterministic but can be non-trivial for a long program, and resolution touches the
    // alias store — keep it off the main thread so the UI never stutters.
    viewModelScope.launch(Dispatchers.Default) {
      // Pasted text takes the SAME route as a picked file, app-export adapters included —
      // people paste a Strong CSV into the box as often as they attach it.
      val bytes = s.rawInput.toByteArray(Charsets.UTF_8)
      applyResult(
        AndroidReader.reader(getApplication()).readText(
          raw = s.rawInput,
          name = s.programName,
          aliasLookup = alias.asLookup
        ),
        bytes,
        s.programName
      )
      checkDuplicate(bytes)
    }
  }

  /**
   * Load a picked file through the full ingest pipeline — any of the formats listed in
   * `docs/reader/` — and show the result.
   *
   * Extraction can be slow (a 12-page PDF, OCR of a photo), so it runs on IO with live progress and
   * cooperative cancellation rather than freezing behind an indeterminate spinner.
   */
  fun loadFromFile(uri: Uri) {
    _state.value = _state.value.copy(parsing = true, error = null, progress = "Reading the file")
    cancelled = false
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val app = getApplication<Application>()
        val bytes = AndroidReader.readBytes(app, uri)
        val fileName = AndroidReader.displayName(app, uri)
        val result = AndroidReader.reader(app).read(
          bytes = bytes,
          fileName = fileName,
          mimeType = AndroidReader.mimeType(app, uri),
          aliasLookup = alias.asLookup,
          progress = progressSink
        )
        applyResult(result, bytes, fileName)
        checkDuplicate(bytes)
      } catch (e: IngestError) {
        // A typed ingest error already carries a message the user can act on; wrapping it in
        // "Couldn’t read that file" would bury the actionable half.
        _state.value = _state.value.copy(parsing = false, progress = "", error = e.message)
      } catch (e: Exception) {
        _state.value = _state.value.copy(parsing = false, progress = "",
          error = "Couldn’t read that file: ${e.message}")
      }
    }
  }

  @Volatile private var cancelled = false

  /** The last successful read, kept so [commit] can record what produced this draft. */
  private var lastResult: com.wildodds.gymtracker.data.reader.ProgramReader.Result? = null

  /** Stops a long extraction between units of work. The pipeline checks this and unwinds cleanly. */
  fun cancelParsing() {
    cancelled = true
    _state.value = _state.value.copy(parsing = false, progress = "")
  }

  private val progressSink = object : IngestProgress {
    override fun report(stage: String, current: Int, total: Int) {
      _state.value = _state.value.copy(progress = stage)
    }
    override fun isCancelled(): Boolean = cancelled
  }

  private fun applyResult(
    r: com.wildodds.gymtracker.data.reader.ProgramReader.Result,
    sourceBytes: ByteArray? = null,
    sourceName: String = ""
  ) {
    lastResult = r
    val result = r.importResult
    val multiWeek = result.program.sessions.map { it.weekNumber }.distinct().size > 1
    // Per-slot confidence and reasons live on the DRAFT, keyed by the verbatim source line, which
    // is the only identifier that survives compilation into the flat program.
    val byLine = result.draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }
      .associateBy { it.rawText }
    val summary = com.wildodds.gymtracker.data.importer.Confidence.forProgram(result.draft)
    _state.value = _state.value.copy(
      parsing = false,
      parsed = true,
      progress = "",
      rawInput = r.document.text,
      route = r.route,
      programName = _state.value.programName.ifBlank { result.program.name },
      sessions = result.program.sessions.groupBy { it.weekNumber to it.dayNumber }.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { (key, group) ->
          val ps = group.first()
          EditSession(ps.name, key.first, key.second, ps.exercises.map { toEdit(it, byLine) }, ps.sessionType)
        },
      flags = result.flags,
      overallConfidence = summary.score,
      confidenceExplanation = summary.explain(),
      needingReview = summary.needingReview,
      exerciseCount = result.exerciseCount,
      multiWeek = multiWeek,
      sourceBytes = sourceBytes,
      sourceName = sourceName,
      // A single written week defaults to a 4-week block (the app default length) rather than a
      // 1-week program, which is almost never what the user wants; the stepper adjusts it.
      weeksToRun = if (multiWeek) result.program.totalWeeks
                   else result.program.repeatWeeks.takeIf { it > 1 } ?: 4
    )
  }

  private fun toEdit(
    pe: ParsedExercise,
    byLine: Map<String, com.wildodds.gymtracker.data.importer.SlotDraft> = emptyMap()
  ): EditExercise {
    val slot = pe.rawSourceLine?.let { byLine[it] }
    val ref = slot?.exercise
      ?: com.wildodds.gymtracker.data.importer.ExerciseResolver.resolve(pe.name, alias.asLookup)
    val needsReview = ref.status == ResolutionStatus.INFERRED || ref.status == ResolutionStatus.UNRESOLVED ||
      pe.setsStated == null ||
      (slot?.confidence ?: 1.0) < com.wildodds.gymtracker.data.importer.Confidence.REVIEW_THRESHOLD
    return EditExercise(
      original = pe.name, name = pe.name, sets = pe.sets, reps = pe.repsTarget,
      rpe = pe.rpeTarget, pct = pe.pct1rmTarget, notes = pe.notes, needsReview = needsReview,
      parsed = pe, sourceLine = pe.rawSourceLine.orEmpty(),
      confidence = slot?.confidence ?: 1.0,
      confidenceReasons = slot?.breakdown?.reasons.orEmpty().distinct(),
      candidates = ref.candidates.map { it.name }
    )
  }

  private fun update(si: Int, ei: Int, block: (EditExercise) -> EditExercise) {
    val sessions = _state.value.sessions.toMutableList()
    val sess = sessions.getOrNull(si) ?: return
    val exs = sess.exercises.toMutableList()
    val ex = exs.getOrNull(ei) ?: return
    exs[ei] = block(ex)
    sessions[si] = sess.copy(exercises = exs)
    _state.value = _state.value.copy(sessions = sessions)
  }

  /**
   * Re-importing the same document should offer a choice, not silently produce a second copy.
   * Keyed on the source BYTES, so the same file is recognised however it has been renamed.
   */
  private suspend fun checkDuplicate(bytes: ByteArray) {
    when (val d = history.checkForDuplicate(bytes)) {
      is com.wildodds.gymtracker.data.repository.ImportHistory.Duplicate.Seen ->
        _state.value = _state.value.copy(duplicateOf = DuplicateImport(
          d.programId, d.programName, d.record.createdAt))
      else -> _state.value = _state.value.copy(duplicateOf = null)
    }
  }

  fun dismissDuplicate() { _state.value = _state.value.copy(duplicateOf = null) }

  /** Replace the earlier import of this same document rather than adding a second copy. */
  fun replaceDuplicate(onDone: (Long) -> Unit) {
    val dup = _state.value.duplicateOf ?: return
    viewModelScope.launch {
      history.undo(dup.programId)
      _state.value = _state.value.copy(duplicateOf = null)
      commit(activate = true, onDone = onDone)
    }
  }

  /**
   * Accept every row that scored above the review threshold, in one action.
   *
   * The rows BELOW it are deliberately untouched: bulk-accepting the uncertain ones is the same as
   * not reviewing at all, which is the failure this whole screen exists to prevent.
   */
  fun acceptAllAboveThreshold() {
    _state.value = _state.value.copy(sessions = _state.value.sessions.map { sess ->
      sess.copy(exercises = sess.exercises.map {
        if (!it.needsReview) it.copy(accepted = true) else it
      })
    })
  }

  /** How many rows still need a decision. */
  fun remainingProblems(): Int = _state.value.sessions
    .sumOf { s -> s.exercises.count { it.needsReview && !it.accepted } }

  /** Accept one row as read, without changing it. */
  fun acceptRow(si: Int, ei: Int) = update(si, ei) { it.copy(accepted = true, needsReview = false) }

  /**
   * Undo the import that was just committed. Available without leaving the screen, because the
   * moment someone realises the import is wrong is the moment they are looking at it.
   */
  fun undoLastCommit(onDone: () -> Unit) {
    val id = _state.value.lastCommittedProgramId ?: return
    viewModelScope.launch {
      if (history.undo(id)) {
        _state.value = _state.value.copy(undone = true, lastCommittedProgramId = null,
          committedProgramId = null)
      }
      onDone()
    }
  }

  fun editName(si: Int, ei: Int, name: String) = update(si, ei) { it.copy(name = name, needsReview = false) }
  fun editSets(si: Int, ei: Int, sets: Int)    = update(si, ei) { it.copy(sets = sets.coerceAtLeast(1)) }
  fun editReps(si: Int, ei: Int, reps: String) = update(si, ei) { it.copy(reps = reps) }

  fun removeExercise(si: Int, ei: Int) {
    val sessions = _state.value.sessions.toMutableList()
    val sess = sessions.getOrNull(si) ?: return
    sessions[si] = sess.copy(exercises = sess.exercises.filterIndexed { i, _ -> i != ei })
    _state.value = _state.value.copy(sessions = sessions)
  }

  /**
   * Fix once, apply everywhere: correcting a name updates every occurrence AND learns the alias, so
   * the next import of the same coach's sheet needs no correction at all.
   *
   * Returns how many rows changed, so the UI can say "applied to 12 occurrences" rather than
   * leaving the user to wonder whether it did anything.
   */
  fun applyMappingEverywhere(original: String, newName: String): Int {
    var changed = 0
    _state.value = _state.value.copy(
      sessions = _state.value.sessions.map { s ->
        s.copy(exercises = s.exercises.map {
          if (it.original.equals(original, true)) {
            changed++
            it.copy(name = newName, needsReview = false, accepted = true)
          } else it
        })
      }
    )
    if (changed > 0 && newName.isNotBlank()) alias.learn(original, newName)
    return changed
  }

  fun commit(activate: Boolean, onDone: (Long) -> Unit) {
    val s = _state.value
    if (s.sessions.isEmpty()) { _state.value = s.copy(error = "Nothing to save."); return }
    _state.value = s.copy(committing = true, error = null)
    viewModelScope.launch {
      // Learn every correction the user made, scoped to this user only.
      s.sessions.flatMap { it.exercises }.filter { !it.name.equals(it.original, true) && it.original.isNotBlank() }
        .forEach { alias.learn(it.original, it.name) }

      val parsedSessions = s.sessions.map { es ->
        ParsedSession(
          weekNumber = es.week, dayNumber = es.day, name = es.label, muscleGroups = "",
          // Copy the user's edits ONTO the compiled exercise rather than building a fresh one, so
          // every field they didn't touch survives the commit exactly as parsed.
          exercises = es.exercises.mapIndexed { i, e ->
            e.parsed.copy(
              name = e.name, sets = e.sets, repsTarget = e.reps, notes = e.notes,
              orderIndex = i, rpeTarget = e.rpe, pct1rmTarget = e.pct,
              // An edited set count is now stated — by the user, in review. That is a statement of
              // fact, not a guess, so it is recorded as one.
              setsStated = if (e.sets != e.parsed.sets) e.sets else e.parsed.setsStated
            )
          },
          sessionType = es.sessionType
        )
      }
      val allEx = parsedSessions.flatMap { it.exercises }
      val program = ParsedProgram(
        name = s.programName.ifBlank { "Imported Program" },
        totalWeeks = if (s.multiWeek) s.sessions.map { it.week }.distinct().size else s.weeksToRun,
        sessions = parsedSessions,
        isUserCreated = true,
        trackRpe = allEx.any { it.rpeTarget.isNotBlank() },
        trackOneRm = allEx.any { it.pct1rmTarget.isNotBlank() },
        repeatWeeks = if (s.multiWeek) 1 else s.weeksToRun.coerceAtLeast(1),
        daysPerWeek = s.sessions
          .filter { it.week == (s.sessions.minOfOrNull { x -> x.week } ?: 1) && it.sessionType == "TRAINING" }
          .size
      )
      // Transactional, and RECORDED: the program and its import record land together, so the
      // import can be undone, re-parsed with a future reader, and recognised if the same file is
      // imported again.
      val bytes = s.sourceBytes ?: s.rawInput.toByteArray(Charsets.UTF_8)
      val id = lastResult?.let { r ->
        history.commit(repo, program, r, bytes, s.sourceName.ifBlank { s.programName }, activate)
      } ?: repo.importProgramTransactionally(program, activate)
      _state.value = _state.value.copy(
        committing = false, committedProgramId = id, lastCommittedProgramId = id, undone = false)
      onDone(id)
    }
  }
}
