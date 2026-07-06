package com.wildodds.gymtracker.ui.create

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ExerciseTemplate(
  val id: String = UUID.randomUUID().toString(),
  val name: String = "",
  val sets: Int = 3,
  val repsTarget: String = "8-12",
  val notes: String = ""
)

data class DayTemplate(
  val dayNumber: Int,
  val name: String = "Day $dayNumber",
  val exercises: List<ExerciseTemplate> = emptyList()
)

enum class ProgramType { FULL, FLEXIBLE }

data class CreateProgramState(
  val step: Int = 1,
  val programType: ProgramType = ProgramType.FULL,
  val programName: String = "",
  val daysPerWeek: Int = 4,
  val numberOfWeeks: Int = 5,
  val coverImageUri: String? = null,
  val days: List<DayTemplate> = emptyList(),
  val trackRpe: Boolean = false,
  val trackOneRm: Boolean = false,
  // ── Browse metadata ──
  val description: String = "",
  val coach: String = "",
  val coachBio: String = "",
  val split: String = "",
  val style: String = "",
  val isSaving: Boolean = false,
  val saveError: String? = null
)

class CreateProgramViewModel(app: Application) : AndroidViewModel(app) {

  private val repo  = GymRepository(AppDatabase.getInstance(app))
  private val gson  = Gson()
  private val draftPrefs  = app.getSharedPreferences("create_program_draft", Context.MODE_PRIVATE)
  private val DRAFT_KEY  = "draft_json"

  private val _state = MutableStateFlow(CreateProgramState())
  val state: StateFlow<CreateProgramState> = _state.asStateFlow()

  private val _hasDraft = MutableStateFlow(draftPrefs.contains(DRAFT_KEY))
  val hasDraft: StateFlow<Boolean> = _hasDraft.asStateFlow()

  // ── Draft save / restore ──────────────────────────────────────────────────

  fun saveDraft() {
  val saveable = _state.value.copy(isSaving = false, saveError = null)
  draftPrefs.edit().putString(DRAFT_KEY, gson.toJson(saveable)).apply()
  _hasDraft.value = true
  }

  fun restoreDraft() {
  val json = draftPrefs.getString(DRAFT_KEY, null) ?: return
  try {
  val restored = gson.fromJson(json, CreateProgramState::class.java)
  _state.value = restored.copy(isSaving = false, saveError = null)
  } catch (_: Exception) {
  clearDraft()
  }
  }

  fun clearDraft() {
  draftPrefs.edit().remove(DRAFT_KEY).apply()
  _hasDraft.value = false
  }

  // ── Step 1 ────────────────────────────────────────────────────────────────

  fun setProgramType(type: ProgramType) { _state.value = _state.value.copy(programType = type) }
  fun setProgramName(name: String)  { _state.value = _state.value.copy(programName = name) }
  fun setDaysPerWeek(days: Int)  { _state.value = _state.value.copy(daysPerWeek = days) }
  fun setNumberOfWeeks(weeks: Int)  { _state.value = _state.value.copy(numberOfWeeks = weeks) }
  fun setTrackRpe(value: Boolean)  { _state.value = _state.value.copy(trackRpe = value) }
  fun setTrackOneRm(value: Boolean)  { _state.value = _state.value.copy(trackOneRm = value) }
  fun setDescription(v: String)  { _state.value = _state.value.copy(description = v) }
  fun setCoach(v: String)  { _state.value = _state.value.copy(coach = v) }
  fun setCoachBio(v: String)  { _state.value = _state.value.copy(coachBio = v) }
  fun setSplit(v: String)  { _state.value = _state.value.copy(split = v) }
  fun setStyle(v: String)  { _state.value = _state.value.copy(style = v) }

  fun setCoverImage(uri: Uri) {
  try {
  getApplication<Application>().contentResolver.takePersistableUriPermission(
  uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
  )
  } catch (_: Exception) {}
  _state.value = _state.value.copy(coverImageUri = uri.toString())
  }

  fun clearCoverImage() { _state.value = _state.value.copy(coverImageUri = null) }

  /** For FULL programs: advance to the exercise builder. For FLEXIBLE: save directly. */
  fun proceedToBuilder() {
  val current = _state.value
  val days = (1..current.daysPerWeek).map { DayTemplate(dayNumber = it, name = "Day $it") }
  _state.value = current.copy(step = 2, days = days)
  }

  fun goBackToSetup() { _state.value = _state.value.copy(step = 1) }

  /** Save a FLEXIBLE program immediately from Step 1 (no exercise builder step). */
  fun saveFlexibleProgram(activate: Boolean = true, onSuccess: () -> Unit) {
  val current = _state.value
  viewModelScope.launch {
  _state.value = current.copy(isSaving = true, saveError = null)
  withContext(Dispatchers.IO) {
  try {
  val programName = current.programName.ifBlank { "My Program" }
  val sessions = (1..current.numberOfWeeks).flatMap { week ->
  (1..current.daysPerWeek).map { day ->
  ParsedSession(weekNumber = week, dayNumber = day,
  name = "Day $day", muscleGroups = "Custom",
  exercises = emptyList())
  }
  }
  val program = ParsedProgram(programName, current.numberOfWeeks,
  sessions, current.coverImageUri, isFlexible = true,
  category = "Your Programs", isUserCreated = true,
  trackRpe = current.trackRpe, trackOneRm = current.trackOneRm,
  description = current.description.trim(), coach = current.coach.trim(),
  coachBio = current.coachBio.trim(), daysPerWeek = current.daysPerWeek,
  split = current.split.trim(), style = current.style.trim())
  repo.importProgram(program, activate = activate)
  clearDraft()
  _state.value = _state.value.copy(isSaving = false)
  withContext(Dispatchers.Main) { onSuccess() }
  } catch (e: Exception) {
  _state.value = _state.value.copy(isSaving = false, saveError = e.message ?: "Save failed")
  }
  }
  }
  }

  fun addFlexibleToLibrary(onSuccess: () -> Unit) = saveFlexibleProgram(activate = false, onSuccess = onSuccess)
  fun startFlexibleNow(onSuccess: () -> Unit)  = saveFlexibleProgram(activate = true,  onSuccess = onSuccess)

  // ── Step 2 - editing ──────────────────────────────────────────────────────

  fun updateDayName(dayIndex: Int, name: String) {
  mutateDays { it[dayIndex] = it[dayIndex].copy(name = name) }
  }

  fun addExercise(dayIndex: Int) {
  mutateDays { days ->
  val day = days[dayIndex]
  days[dayIndex] = day.copy(exercises = day.exercises + ExerciseTemplate())
  }
  }

  fun moveExercise(dayIndex: Int, exerciseId: String, delta: Int) {
    mutateDays { days ->
      val day = days[dayIndex]
      val exList = day.exercises.toMutableList()
      val fromIdx = exList.indexOfFirst { it.id == exerciseId }
      if (fromIdx < 0) return@mutateDays
      val toIdx = (fromIdx + delta).coerceIn(0, exList.size - 1)
      if (fromIdx == toIdx) return@mutateDays
      val item = exList.removeAt(fromIdx)
      exList.add(toIdx, item)
      days[dayIndex] = day.copy(exercises = exList)
    }
  }

  fun deleteExercise(dayIndex: Int, exerciseId: String) {
  mutateDays { days ->
  val day = days[dayIndex]
  days[dayIndex] = day.copy(exercises = day.exercises.filter { it.id != exerciseId })
  }
  }

  fun updateExerciseName(dayIndex: Int, exerciseId: String, name: String) =
  updateExercise(dayIndex, exerciseId) { it.copy(name = name) }

  fun updateExerciseSets(dayIndex: Int, exerciseId: String, delta: Int) =
  updateExercise(dayIndex, exerciseId) { ex -> ex.copy(sets = (ex.sets + delta).coerceIn(1, 20)) }

  fun updateExerciseReps(dayIndex: Int, exerciseId: String, reps: String) =
  updateExercise(dayIndex, exerciseId) { it.copy(repsTarget = reps) }

  fun updateExerciseNotes(dayIndex: Int, exerciseId: String, notes: String) =
  updateExercise(dayIndex, exerciseId) { it.copy(notes = notes) }

  private fun updateExercise(dayIndex: Int, exerciseId: String, transform: (ExerciseTemplate) -> ExerciseTemplate) {
  mutateDays { days ->
  val day = days[dayIndex]
  days[dayIndex] = day.copy(exercises = day.exercises.map { if (it.id == exerciseId) transform(it) else it })
  }
  }

  private fun mutateDays(block: (MutableList<DayTemplate>) -> Unit) {
  _state.value = _state.value.copy(days = _state.value.days.toMutableList().also(block))
  }

  // ── Save - always allowed, name defaults to "My Program" if blank ─────────

  fun saveProgram(activate: Boolean = true, onSuccess: () -> Unit) {
  val current = _state.value
  viewModelScope.launch {
  _state.value = current.copy(isSaving = true, saveError = null)
  withContext(Dispatchers.IO) {
  try {
  val programName = current.programName.ifBlank { "My Program" }
  val sessions = (1..current.numberOfWeeks).flatMap { week ->
  current.days.map { day ->
  val muscleGroups = day.exercises
  .filter { it.name.isNotBlank() }.take(4)
  .joinToString(" / ") { it.name.split(" ").firstOrNull() ?: it.name }
  ParsedSession(
  weekNumber  = week,
  dayNumber  = day.dayNumber,
  name  = day.name,
  muscleGroups = muscleGroups.ifBlank { "Custom" },
  exercises  = day.exercises.filter { it.name.isNotBlank() }
  .mapIndexed { idx, ex ->
  ParsedExercise(ex.name, ex.sets, ex.repsTarget.ifBlank { "8-12" }, ex.notes, idx)
  }
  )
  }
  }
  val program = ParsedProgram(programName, current.numberOfWeeks, sessions, current.coverImageUri,
  category = "Your Programs", isUserCreated = true,
  trackRpe = current.trackRpe, trackOneRm = current.trackOneRm,
  description = current.description.trim(), coach = current.coach.trim(),
  coachBio = current.coachBio.trim(), daysPerWeek = current.daysPerWeek,
  split = current.split.trim(), style = current.style.trim())
  repo.importProgram(program, activate = activate)
  clearDraft()
  _state.value = _state.value.copy(isSaving = false)
  withContext(Dispatchers.Main) { onSuccess() }
  } catch (e: Exception) {
  _state.value = _state.value.copy(isSaving = false, saveError = e.message ?: "Save failed")
  }
  }
  }
  }

  fun addToLibrary(onSuccess: () -> Unit) = saveProgram(activate = false, onSuccess = onSuccess)
  fun startNow(onSuccess: () -> Unit)  = saveProgram(activate = true,  onSuccess = onSuccess)

  fun clearSaveError() { _state.value = _state.value.copy(saveError = null) }

  // ── Session Library integration ────────────────────────────────────────────

  val librarySessions: StateFlow<List<com.wildodds.gymtracker.data.db.entity.SessionTemplate>> =
  repo.librarySessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _toast = MutableStateFlow<String?>(null)
  val toast: StateFlow<String?> = _toast.asStateFlow()
  fun clearToast() { _toast.value = null }

  /** Save the current builder day to the reusable Session Library. */
  fun saveDayAsSession(dayIndex: Int) {
  val day = _state.value.days.getOrNull(dayIndex) ?: return
  val rows = day.exercises.filter { it.name.isNotBlank() }
  if (rows.isEmpty()) { _toast.value = "Add an exercise first"; return }
  val muscles = rows.take(4).joinToString(" / ") { it.name.split(" ").firstOrNull() ?: it.name }
  viewModelScope.launch {
  withContext(Dispatchers.IO) {
  repo.saveSessionTemplate(
  name = day.name, muscleGroups = muscles, notes = "",
  exercises = rows.mapIndexed { idx, ex ->
  com.wildodds.gymtracker.data.db.entity.SessionTemplateExercise(
  templateId = 0, name = ex.name, sets = ex.sets,
  repsTarget = ex.repsTarget.ifBlank { "8-12" }, notes = ex.notes, orderIndex = idx)
  },
  source = "Custom"
  )
  }
  _toast.value = "\"${day.name}\" saved to Session Library"
  }
  }

  /** Replace a builder day's exercises (and name) with a library session. */
  fun loadSessionIntoDay(dayIndex: Int, templateId: Long) {
  viewModelScope.launch {
  val exercises = withContext(Dispatchers.IO) { repo.getLibrarySessionExercisesOnce(templateId) }
  val template = withContext(Dispatchers.IO) {
  librarySessions.value.firstOrNull { it.id == templateId }
  }
  mutateDays { days ->
  val day = days[dayIndex]
  days[dayIndex] = day.copy(
  name = template?.name ?: day.name,
  exercises = exercises.map { ex ->
  ExerciseTemplate(name = ex.name, sets = ex.sets,
  repsTarget = ex.repsTarget, notes = ex.notes)
  }
  )
  }
  _toast.value = "Loaded ${exercises.size} exercise${if (exercises.size != 1) "s" else ""}"
  }
  }
}
