package com.wildodds.gymtracker.ui.builder

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.wildodds.gymtracker.data.builder.PlanExercise
import com.wildodds.gymtracker.data.builder.PlanGroup
import com.wildodds.gymtracker.data.builder.PlanProgram
import com.wildodds.gymtracker.data.builder.PlanSet
import com.wildodds.gymtracker.data.builder.PlanWorkout
import com.wildodds.gymtracker.data.builder.ProgramMath
import com.wildodds.gymtracker.data.builder.ProgramPlanJson
import com.wildodds.gymtracker.data.builder.ProgramSummary
import com.wildodds.gymtracker.data.builder.ProgressionSettings
import com.wildodds.gymtracker.data.builder.SetShape
import com.wildodds.gymtracker.data.builder.SetType
import com.wildodds.gymtracker.data.builder.WorkoutKind
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the small status line under the header says. Never mentions a server — there isn't one. */
enum class SaveStatus { IDLE, SAVING, SAVED }

/**
 * Drives the program builder.
 *
 * Everything the user types mutates one [PlanProgram] in memory and is written to a local draft
 * ~400 ms later, so there is no Save button to forget and nothing is lost if the app is killed
 * mid-edit. Committing to the database is a separate, explicit action.
 *
 * Undo is a bounded snapshot stack: every destructive action pushes the previous plan before
 * mutating, so "Deleted Push A · Undo" restores exactly what was there.
 */
class BuilderViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = GymRepository(AppDatabase.getInstance(app))
  private val gson = Gson()
  private val draftPrefs = app.getSharedPreferences("program_builder_draft", Context.MODE_PRIVATE)

  private val _plan = MutableStateFlow(PlanProgram())
  val plan: StateFlow<PlanProgram> = _plan.asStateFlow()

  private val _saveStatus = MutableStateFlow(SaveStatus.IDLE)
  val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

  /** Set when a destructive action can still be reversed. Cleared once the snackbar goes. */
  private val _undoPrompt = MutableStateFlow<UndoPrompt?>(null)
  val undoPrompt: StateFlow<UndoPrompt?> = _undoPrompt.asStateFlow()

  private val _committed = MutableStateFlow<Long?>(null)
  val committed: StateFlow<Long?> = _committed.asStateFlow()

  private val _message = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = _message.asStateFlow()

  data class UndoPrompt(val label: String, val snapshot: PlanProgram)

  /** Live summary. Recomputed off the main thread — the stress case is 8 × 12 × 5. */
  val summary: StateFlow<ProgramSummary> = _plan
    .map { ProgramMath.summarise(it) }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramMath.summarise(PlanProgram()))

  private var autosaveJob: Job? = null

  init {
    restoreDraft()
  }

  // ── Autosave ───────────────────────────────────────────────────────────────

  /**
   * Single funnel for every mutation. Applies [transform], then schedules the debounced local
   * write. Nothing else in this class writes [_plan] directly, so nothing can bypass autosave.
   */
  private fun mutate(transform: (PlanProgram) -> PlanProgram) {
    _plan.value = transform(_plan.value)
    scheduleAutosave()
  }

  private fun scheduleAutosave() {
    _saveStatus.value = SaveStatus.SAVING
    autosaveJob?.cancel()
    autosaveJob = viewModelScope.launch {
      delay(AUTOSAVE_DEBOUNCE_MS)
      withContext(Dispatchers.IO) { writeDraft(_plan.value) }
      _saveStatus.value = SaveStatus.SAVED
    }
  }

  private fun writeDraft(p: PlanProgram) {
    runCatching { draftPrefs.edit().putString(DRAFT_KEY, gson.toJson(p)).apply() }
  }

  private fun restoreDraft() {
    val json = draftPrefs.getString(DRAFT_KEY, null) ?: return
    runCatching { gson.fromJson(json, PlanProgram::class.java) }
      .getOrNull()
      ?.let {
        _plan.value = it
        _saveStatus.value = SaveStatus.SAVED
      }
  }

  /** Called when the user finishes with a program, so the next new one starts clean. */
  fun clearDraft() {
    autosaveJob?.cancel()
    draftPrefs.edit().remove(DRAFT_KEY).apply()
    _saveStatus.value = SaveStatus.IDLE
  }

  fun startNewProgram() {
    autosaveJob?.cancel()
    _plan.value = PlanProgram()
    _saveStatus.value = SaveStatus.IDLE
    draftPrefs.edit().remove(DRAFT_KEY).apply()
  }

  /** True when there is meaningful work in progress worth offering to resume. */
  fun hasContent(): Boolean =
    _plan.value.name.isNotBlank() || _plan.value.workouts.isNotEmpty()

  // ── Undo ───────────────────────────────────────────────────────────────────

  private fun snapshotFor(label: String) {
    _undoPrompt.value = UndoPrompt(label, _plan.value)
  }

  fun undo() {
    _undoPrompt.value?.let {
      _plan.value = it.snapshot
      scheduleAutosave()
    }
    _undoPrompt.value = null
  }

  fun dismissUndo() { _undoPrompt.value = null }
  fun clearMessage() { _message.value = null }

  // ── Program header (§1.1) ──────────────────────────────────────────────────

  fun setName(v: String) = mutate { it.copy(name = v) }
  fun setDescription(v: String) = mutate { it.copy(description = v) }
  fun setGoal(v: com.wildodds.gymtracker.data.builder.ProgramGoal) = mutate { it.copy(goal = v) }
  fun setExperience(v: com.wildodds.gymtracker.data.builder.ExperienceLevel) =
    mutate { it.copy(experience = v) }
  fun setDurationWeeks(v: Int) = mutate { it.copy(durationWeeks = v.coerceIn(0, 104)) }
  fun setFolder(v: String) = mutate { it.copy(folder = v) }
  fun setCoverImage(v: String?) = mutate { it.copy(coverImage = v) }
  fun setTrackRpe(v: Boolean) = mutate { it.copy(trackRpe = v) }
  fun setTrackOneRm(v: Boolean) = mutate { it.copy(trackOneRm = v) }
  fun setProgression(v: ProgressionSettings) = mutate { it.copy(progression = v) }

  // ── Workouts (§1.2, §1.3, §1.6) ────────────────────────────────────────────

  fun addWorkout(name: String = "", kind: WorkoutKind = WorkoutKind.TRAINING, weekday: Int = 0): String {
    val workout = PlanWorkout(
      name = name.ifBlank { defaultWorkoutName(kind) },
      kind = kind,
      weekday = weekday
    )
    mutate { it.copy(workouts = it.workouts + workout) }
    return workout.id
  }

  private fun defaultWorkoutName(kind: WorkoutKind): String = when (kind) {
    WorkoutKind.REST -> "Rest"
    WorkoutKind.DELOAD -> "Deload"
    WorkoutKind.TRAINING -> {
      val n = _plan.value.workouts.count { !it.isRest } + 1
      "Day $n"
    }
  }

  fun duplicateWorkout(id: String) = mutate { p ->
    val i = p.workouts.indexOfFirst { it.id == id }
    if (i < 0) return@mutate p
    val src = p.workouts[i]
    // Fresh ids all the way down, so editing the copy never touches the original.
    val groupMap = src.groups.associate { it.id to PlanGroup(
      label = it.label, rounds = it.rounds,
      restBetweenExercisesSeconds = it.restBetweenExercisesSeconds,
      restAfterRoundSeconds = it.restAfterRoundSeconds
    ) }
    val copy = src.copy(
      id = PlanWorkout().id,
      name = "${src.name} copy",
      groups = groupMap.values.toList(),
      exercises = src.exercises.map { ex ->
        ex.copy(
          id = PlanExercise().id,
          supersetGroupId = ex.supersetGroupId?.let { groupMap[it]?.id },
          sets = ex.sets.map { s -> s.copy(id = PlanSet().id) }
        )
      }
    )
    p.copy(workouts = p.workouts.toMutableList().apply { add(i + 1, copy) })
  }

  fun renameWorkout(id: String, name: String) = updateWorkout(id) { it.copy(name = name) }
  fun setWorkoutWeekday(id: String, weekday: Int) = updateWorkout(id) { it.copy(weekday = weekday) }
  fun setWorkoutNote(id: String, note: String) = updateWorkout(id) { it.copy(note = note) }
  fun setWorkoutOptional(id: String, optional: Boolean) =
    updateWorkout(id) { it.copy(isOptional = optional) }

  fun deleteWorkout(id: String) {
    val w = _plan.value.workouts.firstOrNull { it.id == id } ?: return
    snapshotFor("Deleted ${w.name.ifBlank { "workout" }}")
    mutate { p -> p.copy(workouts = p.workouts.filterNot { it.id == id }) }
  }

  /** Move a workout to a new index. Used by both the drag handle and the accessible menu items. */
  fun moveWorkout(id: String, toIndex: Int) = mutate { p ->
    val from = p.workouts.indexOfFirst { it.id == id }
    if (from < 0) return@mutate p
    val target = toIndex.coerceIn(0, p.workouts.lastIndex)
    if (from == target) return@mutate p
    val list = p.workouts.toMutableList()
    list.add(target, list.removeAt(from))
    p.copy(workouts = list)
  }

  fun moveWorkoutBy(id: String, delta: Int) {
    val from = _plan.value.workouts.indexOfFirst { it.id == id }
    if (from >= 0) moveWorkout(id, from + delta)
  }

  private fun updateWorkout(id: String, transform: (PlanWorkout) -> PlanWorkout) = mutate { p ->
    p.copy(workouts = p.workouts.map { if (it.id == id) transform(it) else it })
  }

  // ── Exercises (§1.5, §1.6, §2.2) ───────────────────────────────────────────

  /** Add one exercise with a preset applied, so a new row is never blank. */
  fun addExercise(workoutId: String, name: String, preset: SetPreset = SetPreset.THREE_BY_EIGHT_TWELVE) =
    updateWorkout(workoutId) { w ->
      w.copy(exercises = w.exercises + PlanExercise(name = name, sets = preset.build()))
    }

  fun addExercises(workoutId: String, names: List<String>, preset: SetPreset = SetPreset.THREE_BY_EIGHT_TWELVE) =
    updateWorkout(workoutId) { w ->
      w.copy(exercises = w.exercises + names.map { PlanExercise(name = it, sets = preset.build()) })
    }

  fun renameExercise(workoutId: String, exerciseId: String, name: String) =
    updateExercise(workoutId, exerciseId) { it.copy(name = name) }

  fun setExerciseNotes(workoutId: String, exerciseId: String, notes: String) =
    updateExercise(workoutId, exerciseId) { it.copy(notes = notes) }

  fun setExerciseOptional(workoutId: String, exerciseId: String, optional: Boolean) =
    updateExercise(workoutId, exerciseId) { it.copy(isOptional = optional) }

  fun setAlternatives(workoutId: String, exerciseId: String, names: List<String>) =
    updateExercise(workoutId, exerciseId) { it.copy(alternatives = names) }

  fun deleteExercise(workoutId: String, exerciseId: String) {
    val w = _plan.value.workouts.firstOrNull { it.id == workoutId } ?: return
    val ex = w.exercises.firstOrNull { it.id == exerciseId } ?: return
    snapshotFor("Removed ${ex.name.ifBlank { "exercise" }}")
    updateWorkout(workoutId) { it.copy(exercises = it.exercises.filterNot { e -> e.id == exerciseId }) }
  }

  fun duplicateExercise(workoutId: String, exerciseId: String) = updateWorkout(workoutId) { w ->
    val i = w.exercises.indexOfFirst { it.id == exerciseId }
    if (i < 0) return@updateWorkout w
    val src = w.exercises[i]
    val copy = src.copy(id = PlanExercise().id, sets = src.sets.map { it.copy(id = PlanSet().id) })
    w.copy(exercises = w.exercises.toMutableList().apply { add(i + 1, copy) })
  }

  fun moveExercise(workoutId: String, exerciseId: String, delta: Int) = updateWorkout(workoutId) { w ->
    val from = w.exercises.indexOfFirst { it.id == exerciseId }
    if (from < 0) return@updateWorkout w
    val to = (from + delta).coerceIn(0, w.exercises.lastIndex)
    if (from == to) return@updateWorkout w
    val list = w.exercises.toMutableList()
    list.add(to, list.removeAt(from))
    w.copy(exercises = list)
  }

  fun moveExerciseTo(workoutId: String, exerciseId: String, toIndex: Int) = updateWorkout(workoutId) { w ->
    val from = w.exercises.indexOfFirst { it.id == exerciseId }
    if (from < 0) return@updateWorkout w
    val to = toIndex.coerceIn(0, w.exercises.lastIndex)
    if (from == to) return@updateWorkout w
    val list = w.exercises.toMutableList()
    list.add(to, list.removeAt(from))
    w.copy(exercises = list)
  }

  /** Move an exercise into another workout, keeping its whole prescription. */
  fun moveExerciseToWorkout(fromWorkoutId: String, exerciseId: String, toWorkoutId: String) = mutate { p ->
    val src = p.workouts.firstOrNull { it.id == fromWorkoutId } ?: return@mutate p
    val ex = src.exercises.firstOrNull { it.id == exerciseId } ?: return@mutate p
    p.copy(workouts = p.workouts.map { w ->
      when (w.id) {
        // Leaving a workout also leaves its superset group behind.
        fromWorkoutId -> w.copy(exercises = w.exercises.filterNot { it.id == exerciseId })
        toWorkoutId -> w.copy(exercises = w.exercises + ex.copy(supersetGroupId = null))
        else -> w
      }
    })
  }

  private fun updateExercise(
    workoutId: String,
    exerciseId: String,
    transform: (PlanExercise) -> PlanExercise
  ) = updateWorkout(workoutId) { w ->
    w.copy(exercises = w.exercises.map { if (it.id == exerciseId) transform(it) else it })
  }

  // ── Sets (§1.5 presets + bulk actions, §2.2 per-set editing) ───────────────

  fun applyPreset(workoutId: String, exerciseId: String, preset: SetPreset) =
    updateExercise(workoutId, exerciseId) { it.copy(sets = preset.build()) }

  // ── Evolving sets ──────────────────────────────────────────────────────────

  /**
   * Re-shape an exercise's whole prescription (warm-up ramp, pyramid, top set + back-off…).
   *
   * Keeps what the user has already decided: the working-set count and rep target carry over from
   * the existing sets, and any weight typed on the top set is preserved so switching shapes doesn't
   * throw away their working weight. See `data/builder/SetShape.kt`.
   */
  fun applyShape(workoutId: String, exerciseId: String, shape: SetShape) =
    updateExercise(workoutId, exerciseId) { ex ->
      val workingSets = ex.workingSetCount.coerceAtLeast(1)
      val reps = ex.sets.firstOrNull { it.type.countsAsVolume && it.reps.isNotBlank() }?.reps ?: "8-12"
      val rest = ex.sets.firstOrNull { it.type.countsAsVolume }?.restSeconds ?: 90
      val keptTopLoad = ex.topLoad

      val rebuilt = shape.build(workingSets, reps, rest)
      ex.copy(sets = rebuilt.map { s ->
        if (s.loadPercent == 100 && keptTopLoad != null) s.copy(load = keptTopLoad.tidyLoad()) else s
      })
    }

  /** Set the reference weight the whole ladder resolves from. */
  fun setTopLoad(workoutId: String, exerciseId: String, load: String) =
    updateExercise(workoutId, exerciseId) { ex ->
      val anchor = ex.sets.indexOfFirst { it.loadPercent == 100 }
        .takeIf { it >= 0 }
        ?: ex.sets.indexOfFirst { it.type.countsAsVolume }
      if (anchor < 0) return@updateExercise ex
      ex.copy(sets = ex.sets.mapIndexed { i, s -> if (i == anchor) s.copy(load = load) else s })
    }

  /** Add or remove one working set, keeping the shape's proportions intact. */
  fun changeWorkingSets(workoutId: String, exerciseId: String, delta: Int) =
    updateExercise(workoutId, exerciseId) { ex ->
      val shape = SetShape.detect(ex.sets)
      val target = (ex.workingSetCount + delta).coerceIn(1, 12)
      val reps = ex.sets.firstOrNull { it.type.countsAsVolume && it.reps.isNotBlank() }?.reps ?: "8-12"
      val rest = ex.sets.firstOrNull { it.type.countsAsVolume }?.restSeconds ?: 90
      val keptTopLoad = ex.topLoad
      val rebuilt = shape.build(target, reps, rest)
      ex.copy(sets = rebuilt.map { s ->
        if (s.loadPercent == 100 && keptTopLoad != null) s.copy(load = keptTopLoad.tidyLoad()) else s
      })
    }

  /** Change the rep target on every working set at once. */
  fun setRepsForAllWorking(workoutId: String, exerciseId: String, reps: String) =
    updateExercise(workoutId, exerciseId) { ex ->
      ex.copy(sets = ex.sets.map { if (it.type.countsAsVolume) it.copy(reps = reps) else it })
    }

  fun addSet(workoutId: String, exerciseId: String) =
    updateExercise(workoutId, exerciseId) { ex ->
      // A new set copies the last one — far more often right than a blank row.
      val template = ex.sets.lastOrNull { it.type == SetType.WORKING } ?: PlanSet()
      ex.copy(sets = ex.sets + template.copy(id = PlanSet().id))
    }

  fun duplicateLastSet(workoutId: String, exerciseId: String) = addSet(workoutId, exerciseId)

  fun removeSet(workoutId: String, exerciseId: String, setId: String) =
    updateExercise(workoutId, exerciseId) { ex ->
      ex.copy(sets = ex.sets.filterNot { it.id == setId })
    }

  fun updateSet(workoutId: String, exerciseId: String, setId: String, transform: (PlanSet) -> PlanSet) =
    updateExercise(workoutId, exerciseId) { ex ->
      ex.copy(sets = ex.sets.map { if (it.id == setId) transform(it) else it })
    }

  /** Bulk: copy one set's prescription onto every other set of the exercise. */
  fun applySetToAll(workoutId: String, exerciseId: String, setId: String) =
    updateExercise(workoutId, exerciseId) { ex ->
      val src = ex.sets.firstOrNull { it.id == setId } ?: return@updateExercise ex
      ex.copy(sets = ex.sets.map {
        if (it.type == SetType.WARMUP) it
        else it.copy(load = src.load, reps = src.reps, restSeconds = src.restSeconds, rpe = src.rpe)
      })
    }

  fun setRestForAll(workoutId: String, exerciseId: String, seconds: Int) =
    updateExercise(workoutId, exerciseId) { ex ->
      ex.copy(sets = ex.sets.map { it.copy(restSeconds = seconds.coerceIn(0, 600)) })
    }

  fun setLoadForAllWorking(workoutId: String, exerciseId: String, load: String) =
    updateExercise(workoutId, exerciseId) { ex ->
      ex.copy(sets = ex.sets.map { if (it.type.countsAsVolume) it.copy(load = load) else it })
    }

  /** "8-12" → "10": collapse a range to its midpoint, and back again. */
  fun convertRepsToRange(workoutId: String, exerciseId: String) =
    updateExercise(workoutId, exerciseId) { ex ->
      ex.copy(sets = ex.sets.map { s ->
        if (s.reps.contains('-')) s else {
          val n = ProgramMath.repsForVolume(s.reps)
          if (n <= 0) s else s.copy(reps = "${(n - 2).coerceAtLeast(1)}-${n + 2}")
        }
      })
    }

  // ── Supersets (§2.4) ───────────────────────────────────────────────────────

  fun createGroupWith(workoutId: String, exerciseIds: List<String>) = updateWorkout(workoutId) { w ->
    val label = ('A' + w.groups.size).toString()
    val group = PlanGroup(label = label)
    w.copy(
      groups = w.groups + group,
      exercises = w.exercises.map {
        if (it.id in exerciseIds) it.copy(supersetGroupId = group.id) else it
      }
    )
  }

  fun addToGroup(workoutId: String, exerciseId: String, groupId: String) =
    updateExercise(workoutId, exerciseId) { it.copy(supersetGroupId = groupId) }

  /** Break the grouping without deleting a single exercise. */
  fun ungroup(workoutId: String, groupId: String) = updateWorkout(workoutId) { w ->
    w.copy(
      groups = w.groups.filterNot { it.id == groupId },
      exercises = w.exercises.map {
        if (it.supersetGroupId == groupId) it.copy(supersetGroupId = null) else it
      }
    )
  }

  fun updateGroup(workoutId: String, groupId: String, transform: (PlanGroup) -> PlanGroup) =
    updateWorkout(workoutId) { w ->
      w.copy(groups = w.groups.map { if (it.id == groupId) transform(it) else it })
    }

  // ── Templates + import (§2.3, §3.5) ────────────────────────────────────────

  fun loadTemplate(template: PlanProgram) {
    _plan.value = template.copy(id = PlanProgram().id)
    scheduleAutosave()
  }

  /** Replace the plan from a decoded JSON import. Caller has already previewed it. */
  fun adoptImported(program: PlanProgram) {
    snapshotFor("Replaced program")
    _plan.value = program
    scheduleAutosave()
  }

  fun exportJson(): String = ProgramPlanJson.encode(_plan.value)
  fun exportFileName(): String = ProgramPlanJson.fileNameFor(_plan.value)

  fun importJson(raw: String, replace: Boolean) {
    when (val r = ProgramPlanJson.decode(raw)) {
      is ProgramPlanJson.Result.Invalid -> _message.value = r.reason
      is ProgramPlanJson.Result.Ok -> {
        if (replace) adoptImported(r.program)
        else mutate { it.copy(workouts = it.workouts + r.program.workouts) }
        _message.value = buildString {
          append("Imported ${r.program.workouts.size} workout")
          if (r.program.workouts.size != 1) append("s")
          if (r.notes.isNotEmpty()) append(" · ${r.notes.first()}")
        }
      }
    }
  }

  fun readUri(uri: Uri, onText: (String) -> Unit) {
    viewModelScope.launch {
      val text = withContext(Dispatchers.IO) {
        runCatching {
          getApplication<Application>().contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
      }
      if (text.isNullOrBlank()) _message.value = "Couldn't read that file."
      else onText(text)
    }
  }

  // ── Commit (§3.4) ──────────────────────────────────────────────────────────

  /**
   * Write the plan to the database. Always allowed — an incomplete program saves with a warning
   * rather than being blocked.
   */
  fun saveProgram(activate: Boolean) {
    viewModelScope.launch {
      _saveStatus.value = SaveStatus.SAVING
      val id = runCatching { repo.saveProgramPlan(_plan.value, activate) }.getOrNull()
      if (id == null) {
        _message.value = "Couldn't save that program."
        _saveStatus.value = SaveStatus.IDLE
      } else {
        clearDraft()
        _committed.value = id
      }
    }
  }

  fun consumeCommitted() { _committed.value = null }

  companion object {
    /** Long enough not to write on every keystroke, short enough to survive a kill. */
    const val AUTOSAVE_DEBOUNCE_MS = 400L
    private const val DRAFT_KEY = "plan_json"
  }
}

/** One-tap set defaults (§1.5). The advanced editor stays collapsed behind "Configure". */
enum class SetPreset(val label: String) {
  THREE_BY_EIGHT("3 × 8"),
  THREE_BY_EIGHT_TWELVE("3 × 8–12"),
  FOUR_BY_TEN("4 × 10"),
  THREE_TO_FAILURE("3 sets to failure"),
  THREE_BY_THIRTY_S("3 × 30 s");

  fun build(): List<PlanSet> = when (this) {
    THREE_BY_EIGHT -> working(3, "8", 90)
    THREE_BY_EIGHT_TWELVE -> working(3, "8-12", 90)
    FOUR_BY_TEN -> working(4, "10", 90)
    THREE_TO_FAILURE -> List(3) { PlanSet(type = SetType.FAILURE, reps = "AMRAP", restSeconds = 120) }
    THREE_BY_THIRTY_S -> working(3, "30s", 60)
  }

  private fun working(n: Int, reps: String, rest: Int) =
    List(n) { PlanSet(type = SetType.WORKING, reps = reps, restSeconds = rest) }
}

/** "60" not "60.0" — the load field is text the user typed, so keep it looking typed. */
private fun Float.tidyLoad(): String =
  if (this % 1f == 0f) toInt().toString() else toString().trimEnd('0').trimEnd('.')
