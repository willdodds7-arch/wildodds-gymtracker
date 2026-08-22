package com.wildodds.gymtracker.ui.programimport

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.ai.AiFileExtractor
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
  val sourceLine: String = ""
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
  val error: String? = null
)

/**
 * Drives the Universal Import review screen: parse pasted text/CSV → editable draft → commit. All
 * deterministic and on-device. Corrections the user makes are learned into the per-user alias store
 * so the next import is smarter (spec §5).
 */
class ImportReviewViewModel(app: Application) : AndroidViewModel(app) {

  private val repo  = GymRepository(AppDatabase.getInstance(app))
  private val alias = PrefsAliasStore(app)

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
      val result = UniversalProgramImporter.import(
        name = s.programName.ifBlank { "Imported Program" },
        raw = s.rawInput,
        aliasLookup = alias.asLookup
      )
      val multiWeek = result.program.sessions.map { it.weekNumber }.distinct().size > 1
      _state.value = _state.value.copy(
        parsing = false,
        parsed = true,
        programName = _state.value.programName.ifBlank { result.program.name },
        sessions = result.program.sessions.groupBy { it.weekNumber to it.dayNumber }.entries
          .sortedWith(compareBy({ it.key.first }, { it.key.second }))
          .map { (key, group) ->
            val ps = group.first()
            EditSession(ps.name, key.first, key.second, ps.exercises.map(::toEdit), ps.sessionType)
          },
        flags = result.flags,
        overallConfidence = result.overallConfidence,
        exerciseCount = result.exerciseCount,
        multiWeek = multiWeek,
        // A single written week defaults to a 4-week block (the app's default length) rather than a
        // 1-week program, which is almost never what the user wants; they can adjust in the stepper.
        weeksToRun = if (multiWeek) result.program.totalWeeks else result.program.repeatWeeks.takeIf { it > 1 } ?: 4
      )
    }
  }

  /**
   * Load a picked file (.txt/.csv/.tsv/.md/.pdf/.xlsx) via the shared [AiFileExtractor], drop its
   * text into the paste box, and parse. Extraction (esp. PDF) runs off the main thread.
   */
  fun loadFromFile(uri: Uri) {
    _state.value = _state.value.copy(parsing = true, error = null)
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val text = AiFileExtractor.extract(getApplication(), uri)
        if (text.isBlank()) {
          _state.value = _state.value.copy(parsing = false,
            error = "No readable text in that file. Scanned PDFs and images aren’t supported yet — paste the text instead.")
          return@launch
        }
        val nameFromFile = fileDisplayName(uri)?.substringBeforeLast('.')?.trim().orEmpty()
        _state.value = _state.value.copy(
          rawInput = text,
          programName = _state.value.programName.ifBlank { nameFromFile }
        )
        parse()
      } catch (e: Exception) {
        _state.value = _state.value.copy(parsing = false, error = "Couldn’t read that file: ${e.message}")
      }
    }
  }

  private fun fileDisplayName(uri: Uri): String? {
    val ctx = getApplication<Application>()
    if (uri.scheme == "content") {
      ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
        val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cur.moveToFirst()) return cur.getString(idx)
      }
    }
    return uri.path?.substringAfterLast('/')
  }

  private fun toEdit(pe: ParsedExercise): EditExercise {
    val ref = com.wildodds.gymtracker.data.importer.ExerciseResolver.resolve(pe.name, alias.asLookup)
    val needsReview = ref.status == ResolutionStatus.INFERRED || ref.status == ResolutionStatus.UNRESOLVED ||
      pe.setsStated == null
    return EditExercise(
      original = pe.name, name = pe.name, sets = pe.sets, reps = pe.repsTarget,
      rpe = pe.rpeTarget, pct = pe.pct1rmTarget, notes = pe.notes, needsReview = needsReview,
      parsed = pe, sourceLine = pe.rawSourceLine.orEmpty()
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

  fun editName(si: Int, ei: Int, name: String) = update(si, ei) { it.copy(name = name, needsReview = false) }
  fun editSets(si: Int, ei: Int, sets: Int)    = update(si, ei) { it.copy(sets = sets.coerceAtLeast(1)) }
  fun editReps(si: Int, ei: Int, reps: String) = update(si, ei) { it.copy(reps = reps) }

  fun removeExercise(si: Int, ei: Int) {
    val sessions = _state.value.sessions.toMutableList()
    val sess = sessions.getOrNull(si) ?: return
    sessions[si] = sess.copy(exercises = sess.exercises.filterIndexed { i, _ -> i != ei })
    _state.value = _state.value.copy(sessions = sessions)
  }

  /** Bulk op (spec §7): apply a name correction to every slot that had the same original text. */
  fun applyMappingEverywhere(original: String, newName: String) {
    _state.value = _state.value.copy(
      sessions = _state.value.sessions.map { s ->
        s.copy(exercises = s.exercises.map {
          if (it.original.equals(original, true)) it.copy(name = newName, needsReview = false) else it
        })
      }
    )
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
      // Transactional: a half-imported program that looks complete but is missing its last two
      // days is worse than a failed import the user can retry.
      val id = repo.importProgramTransactionally(program, activate)
      _state.value = _state.value.copy(committing = false, committedProgramId = id)
      onDone(id)
    }
  }
}
