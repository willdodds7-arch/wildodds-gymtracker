package com.wildodds.gymtracker.ui.home

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.parser.DefaultProgramCatalogue
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession
import com.wildodds.gymtracker.data.ai.AiFileExtractor
import com.wildodds.gymtracker.data.ai.AiParsedProgram
import com.wildodds.gymtracker.data.ai.ClaudeApiClient
import com.wildodds.gymtracker.data.parser.smart.SmartParsedProgram
import com.wildodds.gymtracker.data.parser.smart.SmartXlsxParser
import com.wildodds.gymtracker.data.parser.smart.WeightUnit
import com.wildodds.gymtracker.data.intelligence.ProgressionEngine
import com.wildodds.gymtracker.data.intelligence.RecalibrationDetector
import com.wildodds.gymtracker.data.intelligence.RecalibrationTrigger
import com.wildodds.gymtracker.data.intelligence.SetRecord
import com.wildodds.gymtracker.data.intelligence.WeekRecord
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ImportState {
  object Idle : ImportState()
  object Loading : ImportState()
  // Smart parser detected structure - show preview before committing
  data class Preview(
  val smart: SmartParsedProgram,
  val editName: String,
  val editWeeks: Int
  ) : ImportState()
  data class Success(val sessionCount: Int) : ImportState()
  data class Error(val message: String) : ImportState()
  data class AiReview(val program: AiParsedProgram) : ImportState()
  // Bulk import added N programs (with phases) to the library.
  data class BulkSuccess(val programCount: Int) : ImportState()
}

data class SessionRowUi(val session: Session, val isCompleted: Boolean, val isNextUp: Boolean)

data class WeekSection(
  val weekNumber: Int,
  val sessions: List<SessionRowUi>,
  val completedCount: Int
)

data class ProgramStats(
  val completedSessions: Int,
  val totalSessions: Int
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {

  private val db  = AppDatabase.getInstance(app)
  private val repo = GymRepository(db)
  private val prefs = app.getSharedPreferences("gym_prefs", Context.MODE_PRIVATE)

  val program: StateFlow<Program?> = repo.currentProgram()
  .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val userPrograms: StateFlow<List<Program>> = repo.userPrograms()
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _favourites = MutableStateFlow(
  prefs.getStringSet("favourites", emptySet())!!.toSet()
  )
  val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

  fun toggleFavourite(name: String) {
  val current = _favourites.value.toMutableSet()
  if (current.contains(name)) current.remove(name) else current.add(name)
  _favourites.value = current.toSet()
  prefs.edit().putStringSet("favourites", current).apply()
  }

  fun cancelProgram() {
  viewModelScope.launch { withContext(Dispatchers.IO) { repo.cancelProgram() } }
  }

  fun activateUserProgram(programId: Long) {
  viewModelScope.launch { withContext(Dispatchers.IO) { repo.activateProgram(programId) } }
  }

  /**
   * Begin a session from Home. If this is the very first session of a fresh program (no completions
   * yet) and it isn't the first day, rotate the program so the chosen day becomes day 1 and the rest
   * wrap (start day 3 of 4 → 3,4,1,2, every week). Then navigate. Resuming a program never reshuffles.
   */
  fun startSession(sessionId: Long, dayNumber: Int, weekNumber: Int, navigate: (Long, Int) -> Unit) {
  viewModelScope.launch {
  val prog = program.value
  if (prog != null && !repo.programHasAnyCompletion(prog.id)) {
  repo.rotateProgramStartDay(prog.id, dayNumber)
  }
  navigate(sessionId, weekNumber)
  }
  }

  // ── Edit the active program's days from Home ────────────────────────────────
  fun addDay(name: String) {
  val id = program.value?.id ?: return
  viewModelScope.launch { repo.addDayToProgram(id, name) }
  }

  fun removeDay(dayNumber: Int) {
  val id = program.value?.id ?: return
  viewModelScope.launch { repo.removeDayFromProgram(id, dayNumber) }
  }

  fun renameDay(dayNumber: Int, name: String) {
  val id = program.value?.id ?: return
  viewModelScope.launch { repo.renameDayInProgram(id, dayNumber, name) }
  }

  private val allSessions: Flow<List<Session>> = program
  .filterNotNull()
  .flatMapLatest { repo.allSessions(it.id) }

  private val allCompletions: Flow<List<SessionCompletion>> = repo.allCompletions()

  /**
   * Exercises for the active program, grouped by session id. Read-only — used by the
   * Home weekly view to list each day's lifts. Does not change any logging logic.
   */
  val exercisesBySession: StateFlow<Map<Long, List<com.wildodds.gymtracker.data.db.entity.Exercise>>> = program
  .filterNotNull()
  .flatMapLatest { repo.programExercises(it.id) }
  .map { list -> list.groupBy { it.sessionId } }
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  // Phases of the active program (empty / single = a normal single-block program).
  val phases: StateFlow<List<com.wildodds.gymtracker.data.db.entity.ProgramPhase>> = program
  .filterNotNull()
  .flatMapLatest { repo.programPhases(it.id) }
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  fun setPhase(phase: Int) {
  val id = program.value?.id ?: return
  viewModelScope.launch { withContext(Dispatchers.IO) { repo.setCurrentPhase(id, phase) } }
  }

  /** Global [start, end] week range of the phase currently selected (1-based, inclusive). */
  private fun phaseWeekRange(prog: Program, phaseList: List<com.wildodds.gymtracker.data.db.entity.ProgramPhase>): Pair<Int, Int> {
  if (phaseList.size <= 1) return 1 to prog.totalWeeks
  val ordered = phaseList.sortedBy { it.phaseNumber }
  val idx = (prog.currentPhase - 1).coerceIn(0, ordered.size - 1)
  val start = 1 + ordered.take(idx).sumOf { it.durationWeeks }
  val end = start + ordered[idx].durationWeeks - 1
  return start to end
  }

  val weekSections: StateFlow<List<WeekSection>> = combine(
  program, allSessions, allCompletions, phases
  ) { prog, sessions, completions, phaseList ->
  if (prog == null) return@combine emptyList()
  val completedSet = completions.map { it.sessionId to it.weekNumber }.toSet()
  val grouped = sessions.groupBy { it.weekNumber }
  var foundNextUp = false
  val (startWeek, endWeek) = phaseWeekRange(prog, phaseList)

  (startWeek..endWeek).mapNotNull { week ->
  val weekSessions = grouped[week] ?: return@mapNotNull null
  val rows = weekSessions.map { s ->
  val done  = completedSet.contains(s.id to week)
  val nextUp = if (!done && !foundNextUp) { foundNextUp = true; true } else false
  SessionRowUi(s, isCompleted = done, isNextUp = nextUp)
  }
  WeekSection(week, rows, rows.count { it.isCompleted })
  }
  }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val programStats: StateFlow<ProgramStats> = weekSections
  .map { sections ->
  ProgramStats(
  completedSessions = sections.sumOf { it.completedCount },
  totalSessions  = sections.sumOf { it.sessions.size }
  )
  }
  .stateIn(viewModelScope, SharingStarted.Eagerly, ProgramStats(0, 0))

  private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
  val importState: StateFlow<ImportState> = _importState.asStateFlow()

  private val _defaultPrograms = MutableStateFlow<List<ParsedProgram>>(emptyList())
  val defaultPrograms: StateFlow<List<ParsedProgram>> = _defaultPrograms.asStateFlow()

  init {
  viewModelScope.launch(Dispatchers.IO) {
  _defaultPrograms.value = DefaultProgramCatalogue.load(getApplication())
  }
  }

  /**
   * Other catalogue blocks that share the active program's split — the "Start a new block" list shown
   * when restarting. Empty when the active program has no split tag or nothing else matches.
   */
  val sameSplitBlocks: StateFlow<List<ParsedProgram>> =
  combine(program, defaultPrograms) { active, all ->
  val split = active?.split?.takeIf { it.isNotBlank() } ?: return@combine emptyList()
  all.filter { it.split.equals(split, ignoreCase = true) && it.name != active.name }
  }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  // ── AI-powered import (any file type) ────────────────────────────────────

  fun importAnyFile(uri: Uri) {
    viewModelScope.launch {
      _importState.value = ImportState.Loading
      withContext(Dispatchers.IO) {
        try {
          val apiKey = prefs.getString("claude_api_key", "") ?: ""
          if (apiKey.isBlank()) throw Exception("No API key set. Go to Settings → AI Import and add your Anthropic API key.")
          val text = AiFileExtractor.extract(getApplication(), uri)
          if (text.isBlank()) throw Exception("No readable text found in this file")
          val parsed = ClaudeApiClient.parseProgram(apiKey, text)
          if (parsed.days.isEmpty()) throw Exception("No workout days could be found in the file")
          _importState.value = ImportState.AiReview(parsed)
        } catch (e: Exception) {
          _importState.value = ImportState.Error(e.message ?: "Import failed")
        }
      }
    }
  }

  fun confirmAiImport(program: AiParsedProgram, name: String, weeks: Int, activate: Boolean = true) {
    viewModelScope.launch {
      _importState.value = ImportState.Loading
      withContext(Dispatchers.IO) {
        try {
          val sessions = (1..weeks).flatMap { week ->
            program.days.map { day ->
              ParsedSession(
                weekNumber   = week,
                dayNumber    = day.dayNumber,
                name         = day.name,
                muscleGroups = day.muscleGroups,
                exercises    = day.exercises.mapIndexed { i, ex ->
                  ParsedExercise(ex.name, ex.sets, ex.repsTarget, ex.notes, i)
                }
              )
            }
          }
          val parsed = ParsedProgram(
            name          = name.ifBlank { program.name },
            totalWeeks    = weeks,
            sessions      = sessions,
            isUserCreated = true
          )
          repo.importProgram(parsed, activate = activate)
          _importState.value = ImportState.Success(sessions.size)
        } catch (e: Exception) {
          _importState.value = ImportState.Error(e.message ?: "Import failed")
        }
      }
    }
  }

  // ── Import from xlsx ──────────────────────────────────────────────────────

  fun importXlsx(uri: Uri) {
  viewModelScope.launch {
  _importState.value = ImportState.Loading
  withContext(Dispatchers.IO) {
  try {
  val stream = getApplication<Application>().contentResolver.openInputStream(uri)
  ?: throw Exception("Cannot open file")
  val smart = SmartXlsxParser().parse(stream)
  if (smart.weekTemplate.isEmpty()) throw Exception("No exercises found in file")
  _importState.value = ImportState.Preview(
  smart  = smart,
  editName  = smart.name,
  editWeeks = smart.weeksDetected.coerceIn(1, 52)
  )
  } catch (e: Exception) {
  _importState.value = ImportState.Error(e.message ?: "Unknown error")
  }
  }
  }
  }

  fun confirmSmartImport(name: String, weeks: Int, smart: SmartParsedProgram) {
  viewModelScope.launch {
  _importState.value = ImportState.Loading
  withContext(Dispatchers.IO) {
  try {
  val parsed = smart.toParsedProgram(name.ifBlank { smart.name }, weeks)
  repo.importProgram(parsed)
  _importState.value = ImportState.Success(parsed.sessions.size)
  } catch (e: Exception) {
  _importState.value = ImportState.Error(e.message ?: "Unknown error")
  }
  }
  }
  }

  // ── Load a default or custom ParsedProgram ────────────────────────────────

  fun loadDefaultProgram(program: ParsedProgram) {
  viewModelScope.launch {
  _importState.value = ImportState.Loading
  withContext(Dispatchers.IO) {
  try {
  repo.importProgram(program.copy(isUserCreated = true), activate = true)
  _importState.value = ImportState.Success(program.sessions.size)
  } catch (e: Exception) {
  _importState.value = ImportState.Error(e.message ?: "Unknown error")
  }
  }
  }
  }

  fun addDefaultToLibrary(program: ParsedProgram) {
  viewModelScope.launch {
  _importState.value = ImportState.Loading
  withContext(Dispatchers.IO) {
  try {
  repo.importProgram(program.copy(isUserCreated = true), activate = false)
  _importState.value = ImportState.Success(program.sessions.size)
  } catch (e: Exception) {
  _importState.value = ImportState.Error(e.message ?: "Unknown error")
  }
  }
  }
  }

  // ── Program completion detection ──────────────────────────────────────────

  /** True when the program has at least one session AND every session in every week is completed. */
  val isProgramComplete: StateFlow<Boolean> = weekSections
  .map { sections ->
  sections.isNotEmpty() && sections.all { week -> week.sessions.all { it.isCompleted } }
  }
  .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  // ── Goal re-calibration (Phase 3B) ────────────────────────────────────────
  // Program-wide stall: re-evaluated whenever completions change (a session was just logged).
  private val programStalled: Flow<Boolean> = combine(program, allCompletions) { prog, _ -> prog }
  .flatMapLatest { prog ->
  flow { emit(if (prog == null) false else withContext(Dispatchers.IO) { computeProgramStalled(prog.id) }) }
  }

  /** Why a re-calibration prompt should appear: program completion, a multi-week stall, or nothing. */
  val recalibrationTrigger: StateFlow<RecalibrationTrigger> =
  combine(isProgramComplete, programStalled) { complete, stalled ->
  when {
  complete -> RecalibrationTrigger.PROGRAM_COMPLETE
  stalled  -> RecalibrationTrigger.STALLED
  else  -> RecalibrationTrigger.NONE
  }
  }.stateIn(viewModelScope, SharingStarted.Eagerly, RecalibrationTrigger.NONE)

  // Stall detection now lives in the repository (shared with the Phase 4B adaptive plan engine).
  private suspend fun computeProgramStalled(programId: Long): Boolean =
  repo.isProgramStalled(programId)

  // ── Restart current program (wipe logs, keep exercises) ──────────────────

  private val _restarting = MutableStateFlow(false)
  val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

  fun restartProgram() {
  viewModelScope.launch {
  _restarting.value = true
  withContext(Dispatchers.IO) { repo.restartProgram() }
  _restarting.value = false
  }
  }

  /** Restart using Week 2's logged values as the new Week 1 prefill baseline. */
  fun restartFromWeek2() {
  viewModelScope.launch {
  _restarting.value = true
  withContext(Dispatchers.IO) {
  val programId = program.value?.id ?: return@withContext
  repo.restartFromWeek2(programId)
  }
  _restarting.value = false
  }
  }

  // ── Bulk import (multiple programs, with phases) ──────────────────────────

  fun importBulkXlsx(uri: Uri) {
  viewModelScope.launch {
  _importState.value = ImportState.Loading
  withContext(Dispatchers.IO) {
  try {
  val stream = getApplication<Application>().contentResolver.openInputStream(uri)
  ?: throw Exception("Cannot open file")
  val programs = stream.use {
  com.wildodds.gymtracker.data.parser.BulkProgramXlsxParser().parse(it)
  }
  if (programs.isEmpty()) throw Exception("No programs found. Check the sheet has Program and Exercise columns.")
  programs.forEach { repo.importProgram(it.copy(isUserCreated = true), activate = false) }
  _importState.value = ImportState.BulkSuccess(programs.size)
  } catch (e: Exception) {
  _importState.value = ImportState.Error(e.message ?: "Bulk import failed")
  }
  }
  }
  }

  fun saveBulkTemplateToUri(uri: Uri) {
  viewModelScope.launch {
  withContext(Dispatchers.IO) {
  try {
  getApplication<Application>().assets.open("bulk_template.xlsx").use { input ->
  getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
  input.copyTo(out)
  }
  }
  _templateDownloadState.value = TemplateDownloadState.Success
  } catch (e: Exception) {
  _templateDownloadState.value = TemplateDownloadState.Error(e.message ?: "Failed to save template")
  }
  }
  }
  }

  // ── Template download ─────────────────────────────────────────────────────

  sealed class TemplateDownloadState {
  object Idle : TemplateDownloadState()
  object Success : TemplateDownloadState()
  data class Error(val message: String) : TemplateDownloadState()
  }

  private val _templateDownloadState = MutableStateFlow<TemplateDownloadState>(TemplateDownloadState.Idle)
  val templateDownloadState: StateFlow<TemplateDownloadState> = _templateDownloadState.asStateFlow()

  fun saveTemplateToUri(uri: Uri) {
  viewModelScope.launch {
  withContext(Dispatchers.IO) {
  try {
  val inputStream = getApplication<Application>().assets.open("template.xlsx")
  getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
  inputStream.copyTo(out)
  }
  inputStream.close()
  _templateDownloadState.value = TemplateDownloadState.Success
  } catch (e: Exception) {
  _templateDownloadState.value = TemplateDownloadState.Error(e.message ?: "Failed to save template")
  }
  }
  }
  }

  fun clearTemplateDownloadState() { _templateDownloadState.value = TemplateDownloadState.Idle }

  fun clearImportState() { _importState.value = ImportState.Idle }
}

// ── SmartParsedProgram → ParsedProgram conversion ────────────────────────────

private fun SmartParsedProgram.toParsedProgram(name: String, weeks: Int): ParsedProgram {
  val sessions = (1..weeks).flatMap { week ->
  weekTemplate.map { day ->
  val muscleGroups = day.muscleGroups.ifBlank {
  day.exercises.take(3)
  .joinToString(" / ") { it.name.split(" ").first() }
  .ifBlank { "Custom" }
  }
  ParsedSession(
  weekNumber  = week,
  dayNumber  = day.dayNumber,
  name  = day.name,
  muscleGroups = muscleGroups,
  exercises  = day.exercises.map { ex ->
  val noteParts = mutableListOf<String>()
  if (ex.weight != null) {
  val wt = if (ex.weight % 1f == 0f) ex.weight.toInt().toString() else ex.weight.toString()
  val unit = when (ex.weightUnit) { WeightUnit.KG -> "kg"; WeightUnit.LBS -> "lbs"; else -> "" }
  noteParts.add("$wt$unit")
  }
  ex.notes?.let { noteParts.add(it) }
  ex.restSeconds?.let { noteParts.add("Rest ${it}s") }
  ex.supersetGroup?.let { noteParts.add("Superset $it") }
  ParsedExercise(
  name     = ex.name,
  sets     = ex.sets,
  repsTarget  = ex.reps,
  notes    = noteParts.joinToString(", "),
  orderIndex  = ex.orderIndex,
  rpeTarget   = ex.rpeTarget ?: "",
  pct1rmTarget  = ex.pct1rmTarget ?: ""
  )
  }
  )
  }
  }
  return ParsedProgram(name = name, totalWeeks = weeks, sessions = sessions)
}
