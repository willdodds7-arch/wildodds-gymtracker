package com.wildodds.gymtracker.ui.session

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.dao.SetLogWithWeek
import com.wildodds.gymtracker.data.intelligence.BodyRegion
import com.wildodds.gymtracker.data.intelligence.ExerciseGraph
import com.wildodds.gymtracker.data.intelligence.FatigueEngine
import com.wildodds.gymtracker.data.intelligence.FatigueResult
import com.wildodds.gymtracker.data.intelligence.FatigueSet
import com.wildodds.gymtracker.data.intelligence.InjuryAccommodator
import com.wildodds.gymtracker.data.intelligence.InjuryOverlayData
import com.wildodds.gymtracker.data.intelligence.PlannedExercise
import com.wildodds.gymtracker.data.intelligence.PrehabLibrary
import com.wildodds.gymtracker.data.intelligence.ProgressionEngine
import com.wildodds.gymtracker.data.intelligence.ProgressionInput
import com.wildodds.gymtracker.data.intelligence.TranslatedExercise
import com.wildodds.gymtracker.data.intelligence.TravelEquipment
import com.wildodds.gymtracker.data.intelligence.TravelTranslator
import com.wildodds.gymtracker.data.intelligence.ProgressionStyle
import com.wildodds.gymtracker.data.intelligence.SetRecord
import com.wildodds.gymtracker.data.intelligence.TrackingMode
import com.wildodds.gymtracker.data.profile.MainLift
import com.wildodds.gymtracker.data.intelligence.WeekRecord
import com.wildodds.gymtracker.data.repository.GymRepository
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import com.wildodds.gymtracker.data.wearable.HrResult
import com.wildodds.gymtracker.data.wearable.WearableProvider
import com.wildodds.gymtracker.data.wearable.WearableSessionData
import com.wildodds.gymtracker.data.wearable.encodeHrSeries
import com.wildodds.gymtracker.ui.home.RecoveryAdjustment
import com.wildodds.gymtracker.ui.home.RecoveryAdjustmentStore
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

private val setRowKeys = java.util.concurrent.atomic.AtomicLong()
internal fun nextSetRowKey(): Long = setRowKeys.incrementAndGet()

// Marked @Immutable (never mutated in place — every VM update goes through .copy()/toMutableList())
// so Compose can skip recomposing ExerciseCard/SetRow instances whose reference is unchanged,
// instead of treating every keystroke as invalidating the whole session screen.
@Immutable
data class SetRowState(
  val setNumber: Int,
  val weightKg: String = "",
  val reps: String = "",
  val rpe: String = "",
  val pct1rm: String = "",
  // Unilateral exercises: weightKg/reps = LEFT side, these = RIGHT side
  val weightRight: String = "",
  val repsRight: String = "",
  // isPrefilled = came from prev-week data and user hasn't edited yet
  val isPrefilled: Boolean = false,
  // isWeightAutoFilled = weight was propagated from set 1 and hasn't been manually changed
  val isWeightAutoFilled: Boolean = false,
  val workoutLogId: Long = 0L,
  // ── The prescription for THIS set (evolving sets) ──────────────────────────
  // Null/blank on programs that predate per-set prescription, which then behave exactly as before:
  // every set shares the exercise's single repsTarget.
  /** Name of a `data.builder.SetType` — WARMUP rows are visually separated and skipped by volume. */
  val plannedType: String? = null,
  /** This set's own rep target, e.g. "5" for a warm-up inside an 8-12 exercise. */
  val plannedReps: String = "",
  /** Percentage of the exercise's top working set, when the prescription is a ramp. */
  val plannedPercent: Int? = null,
  /** Literal prescribed load, when the program named one outright. */
  val plannedLoad: String = "",
  /** This set's own rest, so a warm-up doesn't inherit a 3-minute working rest. */
  val plannedRest: Int? = null,
  /**
   * True only for a genuinely distinguished heavy single — a top-set/back-off prescription.
   * Three straight sets at 100% are not "top sets", they are just the work.
   */
  val isTopSet: Boolean = false,
  /**
   * Stable identity for this row in the UI, independent of [setNumber]. Deleting a set renumbers
   * every set after it, so keying the swipe-to-delete rows by set number handed a deleted row's
   * already-dismissed swipe state (and its stale index) to the row that slid up into its place.
   * Survives `.copy()`, so edits keep the same key.
   */
  val rowKey: Long = nextSetRowKey()
) {
  val isWarmup: Boolean get() = plannedType == "WARMUP"
  /** True when this row has a prescription of its own worth showing. */
  val hasPrescription: Boolean
    get() = plannedReps.isNotBlank() || plannedPercent != null || plannedLoad.isNotBlank()
}

@Immutable
data class ExerciseUiState(
  val exercise: Exercise,
  val sets: List<SetRowState> = emptyList(),
  // Badge shown at top of card - what was chosen for THIS exercise last week
  val prevProgressionChoice: String? = null,
  // Pills selection - what the user is choosing for THIS exercise this week (persisted immediately)
  val currentProgressionChoice: String? = null,
  // Smart-progression default (Phase 3B): the picker key the engine recommends + its rationale.
  // Null when smart progression is off or there's no usable history. Always overridable.
  val suggestedProgressionKey: String? = null,
  val suggestionRationale: String? = null,
  val workoutLogId: Long = 0L
)

@Immutable
data class SessionUiState(
  val session: Session? = null,
  val exercises: List<ExerciseUiState> = emptyList(),
  val isCompleted: Boolean = false,
  val isLoading: Boolean = true
)

/** Data shown on the post-session summary sheet. HR fields are empty until a wearable provider exists. */
data class SessionSummaryData(
  val durationSeconds: Long?,
  val totalSets: Int,
  val totalVolumeKg: Float,
  val strainRating: Int? = null,
  val avgHeartRate: Int? = null,
  val peakHeartRate: Int? = null,
  val hrSeries: List<Int> = emptyList(),
  // Heaviest single set this session, for the real-life weight comparison.
  val heaviestLiftName: String? = null,
  val heaviestLiftWeightKg: Float? = null,
  val heaviestLiftReps: Int? = null,
  // Achievements newly unlocked by completing this session (announced on the summary).
  val unlockedAchievements: List<String> = emptyList()
) {
  val hasHeartRate: Boolean get() = avgHeartRate != null && peakHeartRate != null
}

class SessionViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {

  private val sessionId: Long  = savedStateHandle.get<Long>("sessionId")  ?: 0L
  val weekNumber: Int  = savedStateHandle.get<Int>("weekNumber")  ?: 1

  private val db  = AppDatabase.getInstance(app)
  private val repo  = GymRepository(db)
  private val notePrefs = app.getSharedPreferences("exercise_notes", Context.MODE_PRIVATE)
  private val themePrefs = ThemePreferences(app)

  // Heart-rate source (Phase 4). Health Connect when available + permitted, else a no-op that
  // returns null — so everything below degrades cleanly when no wearable data exists.
  private val wearable: WearableSessionData = WearableProvider.create(app)

  private val _state = MutableStateFlow(SessionUiState())
  val state: StateFlow<SessionUiState> = _state.asStateFlow()

  // True once the user has entered any set data during THIS visit to the screen. Drives the
  // exit-confirmation guard; reset when the session is completed or the day is reset.
  private val _hasLoggedThisVisit = MutableStateFlow(false)
  val hasLoggedThisVisit: StateFlow<Boolean> = _hasLoggedThisVisit.asStateFlow()

  // "Active session" start = the moment the FIRST set is logged this visit (not when the screen
  // opens), so idle previewing/browsing doesn't inflate the duration. Duration = Complete − this.
  private var sessionStartMillis: Long? = null
  private fun markLoggedThisVisit() {
  _hasLoggedThisVisit.value = true
  if (sessionStartMillis == null) {
  sessionStartMillis = System.currentTimeMillis()
  }
  }

  // Post-session summary sheet state. Non-null → the sheet is showing.
  private val _summary = MutableStateFlow<SessionSummaryData?>(null)
  val summary: StateFlow<SessionSummaryData?> = _summary.asStateFlow()

  // ── nSuns CAP3 ─────────────────────────────────────────────────────────────
  // Only the main lifts of a CAP3 program are affected: they carry a cap3Tag and are priced from
  // the user's current training maxes rather than from stored weights. Everything else on the
  // screen — accessories included — is untouched.
  private val cap3Prefs = com.wildodds.gymtracker.data.cap3.Cap3Preferences(app)
  val cap3Config: StateFlow<com.wildodds.gymtracker.data.cap3.Cap3Config> = cap3Prefs.config
  .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
  com.wildodds.gymtracker.data.cap3.Cap3Config())

  /** Announcements from automatic training-max changes, shown as a snackbar. */
  private val _cap3Message = MutableStateFlow<String?>(null)
  val cap3Message: StateFlow<String?> = _cap3Message.asStateFlow()
  fun clearCap3Message() { _cap3Message.value = null }

  /** The prescription for a CAP3-tagged exercise, or null for any ordinary exercise. */
  fun cap3Block(exercise: Exercise): com.wildodds.gymtracker.data.cap3.Cap3LiftBlock? {
  val tag = com.wildodds.gymtracker.data.cap3.Cap3Tag.parse(exercise.cap3Tag) ?: return null
  return com.wildodds.gymtracker.data.cap3.Cap3Engine.block(cap3Config.value, tag)
  }

  /**
   * Tick or untick a prescribed set. Ticking logs it at the prescribed weight and reps, so the
   * checkbox is the log — history, volume and progress all see a normal set.
   */
  fun toggleCap3Set(exerciseIndex: Int, setIndex: Int, weight: Double, reps: Int, checked: Boolean) {
  updateSets(exerciseIndex) { sets ->
  sets.toMutableList().also {
  val row = it.getOrNull(setIndex) ?: return@also
  it[setIndex] = if (checked) {
  row.copy(weightKg = trimZero(weight), reps = reps.toString(), isPrefilled = false)
  } else {
  row.copy(weightKg = "", reps = "", isPrefilled = false)
  }
  }
  }
  if (checked) markLoggedThisVisit()
  viewModelScope.launch {
  if (checked) saveSet(exerciseIndex, setIndex)
  else _state.value.exercises.getOrNull(exerciseIndex)?.let { ex ->
  ex.sets.getOrNull(setIndex)?.let { repo.deleteSetLog(ex.workoutLogId, it.setNumber) }
  }
  }
  }

  /**
   * Log an AMRAP and let it move the training max on its own. CAP3's whole progression rule is
   * arithmetic the user already did in the gym, so a change is applied immediately and announced
   * rather than offered for confirmation.
   */
  fun logCap3Amrap(
  exerciseIndex: Int,
  setIndex: Int,
  block: com.wildodds.gymtracker.data.cap3.Cap3LiftBlock,
  set: com.wildodds.gymtracker.data.cap3.Cap3Set,
  achievedReps: Int
  ) {
  if (achievedReps <= 0) return
  toggleCap3Set(exerciseIndex, setIndex, set.weight, achievedReps, checked = true)

  val cfg = cap3Config.value
  val isEndOfCycle = weekNumber >= com.wildodds.gymtracker.data.cap3.CAP3_WEEKS
  val change = if (block.isMajorEffort) {
  com.wildodds.gymtracker.data.cap3.Cap3Progression.evaluateMajorEffort(
  cfg, block.slot, set.weight, achievedReps, set.repsTarget)
  } else {
  com.wildodds.gymtracker.data.cap3.Cap3Progression.evaluateAmrap(
  cfg, block.slot, set.weight, achievedReps, set.repsTarget, endOfCycle = isEndOfCycle)
  }
  if (!change.isChange) return
  viewModelScope.launch {
  cap3Prefs.setTrainingMax(change.slot, change.newTmKg, change.newEst1RmKg)
  _cap3Message.value = change.message
  }
  }

  private fun trimZero(v: Double): String =
  if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

  // ── Real-time fatigue (Phase 3F) ──────────────────────────────────────────
  // Suggestion-only signal; null when the feature is off or there isn't enough logged data.
  private var fatigueEnabled = false
  // Whether the user wants HR in the summary (Phase 2A flag). Gates the Health Connect read so we
  // only touch health data when a feature that uses it (summary or fatigue) is actually on.
  private var summaryHrEnabled = true
  // Phase 5B — write completed sessions back to Health Connect (opt-in, default off).
  private var writeToHealthConnect = false
  private val _fatigue = MutableStateFlow<FatigueResult?>(null)
  val fatigue: StateFlow<FatigueResult?> = _fatigue.asStateFlow()
  private val _fatigueSuggestionDismissed = MutableStateFlow(false)
  val fatigueSuggestionDismissed: StateFlow<Boolean> = _fatigueSuggestionDismissed.asStateFlow()
  fun dismissFatigueSuggestion() { _fatigueSuggestionDismissed.value = true }

  // ── Recovery adjustment overlay (Phase 4B) ─────────────────────────────────
  // A reversible "lighter session" the user accepted from a recovery/adaptive suggestion. It scales
  // prefill load + visible set count only — never logged data or the saved program. Cleared on revert.
  private var adaptivePlanEnabled = false
  private var recoveryAdjustment: RecoveryAdjustment? = null
  private val _recoveryOverlay = MutableStateFlow<RecoveryAdjustment?>(null)
  val recoveryOverlay: StateFlow<RecoveryAdjustment?> = _recoveryOverlay.asStateFlow()

  /** Round a recovery-scaled prefill weight to a clean 0.5 kg step (no-op when scale is 1.0). */
  private fun scaleWeight(weight: Float, scale: Float): Float =
  if (scale == 1f) weight else ((weight * scale) / 0.5f).roundToInt() * 0.5f

  private fun recomputeFatigue(avgHr: Int? = null) {
  if (!fatigueEnabled) { _fatigue.value = null; return }
  val perExercise = _state.value.exercises.map { ex ->
  ex.sets.mapNotNull { s ->
  val reps = s.reps.toIntOrNull() ?: return@mapNotNull null
  FatigueSet(s.weightKg.toFloatOrNull(), reps, s.rpe.toFloatOrNull())
  }
  }
  // Live (mid-session) recompute has no HR — Health Connect data lands at completion, so [avgHr]
  // is supplied only on the final score. Reps + RPE drive the in-session indicator; HR refines
  // the persisted score. Absent HR → identical behaviour to before (degrades cleanly).
  _fatigue.value = FatigueEngine.score(perExercise, avgHeartRate = avgHr)
  }

  // ── Travel mode (Phase 3D) ────────────────────────────────────────────────
  // A NON-DESTRUCTIVE display overlay: when active, exercises render as their minimal-equipment
  // translations, but logging still writes to the original exercises' workout logs (positional).
  // Nothing in the program is mutated; exiting simply clears the overlay. Indexed by position.
  private val _travelOverlay = MutableStateFlow<List<TranslatedExercise>?>(null)
  val travelOverlay: StateFlow<List<TranslatedExercise>?> = _travelOverlay.asStateFlow()
  var lastTravelEquipment: Set<TravelEquipment> = emptySet()
  private set

  fun activateTravelMode(selected: Set<TravelEquipment>) {
  lastTravelEquipment = selected
  // Record that travel mode was used (drives the "Have gym, will travel" achievement, Phase 6A).
  getApplication<Application>().getSharedPreferences("gym_prefs", Context.MODE_PRIVATE)
  .edit().putBoolean("used_travel_mode", true).apply()
  val planned = _state.value.exercises.map {
  PlannedExercise(it.exercise.name, it.exercise.sets, it.exercise.repsTarget)
  }
  val equipment = TravelTranslator.equipmentSetFor(selected)
  // Pure translation — no DB writes, so the program stays untouched.
  _travelOverlay.value = TravelTranslator.translate(planned, ExerciseGraph.engine(getApplication()), equipment)
  }

  fun exitTravelMode() { _travelOverlay.value = null }

  // ── Injury accommodation (Phase 3E) ───────────────────────────────────────
  // A persisted, non-destructive overlay: contraindicated lifts are shown swapped and prehab is
  // surfaced as guidance. Stored across sessions (InjuryAccommodationStore); reverting clears it.
  private val _injuryOverlay = MutableStateFlow<InjuryOverlayData?>(null)
  val injuryOverlay: StateFlow<InjuryOverlayData?> = _injuryOverlay.asStateFlow()

  private fun buildInjuryOverlay(exercises: List<Exercise>): InjuryOverlayData? {
  val acc = InjuryAccommodationStore.load(getApplication()) ?: return null
  val planned = exercises.map { PlannedExercise(it.name, it.sets, it.repsTarget) }
  val prehab = PrehabLibrary.forRegion(getApplication(), acc.region)
  return InjuryAccommodator.build(
  planned, ExerciseGraph.engine(getApplication()), acc.region, acc.avoidTags, prehab)
  }

  /** Apply a (safe, amber/green) accommodation — persists it and overlays the current session. */
  fun applyInjuryAccommodation(region: BodyRegion, avoidTags: Set<String>) {
  InjuryAccommodationStore.save(getApplication(), region, avoidTags, System.currentTimeMillis())
  _injuryOverlay.value = buildInjuryOverlay(_state.value.exercises.map { it.exercise })
  }

  /** Clear the accommodation — the original program view returns immediately. */
  fun revertInjuryAccommodation() {
  InjuryAccommodationStore.clear(getApplication())
  _injuryOverlay.value = null
  }

  private val debounceJobs = mutableMapOf<String, Job>()

  // Cached session info needed for propagation calls
  private var cachedProgramId: Long  = 0L
  private var cachedDayNumber: Int  = 0
  var isFlexible: Boolean  = false
  private set
  var trackRpe: Boolean  = false
  private set
  var trackOneRm: Boolean  = false
  private set

  // %1RM logging is OFF by default for every session; the user opts in per-session via the
  // overflow menu ("Log %1RM"). This is reactive so toggling re-renders the set rows live.
  private val _oneRmVisible = MutableStateFlow(false)
  val oneRmVisible: StateFlow<Boolean> = _oneRmVisible
  fun toggleOneRmVisible() { _oneRmVisible.value = !_oneRmVisible.value }

  // Live heart rate (Phase 4+): polled from the wearable while the session is open. Null when no
  // wearable data / the HR feature is off, so the indicator simply hides.
  private val _liveHeartRate = MutableStateFlow<Int?>(null)
  val liveHeartRate: StateFlow<Int?> = _liveHeartRate
  private var hrPollJob: Job? = null

  private fun startLiveHeartRate() {
  if (!summaryHrEnabled) return
  hrPollJob?.cancel()
  hrPollJob = viewModelScope.launch {
  while (true) {
  val now = System.currentTimeMillis()
  val hr = withContext(Dispatchers.IO) {
  runCatching {
  wearable.heartRateForSession(Instant.ofEpochMilli(now - 120_000), Instant.ofEpochMilli(now))
  }.getOrNull()
  }
  _liveHeartRate.value = hr?.let { it.series.lastOrNull() ?: it.avgBpm }
  delay(15_000)
  }
  }
  }

  init { viewModelScope.launch { loadSession() } }

  // ── Exercise notes (SharedPreferences, keyed by exercise name) ────────────

  // Athlete notes: the lifter's own notes for an exercise, shared by every session (and program)
  // that has it. Keyed by the normalised name so "Bench Press" and "bench press " are one exercise;
  // notes saved under the old exact-name key are still read, and move to the new key on next save.
  // (Coach notes are different: they're the program's Exercise.notes and are never edited here.)
  fun getNoteForExercise(name: String): String =
  notePrefs.getString("note_${exerciseKey(name)}", null)
  ?: notePrefs.getString("note_$name", null)
  ?: ""

  fun saveNoteForExercise(name: String, note: String) {
  val edit = notePrefs.edit().remove("note_$name")
  if (note.isBlank()) edit.remove("note_${exerciseKey(name)}")
  else edit.putString("note_${exerciseKey(name)}", note.trim())
  edit.apply()
  }

  private suspend fun loadSession() {
  val session  = repo.getSessionById(sessionId) ?: return
  val exercises = repo.getExercisesForSession(sessionId)
  val isCompleted = repo.isSessionCompleted(sessionId, weekNumber)

  cachedProgramId = session.programId
  cachedDayNumber = session.dayNumber
  val prog  = repo.getCurrentProgram()
  isFlexible  = prog?.isFlexible  ?: false
  trackRpe  = prog?.trackRpe  ?: false
  trackOneRm  = prog?.trackOneRm  ?: false

  val smartProgression = themePrefs.flag(SettingsRegistry.SMART_PROGRESSION, true).first()
  fatigueEnabled = themePrefs.flag(SettingsRegistry.REALTIME_FATIGUE, false).first()
  summaryHrEnabled = themePrefs.flag(SettingsRegistry.SUMMARY_HEART_RATE, true).first()
  // Recovery / readiness ("train light because you slept poorly") feature removed — never apply
  // a load/volume adjustment to prefill.
  adaptivePlanEnabled = false
  recoveryAdjustment = null
  _recoveryOverlay.value = null
  writeToHealthConnect = themePrefs.flag(SettingsRegistry.WRITE_TO_HEALTH_CONNECT, false).first()
  val trackingMode = when {
  trackRpe  -> TrackingMode.RPE
  trackOneRm -> TrackingMode.PERCENT_1RM
  else  -> TrackingMode.STRAIGHT_SETS
  }

  // The user's configured 1RMs — used to prefill working weight for exercises that prescribe a
  // %1RM target (e.g. "Bench Press @ 80%"). Computed once per session load.
  val userOneRms: Map<MainLift, Float> = mapOf(
  MainLift.SQUAT    to themePrefs.oneRmSquat.first(),
  MainLift.BENCH    to themePrefs.oneRmBench.first(),
  MainLift.DEADLIFT to themePrefs.oneRmDeadlift.first(),
  MainLift.OHP      to themePrefs.oneRmOhp.first()
  )

  val exerciseStates = exercises.map { exercise ->
  val log = repo.getOrCreateWorkoutLog(exercise.id, sessionId, weekNumber)

  // Current-week sets already logged
  val currentSets = repo.getSetLogsForWorkoutLog(log.id)

  // Where this exercise's history lives. Normally its own day/position — but an exercise moved
  // here from another day has no history in this slot yet (earlier weeks hold whatever used to be
  // here), so until it has been logged in its new home, "last time" is read from where it came from.
  val hist = historyPosition(exercise, session)

  // Previous-week sets - use POSITION query (dayNumber + orderIndex) so it works
  // regardless of which exerciseId was assigned to that slot in the prior week.
  val prevSets = repo.getPrevWeekSetLogsByPosition(
  programId  = session.programId,
  dayNumber  = hist.day,
  currentWeek = hist.beforeWeek,
  orderIndex = hist.index
  )

  // Previous-week progression choice for this exercise's position
  val prevChoice = repo.getPrevWeekProgressionByPosition(
  programId  = session.programId,
  dayNumber  = hist.day,
  currentWeek = hist.beforeWeek,
  orderIndex = hist.index
  )

  // ── Smart progression (Phase 3B) — suggestion + adjusted carry-forward ──
  var suggestedKey: String? = null
  var suggestionRationale: String? = null
  var adjustedPrev: Map<Int, ProgressionEngine.AdjustedSet> = emptyMap()
  if (smartProgression) {
  val allWeekLogs = repo.getAllWeekSetLogs(session.programId, hist.day, hist.index)
  // The old slot is someone else's from this week on; only its earlier weeks are this exercise.
  .let { logs -> if (hist.moved) logs.filter { it.weekNumber < hist.beforeWeek } else logs }
  val (repLow, repHigh) = parseRepRange(exercise.repsTarget)
  val rpeTarget = parseRpeTarget(exercise.rpeTarget)

  // Smart default for the picker: AUTO over history up to and including this week.
  val historyToNow = buildHistory(allWeekLogs, weekNumber)
  if (historyToNow.isNotEmpty()) {
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = historyToNow, trackingMode = trackingMode,
  repLow = repLow, repHigh = repHigh, rpeTarget = rpeTarget, style = ProgressionStyle.AUTO))
  suggestedKey = ProgressionEngine.pickerKeyFor(rec.action)
  suggestionRationale = rec.rationale
  }

  // Apply-on-carry-forward: adjust the prefill by last week's chosen style. Editable, reversible.
  if (prevChoice != null && prevSets.isNotEmpty()) {
  val carryRec = ProgressionEngine.recommend(ProgressionInput(
  history = buildHistory(allWeekLogs, weekNumber - 1), trackingMode = trackingMode,
  repLow = repLow, repHigh = repHigh, rpeTarget = rpeTarget,
  style = ProgressionEngine.styleFromPickerKey(prevChoice)))
  val ordered = prevSets.sortedBy { it.setNumber }
  val adjusted = ProgressionEngine.applyToCarryForward(
  ordered.map { SetRecord(it.weightKg, it.reps) }, carryRec)
  adjustedPrev = ordered.mapIndexedNotNull { i, sl -> adjusted.getOrNull(i)?.let { sl.setNumber to it } }.toMap()
  }
  }

  // Recovery overlay applies to a fresh (not-yet-logged) exercise only: scale prefill load and
  // trim back-off sets. Once anything is logged this week it's the user's data — untouched.
  val applyRecovery = recoveryAdjustment != null && currentSets.isEmpty()
  val loadScale = if (applyRecovery) recoveryAdjustment!!.loadScale else 1f
  // ── Evolving sets ─────────────────────────────────────────────────────────
  // A program built with a per-set prescription (warm-up ramp, pyramid, top set + back-off) stores
  // one planned_sets row per set. When present those rows ARE the prescription and decide how many
  // rows to show; otherwise nothing changes and the exercise's single `sets`/`repsTarget` is used
  // exactly as before, which is every program that predates this.
  val planned = db.plannedSetDao().forExercise(exercise.id).sortedBy { it.setNumber }
  val plannedCount = planned.size

  // The weight the percentage ladder hangs off: whatever the program named on its 100% set (or,
  // failing that, its first set with a literal load). Resolved ONCE here so every row can show a
  // real target weight rather than each row doing its own arithmetic.
  val plannedTopLoad = planned.firstOrNull { it.loadPercent == 100 && it.load.isNotBlank() }?.load?.toFloatOrNull()
  ?: planned.firstOrNull { it.load.isNotBlank() }?.load?.toFloatOrNull()

  // Only call something a "top set" when it genuinely stands above lighter working sets — three
  // straight sets at 100% are just the working sets.
  val workingPercents = planned.filter { it.type != "WARMUP" }.mapNotNull { it.loadPercent }
  val hasDistinctTopSet = workingPercents.count { it == 100 } == 1 && workingPercents.any { it < 100 }

  // Never hide a set that's already been logged this week, even if the prescription has since
  // dropped below it (e.g. a progression started mid-session that prescribes fewer sets).
  val loggedCount = currentSets.maxOfOrNull { it.setNumber } ?: 0
  val baseCount = maxOf(if (plannedCount > 0) plannedCount else exercise.sets, loggedCount)
  val setCount = if (applyRecovery)
  (baseCount * recoveryAdjustment!!.volumeScale).roundToInt().coerceIn(1, baseCount)
  else baseCount

  // Program-prescribed %1RM working weights derived from the user's configured 1RM — per SET,
  // because slash-lists ("58.5/67.5/76.5%", i.e. 5/3/1's wave) prescribe a different percentage
  // for each set. Takes precedence over week-to-week carry-forward so a percentage-based
  // program (Candito / 5/3/1 / Russian / Calgary) prefills each week's prescribed loads.
  val pct1rmPrefills = com.wildodds.gymtracker.data.profile.PctPrefill.weights(
  exercise.name, exercise.pct1rmTarget, setCount, userOneRms)

  val sets = (1..setCount).map { setNum ->
  val existing = currentSets.find { it.setNumber == setNum }
  val prev  = prevSets.find { it.setNumber == setNum }
  val adj  = adjustedPrev[setNum]
  val pct1rmPrefill = pct1rmPrefills?.getOrNull(setNum - 1)
  val prefillWeight = (adj?.weightKg ?: prev?.weightKg)?.let { scaleWeight(it, loadScale) }
  // On the 12-week progression the reps are the scheme (week 4 wants 6 even though week 3 did 5),
  // so prefill this week's target; the weight still carries over from last time.
  val progressionReps = if (com.wildodds.gymtracker.data.builder.TwelveWeekLinear.MODIFIER in exercise.modifiers)
  exercise.repsTarget.toIntOrNull() else null
  val prefillReps  = progressionReps ?: adj?.reps ?: prev?.reps
  val plan = planned.getOrNull(setNum - 1)
  SetRowState(
  setNumber = setNum,
  weightKg  = existing?.weightKg?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
  ?: pct1rmPrefill?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
  ?: prefillWeight?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
  ?: "",
  reps  = existing?.reps?.toString()  ?: prefillReps?.toString()  ?: "",
  rpe  = existing?.rpe?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "",
  weightRight = existing?.weightRightKg?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
  ?: prev?.weightRightKg?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
  ?: "",
  repsRight = existing?.repsRight?.toString() ?: prev?.repsRight?.toString() ?: "",
  pct1rm  = existing?.pct1rm?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "",
  isPrefilled = existing == null && (prev != null || pct1rmPrefill != null || progressionReps != null),
  workoutLogId = log.id,
  plannedType = plan?.type,
  plannedReps = plan?.reps.orEmpty(),
  plannedPercent = plan?.loadPercent,
  plannedLoad = plan?.load?.takeIf { it.isNotBlank() }
  ?: com.wildodds.gymtracker.data.builder.SetShape
  .resolveLoad(plannedTopLoad, plan?.loadPercent)
  ?.let { w -> if (w % 1f == 0f) w.toInt().toString() else w.toString() }
  .orEmpty(),
  plannedRest = plan?.restSeconds,
  isTopSet = hasDistinctTopSet && plan?.loadPercent == 100
  )
  }

  ExerciseUiState(
  exercise  = exercise,
  sets  = sets,
  prevProgressionChoice  = prevChoice,
  currentProgressionChoice = log.progressionChoice,
  suggestedProgressionKey  = suggestedKey,
  suggestionRationale  = suggestionRationale,
  workoutLogId  = log.id
  )
  }

  _state.value = SessionUiState(
  session  = session,
  exercises  = exerciseStates,
  isCompleted = isCompleted,
  isLoading  = false
  )
  // Recompute any active injury accommodation for this session's exercises.
  _injuryOverlay.value = buildInjuryOverlay(exercises)
  recomputeFatigue()
  startLiveHeartRate()
  }

  // ── Set editing ────────────────────────────────────────────────────────────

  fun updateSetWeight(exerciseIndex: Int, setIndex: Int, value: String) {
  markLoggedThisVisit()
  val exercises = _state.value.exercises.toMutableList()
  val ex  = exercises[exerciseIndex]
  val oldSets = ex.sets

  // Autofill from set 1 applies on EVERY week: blank, previously auto-filled, and
  // carried-forward prefill sets follow set 1; a set the user manually edited is never
  // clobbered. (See [autofillWeights].) Non-first sets just take the typed value.
  val newSets = if (oldSets.any { it.plannedPercent != null }) {
  // Any row of a ramp can be the anchor — people often type the set they are about to do.
  autofillFromRamp(oldSets, value, anchorIndex = setIndex)
  } else if (setIndex == 0) {
  autofillWeights(oldSets, value)
  } else {
  oldSets.toMutableList().also {
  it[setIndex] = it[setIndex].copy(weightKg = value, isPrefilled = false, isWeightAutoFilled = false)
  }
  }

  exercises[exerciseIndex] = ex.copy(sets = newSets)
  _state.value = _state.value.copy(exercises = exercises)

  // Persist the edited set plus any set the autofill actually changed.
  newSets.indices.forEach { i ->
  if (i == setIndex || newSets[i].weightKg != oldSets[i].weightKg) scheduleSave(exerciseIndex, i)
  }
  }

  fun updateSetWeightManual(exerciseIndex: Int, setIndex: Int, value: String) {
  // Called when user edits a non-first-set weight directly — clears auto-fill flag
  markLoggedThisVisit()
  updateSets(exerciseIndex) { sets ->
  sets.toMutableList().also { it[setIndex] = it[setIndex].copy(weightKg = value, isWeightAutoFilled = false, isPrefilled = false) }
  }
  scheduleSave(exerciseIndex, setIndex)
  }

  fun updateSetReps(exerciseIndex: Int, setIndex: Int, value: String) {
  markLoggedThisVisit()
  val prevSet = _state.value.exercises.getOrNull(exerciseIndex)?.sets?.getOrNull(setIndex)
  val wasComplete = prevSet?.let { it.weightKg.toFloatOrNull() != null && it.reps.toIntOrNull() != null } ?: false
  updateSets(exerciseIndex) { sets ->
  sets.toMutableList().also { it[setIndex] = it[setIndex].copy(reps = value, isPrefilled = false) }
  }
  scheduleSave(exerciseIndex, setIndex)
  val newSet = _state.value.exercises.getOrNull(exerciseIndex)?.sets?.getOrNull(setIndex)
  val isNowComplete = newSet?.let { it.weightKg.toFloatOrNull() != null && it.reps.toIntOrNull() != null } ?: false
  if (!wasComplete && isNowComplete) onSetCompleted()
  }

  fun updateSetWeightRight(exerciseIndex: Int, setIndex: Int, value: String) {
  markLoggedThisVisit()
  updateSets(exerciseIndex) { sets ->
  sets.toMutableList().also { it[setIndex] = it[setIndex].copy(weightRight = value, isPrefilled = false) }
  }
  scheduleSave(exerciseIndex, setIndex)
  }

  fun updateSetRepsRight(exerciseIndex: Int, setIndex: Int, value: String) {
  markLoggedThisVisit()
  updateSets(exerciseIndex) { sets ->
  sets.toMutableList().also { it[setIndex] = it[setIndex].copy(repsRight = value, isPrefilled = false) }
  }
  scheduleSave(exerciseIndex, setIndex)
  }

  fun updateSetRpe(exerciseIndex: Int, setIndex: Int, value: String) {
  markLoggedThisVisit()
  updateSets(exerciseIndex) { sets ->
  sets.toMutableList().also { it[setIndex] = it[setIndex].copy(rpe = value) }
  }
  scheduleSave(exerciseIndex, setIndex)
  }

  fun updateSetPct1rm(exerciseIndex: Int, setIndex: Int, value: String) {
  markLoggedThisVisit()
  updateSets(exerciseIndex) { sets ->
  sets.toMutableList().also { it[setIndex] = it[setIndex].copy(pct1rm = value) }
  }
  scheduleSave(exerciseIndex, setIndex)
  }

  /**
   * Delete exactly one set row, identified by its [rowKey] rather than a list index: an index
   * captured by the swipe gesture goes stale the moment an earlier row is removed, which is how one
   * swipe used to take out the wrong set (or a second one).
   */
  fun deleteSet(exerciseIndex: Int, rowKey: Long) {
  val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
  val setIndex = ex.sets.indexOfFirst { it.rowKey == rowKey }
  if (setIndex < 0) return
  val newSets = ex.sets.toMutableList().also { it.removeAt(setIndex) }
  val renumbered = newSets.mapIndexed { i, s -> s.copy(setNumber = i + 1) }
  updateSets(exerciseIndex) { renumbered }
  // Pending debounced saves are keyed by (exercise, row index) and would now land on the wrong
  // rows; the rewrite below persists everything they were going to.
  debounceJobs.keys.filter { it.startsWith("$exerciseIndex-") }.forEach { debounceJobs.remove(it)?.cancel() }
  viewModelScope.launch(Dispatchers.IO) {
  // The logs are stored by set number, and every set after the deleted one just moved up a
  // number. Deleting only the removed row left the later rows under their old numbers, so on
  // reload a set vanished and the rest shifted — it looked like several sets had been deleted.
  // Rewrite this exercise's logs to match the renumbered rows instead. Untouched carry-forward
  // prefills were never logged, so they stay unlogged.
  repo.replaceSetLogs(ex.workoutLogId, renumbered.filter { !it.isPrefilled }.mapNotNull { it.toSetLog(ex.workoutLogId) })
  repo.updateExerciseSets(
  exerciseId  = ex.exercise.id,
  sets  = renumbered.size,
  orderIndex  = ex.exercise.orderIndex,
  dayNumber  = cachedDayNumber,
  programId  = cachedProgramId,
  currentWeek = weekNumber
  )
  }
  }

  fun addSet(exerciseIndex: Int) {
  val ex  = _state.value.exercises.getOrNull(exerciseIndex) ?: return
  val newCount = ex.sets.size + 1
  updateSets(exerciseIndex) { sets ->
  sets + SetRowState(setNumber = newCount, workoutLogId = ex.workoutLogId)
  }
  // Persist new sets count to this exercise AND all future-week exercises at same position
  viewModelScope.launch(Dispatchers.IO) {
  repo.updateExerciseSets(
  exerciseId  = ex.exercise.id,
  sets  = newCount,
  orderIndex  = ex.exercise.orderIndex,
  dayNumber  = cachedDayNumber,
  programId  = cachedProgramId,
  currentWeek = weekNumber
  )
  }
  }

  /** Add a brand-new exercise to this session and all future weeks at the same position. */
  fun addNewExercise(name: String, sets: Int, repsTarget: String) {
  if (name.isBlank()) return
  val orderIndex = _state.value.exercises.size
  viewModelScope.launch(Dispatchers.IO) {
  repo.createExerciseAndPropagate(
  name  = name.trim(),
  sets  = sets,
  repsTarget  = repsTarget.ifBlank { "8-12" },
  sessionId  = sessionId,
  orderIndex  = orderIndex,
  programId  = cachedProgramId,
  dayNumber  = cachedDayNumber,
  currentWeek = weekNumber
  )
  loadSession()
  }
  }

  // ── Unilateral mode ───────────────────────────────────────────────────────

  /** Cycles 0 (normal) → 1 (single weight, L/R reps) → 2 (L/R weight + reps) → 0. */
  fun cycleUnilateralMode(exerciseIndex: Int) {
  val exercises = _state.value.exercises
  val ex = exercises.getOrNull(exerciseIndex) ?: return
  val newMode = (ex.exercise.unilateralMode + 1) % 3
  val updated = exercises.toMutableList()
  updated[exerciseIndex] = ex.copy(exercise = ex.exercise.copy(unilateralMode = newMode))
  _state.value = _state.value.copy(exercises = updated)
  viewModelScope.launch(Dispatchers.IO) {
  repo.setUnilateralMode(
  exerciseId = ex.exercise.id,
  mode  = newMode,
  orderIndex = ex.exercise.orderIndex,
  dayNumber  = cachedDayNumber,
  programId  = cachedProgramId
  )
  }
  }

  // ── Delete exercise ───────────────────────────────────────────────────────

  /** Delete an exercise from this session and every other week of the program. */
  fun deleteExercise(exerciseIndex: Int) {
  val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
  // Break any superset link first so the partner doesn't keep an orphan groupId
  if (ex.exercise.supersetGroupId != null) unlinkSuperset(exerciseIndex)
  viewModelScope.launch(Dispatchers.IO) {
  repo.deleteExerciseAtPosition(
  orderIndex = ex.exercise.orderIndex,
  dayNumber  = cachedDayNumber,
  programId  = cachedProgramId
  )
  loadSession()
  }
  }

  // ── Move exercise to another day ──────────────────────────────────────────

  /** Another training day this week that an exercise can be moved to. */
  data class MoveTargetDay(val dayNumber: Int, val name: String, val exerciseNames: List<String>)

  // exerciseId → "originDay:originIndex:moveWeek". Lets a moved exercise keep reading its "last
  // time" from the slot it came from until it has history in its new one (see historyPosition).
  private val movePrefs = app.getSharedPreferences("moved_exercises", Context.MODE_PRIVATE)

  /** Where to read an exercise's past weeks from: a day/position, and only weeks before [beforeWeek]. */
  private data class HistorySource(val day: Int, val index: Int, val beforeWeek: Int, val moved: Boolean)

  private suspend fun historyPosition(exercise: Exercise, session: Session): HistorySource {
  val own = HistorySource(session.dayNumber, exercise.orderIndex, weekNumber, moved = false)
  val parts = movePrefs.getString(exercise.id.toString(), null)?.split(":")?.mapNotNull { it.toIntOrNull() }
  if (parts == null || parts.size != 3) return own
  val (originDay, originIdx, moveWeek) = parts
  if (weekNumber < moveWeek) return own
  // Once it has been logged in its new slot, that slot IS its history.
  val latestHere = repo.latestLoggedWeekByPosition(session.programId, session.dayNumber, exercise.orderIndex, weekNumber)
  if (latestHere != null && latestHere >= moveWeek) return own
  // Otherwise the old slot — but only weeks before the move; from then on it belongs to whatever
  // slid up into it.
  return HistorySource(originDay, originIdx, moveWeek, moved = true)
  }

  /** The other training days in this week, each with its current exercise order. */
  suspend fun loadMoveTargets(): List<MoveTargetDay> = withContext(Dispatchers.IO) {
  repo.getSessionsForWeek(weekNumber, cachedProgramId)
  .filter { it.id != sessionId }
  .map { s -> MoveTargetDay(s.dayNumber, s.name, repo.getExercisesForSession(s.id).map { it.name }) }
  }

  /**
   * Move an exercise to [target] day, landing at [insertAt] in that day's order — this week and
   * the weeks after it. It disappears from this session; anything already logged goes with it, and
   * in its new day it still prefills from the last time it was done.
   */
  fun moveExerciseToDay(exerciseIndex: Int, target: MoveTargetDay, insertAt: Int) {
  val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
  viewModelScope.launch {
  // Pending saves are keyed by list index, which the reload below reshuffles — write them now.
  flushPendingSaves()
  val fromIndex = ex.exercise.orderIndex
  val moved = repo.moveExerciseToDay(
  programId = cachedProgramId, fromDay = cachedDayNumber, fromIndex = fromIndex,
  exerciseName = ex.exercise.name, toDay = target.dayNumber, toIndex = insertAt,
  fromWeek = weekNumber
  )
  if (moved.isEmpty()) return@launch
  // Where its history lives. A row that was itself moved before keeps its original origin until
  // it has been logged since, so moving twice in a week still finds "last time".
  val edit = movePrefs.edit()
  moved.forEach { (id, _) ->
  val prior = movePrefs.getString(ex.exercise.id.toString(), null)?.split(":")?.mapNotNull { it.toIntOrNull() }
  val origin = if (prior != null && prior.size == 3 && prior[2] == weekNumber) "${prior[0]}:${prior[1]}"
  else "$cachedDayNumber:$fromIndex"
  edit.putString(id.toString(), "$origin:$weekNumber")
  }
  edit.apply()
  _cap3Message.value = "Moved ${ex.exercise.name} to ${target.name}"
  loadSession()
  }
  }

  /** Run every debounced set save immediately (before anything reshuffles exercise indices). */
  private suspend fun flushPendingSaves() {
  val pending = debounceJobs.keys.toList()
  pending.forEach { key ->
  debounceJobs.remove(key)?.cancel()
  val (ei, si) = key.split("-").map { it.toInt() }
  saveSet(ei, si)
  }
  }

  // ── 12-week linear progression ────────────────────────────────────────────

  fun isOnLinear12(ex: ExerciseUiState): Boolean =
    com.wildodds.gymtracker.data.builder.TwelveWeekLinear.MODIFIER in ex.exercise.modifiers

  /** The starting weight a progression was set up with, when one was given. */
  fun linear12StartLoad(ex: ExerciseUiState): Float? = ex.exercise.loadValue?.toFloat()

  /**
   * Which week of the 12 (1-12) this session is, for a progression exercise — read from its coach
   * note, which is the thing the athlete sees. Null when it isn't on one.
   */
  fun linear12Week(ex: ExerciseUiState): Int? {
    if (!isOnLinear12(ex)) return null
    return Regex("Week (\\d+) of 12").find(ex.exercise.notes)?.groupValues?.get(1)?.toIntOrNull()
  }

  /**
   * Start the 12-week linear progression on this exercise with THIS week as its week 1. This
   * session's sets × reps change straight away; later weeks follow the scheme.
   */
  fun startLinear12(exerciseIndex: Int, startLoad: Float?) {
    val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
    viewModelScope.launch {
      flushPendingSaves()
      repo.applyLinear12(
        programId = cachedProgramId, dayNumber = cachedDayNumber, orderIndex = ex.exercise.orderIndex,
        exerciseName = ex.exercise.name, fromWeek = weekNumber, startLoad = startLoad
      )
      _cap3Message.value = "${ex.exercise.name}: 12-week progression started — this is week 1"
      loadSession()
    }
  }

  /** Take this exercise off the progression from this week on, keeping this week's sets × reps. */
  fun stopLinear12(exerciseIndex: Int) {
    val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
    viewModelScope.launch {
      flushPendingSaves()
      repo.stopLinear12(
        programId = cachedProgramId, dayNumber = cachedDayNumber, orderIndex = ex.exercise.orderIndex,
        exerciseName = ex.exercise.name, fromWeek = weekNumber
      )
      _cap3Message.value = "${ex.exercise.name}: 12-week progression stopped"
      loadSession()
    }
  }

  // ── Per-exercise weight autofill ("A" toggle) ─────────────────────────────

  // Names (normalised) of exercises the user switched autofill OFF for. Keyed by name, like notes,
  // so the choice follows the exercise from week to week. Absent = on, the default.
  private val autofillPrefs = app.getSharedPreferences("exercise_autofill", Context.MODE_PRIVATE)
  private val _autofillOff = MutableStateFlow(autofillPrefs.getStringSet("off", emptySet()).orEmpty().toSet())
  val autofillOff: StateFlow<Set<String>> = _autofillOff.asStateFlow()

  fun toggleExerciseAutofill(exerciseName: String) {
  val key = exerciseKey(exerciseName)
  val next = _autofillOff.value.let { if (key in it) it - key else it + key }
  _autofillOff.value = next
  autofillPrefs.edit().putStringSet("off", next).apply()
  }

  // ── Superset linking ──────────────────────────────────────────────────────

  fun linkSuperset(exerciseIndex: Int, partnerIndex: Int) {
    val exercises = _state.value.exercises
    val ex      = exercises.getOrNull(exerciseIndex) ?: return
    val partner = exercises.getOrNull(partnerIndex)  ?: return
    val newGroupId = (exercises.mapNotNull { it.exercise.supersetGroupId }.maxOrNull() ?: 0) + 1
    val updated = exercises.toMutableList()
    updated[exerciseIndex] = ex.copy(exercise = ex.exercise.copy(supersetGroupId = newGroupId))
    updated[partnerIndex]  = partner.copy(exercise = partner.exercise.copy(supersetGroupId = newGroupId))
    _state.value = _state.value.copy(exercises = updated)
    viewModelScope.launch(Dispatchers.IO) {
      repo.linkSuperset(
        exerciseId1 = ex.exercise.id,      orderIndex1 = ex.exercise.orderIndex,
        exerciseId2 = partner.exercise.id, orderIndex2 = partner.exercise.orderIndex,
        groupId     = newGroupId,
        dayNumber   = cachedDayNumber, programId = cachedProgramId
      )
    }
  }

  fun unlinkSuperset(exerciseIndex: Int) {
    val exercises = _state.value.exercises
    val ex      = exercises.getOrNull(exerciseIndex) ?: return
    val groupId = ex.exercise.supersetGroupId ?: return
    val partnerIndex = exercises.indexOfFirst { it.exercise.id != ex.exercise.id && it.exercise.supersetGroupId == groupId }
    val updated = exercises.toMutableList()
    updated[exerciseIndex] = ex.copy(exercise = ex.exercise.copy(supersetGroupId = null))
    if (partnerIndex >= 0) updated[partnerIndex] = updated[partnerIndex].copy(exercise = updated[partnerIndex].exercise.copy(supersetGroupId = null))
    _state.value = _state.value.copy(exercises = updated)
    viewModelScope.launch(Dispatchers.IO) {
      repo.unlinkSuperset(
        orderIndex1 = ex.exercise.orderIndex,
        orderIndex2 = exercises.getOrNull(partnerIndex)?.exercise?.orderIndex ?: -1,
        dayNumber   = cachedDayNumber, programId = cachedProgramId
      )
    }
  }

  // ── Exercise reorder ──────────────────────────────────────────────────────

  fun moveExercise(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    val current = _state.value.exercises
    val reordered = current.toMutableList().also {
      val item = it.removeAt(fromIndex)
      it.add(toIndex, item)
    }.mapIndexed { newIdx, ex -> ex.copy(exercise = ex.exercise.copy(orderIndex = newIdx)) }

    val reorderMap = reordered.map { newEx ->
      val oldEx = current.first { it.exercise.id == newEx.exercise.id }
      Triple(newEx.exercise.id, oldEx.exercise.orderIndex, newEx.exercise.orderIndex)
    }

    _state.value = _state.value.copy(exercises = reordered)
    viewModelScope.launch(Dispatchers.IO) {
      repo.reorderExercises(reorderMap, cachedDayNumber, cachedProgramId, weekNumber)
    }
  }

  // ── Exercise name editing ──────────────────────────────────────────────────

  fun updateExerciseName(exerciseIndex: Int, newName: String) {
  if (newName.isBlank()) return
  val exercises = _state.value.exercises.toMutableList()
  val old = exercises[exerciseIndex]
  exercises[exerciseIndex] = old.copy(exercise = old.exercise.copy(name = newName))
  _state.value = _state.value.copy(exercises = exercises)
  viewModelScope.launch(Dispatchers.IO) {
  repo.updateExerciseName(
  exerciseId  = old.exercise.id,
  newName  = newName,
  orderIndex  = old.exercise.orderIndex,
  dayNumber  = cachedDayNumber,
  programId  = cachedProgramId,
  currentWeek = weekNumber
  )
  }
  }

  // ── Exercise swap / regress / progress (Phase 3C) ─────────────────────────

  /**
   * Replace an exercise with [newName] (a swap, or an easier/harder variation). Updates this
   * session immediately and, when [applyToFuture], the same position in later weeks. Then reloads
   * so prefill/suggestions re-derive for the new movement.
   */
  fun swapExercise(exerciseIndex: Int, newName: String, applyToFuture: Boolean) {
  if (newName.isBlank()) return
  val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
  // Optimistic UI update.
  val updated = _state.value.exercises.toMutableList()
  updated[exerciseIndex] = ex.copy(exercise = ex.exercise.copy(name = newName))
  _state.value = _state.value.copy(exercises = updated)
  viewModelScope.launch {
  withContext(Dispatchers.IO) {
  repo.swapExercise(
  exerciseId = ex.exercise.id, newName = newName, orderIndex = ex.exercise.orderIndex,
  dayNumber = cachedDayNumber, programId = cachedProgramId, currentWeek = weekNumber,
  applyToFuture = applyToFuture
  )
  }
  loadSession()
  }
  }

  /** Best easier variation for an exercise (or null), for the one-tap "Make easier" control. */
  fun easierFor(name: String): com.wildodds.gymtracker.data.intelligence.ScoredExercise? =
  exerciseGraph().regress(name)

  /** Best harder variation for an exercise (or null), for the one-tap "Make harder" control. */
  fun harderFor(name: String): com.wildodds.gymtracker.data.intelligence.ScoredExercise? =
  exerciseGraph().progress(name)

  private fun exerciseGraph() =
  com.wildodds.gymtracker.data.intelligence.ExerciseGraph.engine(getApplication())

  // ── Per-exercise progression choice ───────────────────────────────────────

  fun setExerciseProgressionChoice(exerciseIndex: Int, choice: String) {
  val exercises = _state.value.exercises.toMutableList()
  val ex = exercises.getOrNull(exerciseIndex) ?: return
  // Toggle off if already selected.
  val newChoice = if (ex.currentProgressionChoice == choice) null else choice
  exercises[exerciseIndex] = ex.copy(currentProgressionChoice = newChoice)
  _state.value = _state.value.copy(exercises = exercises)
  // Persist EVERY change (including clearing) to THIS week's workout log, so the choice is
  // freely settable and re-editable on every week — not locked once a value exists.
  viewModelScope.launch(Dispatchers.IO) {
  repo.updateProgressionChoice(ex.workoutLogId, newChoice)
  }
  }

  // ── Session completion ─────────────────────────────────────────────────────

  /**
   * Complete the session: flush saves, capture duration + (optional) HR, persist the completion,
   * and either show the summary sheet ([showSummary]) or finish immediately. When [showSummary]
   * is true the strain rating is collected later via [finishSummary].
   */
  fun completeSession(showSummary: Boolean, onFinished: () -> Unit) {
  viewModelScope.launch {
  // Flush all pending debounced set saves
  debounceJobs.values.forEach { it.cancel() }
  _state.value.exercises.forEachIndexed { ei, ex ->
  ex.sets.indices.forEach { si -> saveSet(ei, si) }
  }

  val end = System.currentTimeMillis()
  val durationSeconds = computeDurationSeconds(sessionStartMillis, end)
  // Heart rate (Phase 4). Read only when a feature that uses it is on — summary HR or fatigue —
  // so we never touch health data needlessly. The provider itself returns null when Health
  // Connect is unavailable or permission isn't granted, so this stays null in the common case.
  val hr: HrResult? = withContext(Dispatchers.IO) {
  if (!(summaryHrEnabled || fatigueEnabled)) null
  else sessionStartMillis?.let { start ->
  wearable.heartRateForSession(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
  }
  }
  // Final fatigue score now incorporates HR (Phase 3F + 4): no HR → reps + RPE only, as before.
  // Fatigue may use HR even when the summary-HR toggle is off; but HR is only *stored/shown*
  // (below) when the user has opted into seeing it — privacy-respecting by the user's choice.
  recomputeFatigue(hr?.avgBpm)
  val summaryHr = if (summaryHrEnabled) hr else null

  repo.completeSession(
  sessionId, weekNumber, durationSeconds,
  avgHeartRate = summaryHr?.avgBpm, peakHeartRate = summaryHr?.peakBpm,
  hrSeries = encodeHrSeries(summaryHr?.series ?: emptyList()),
  fatigueScore = _fatigue.value?.score
  )
  _state.value = _state.value.copy(isCompleted = true)
  _hasLoggedThisVisit.value = false  // completed → leaving is no longer guarded

  // Phase 5B — write the workout back to Health Connect (opt-in). Best-effort: the provider
  // returns false when unavailable or write permission isn't granted; we never block on it.
  if (writeToHealthConnect) {
  val title = _state.value.session?.name ?: "Strength training"
  withContext(Dispatchers.IO) {
  sessionStartMillis?.let { start ->
  runCatching {
  wearable.writeWorkoutSession(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), title)
  }
  }
  }
  }

  // Evaluate achievements now that this session's completion is persisted, so anything that just
  // crossed its target can be announced on the summary (idempotent — Home re-evaluation awards nothing twice).
  val unlockedTitles: List<String> = run {
  val achievementsEnabled = themePrefs.flag(SettingsRegistry.ACHIEVEMENTS, true).first()
  if (!achievementsEnabled) emptyList()
  else withContext(Dispatchers.IO) {
  val usedTravel = getApplication<Application>()
  .getSharedPreferences("gym_prefs", Context.MODE_PRIVATE)
  .getBoolean("used_travel_mode", false)
  repo.evaluateAndUnlockAchievements(usedTravel, System.currentTimeMillis()).map { it.definition.title }
  }
  }

  if (showSummary) {
  val logs = withContext(Dispatchers.IO) {
  repo.getSessionSetLogs(sessionId, weekNumber).values.flatten()
  }
  val totalSets = logs.count { it.weightKg != null && it.reps != null }
  val volume = logs.sumOf { ((it.weightKg ?: 0f) * (it.reps ?: 0)).toDouble() }.toFloat()
  // Heaviest single set this visit, for the real-life weight comparison.
  var bestName: String? = null; var bestWeight = 0f; var bestReps = 0
  _state.value.exercises.forEach { ex ->
  ex.sets.forEach { s ->
  val w = s.weightKg.toFloatOrNull(); val r = s.reps.toIntOrNull()
  if (w != null && r != null && w > bestWeight) { bestWeight = w; bestReps = r; bestName = ex.exercise.name }
  }
  }
  _summary.value = SessionSummaryData(
  durationSeconds = durationSeconds, totalSets = totalSets, totalVolumeKg = volume,
  avgHeartRate = summaryHr?.avgBpm, peakHeartRate = summaryHr?.peakBpm,
  hrSeries = summaryHr?.series ?: emptyList(),
  heaviestLiftName = bestName,
  heaviestLiftWeightKg = if (bestName != null) bestWeight else null,
  heaviestLiftReps = if (bestName != null) bestReps else null,
  unlockedAchievements = unlockedTitles
  )
  // Navigation happens from finishSummary().
  } else {
  delay(800)
  withContext(Dispatchers.Main) { onFinished() }
  }
  }
  }

  /** Persist the chosen strain rating (if any), dismiss the summary sheet, and finish. */
  fun finishSummary(strainRating: Int?, onDone: () -> Unit) {
  viewModelScope.launch {
  if (strainRating != null) {
  withContext(Dispatchers.IO) { repo.setSessionStrain(sessionId, weekNumber, strainRating) }
  _summary.value = _summary.value?.copy(strainRating = strainRating)
  }
  _summary.value = null
  withContext(Dispatchers.Main) { onDone() }
  }
  }

  // Always allow completing - the user decides when they're done with a session
  val canComplete: Boolean get() = true

  /**
  * Reset this day: cancel pending saves, wipe this week's logged sets + completion,
  * then reload so the card shows the carried-over (prev-week) prefill again, unmarked.
  */
  fun resetDay(onDone: () -> Unit = {}) {
  viewModelScope.launch {
  debounceJobs.values.forEach { it.cancel() }
  debounceJobs.clear()
  withContext(Dispatchers.IO) { repo.resetSessionDay(sessionId, weekNumber) }
  _hasLoggedThisVisit.value = false  // wiped back to carry-forward → nothing in-progress
  sessionStartMillis = null          // active-session clock restarts on next set logged
  loadSession()
  withContext(Dispatchers.Main) { onDone() }
  }
  }

  // ── Timer ─────────────────────────────────────────────────────────────────

  enum class TimerMode { REST, STOPWATCH }

  @Immutable
  data class TimerState(
  val isVisible: Boolean = false,
  val mode: TimerMode = TimerMode.REST,
  val restDuration: Int = 90,   // seconds for REST countdown
  val currentSeconds: Int = 0,  // REST = remaining, STOPWATCH = elapsed
  val isRunning: Boolean = false,
  // Fullscreen overlay for across-the-gym visibility.
  val isFullscreen: Boolean = false,
  // Delayed start: counts -10, -9 … -1, then the timer proper begins. 0 = no delay pending.
  val delayRemaining: Int = 0
  )

  private val _timerState = MutableStateFlow(TimerState())
  val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

  private val _timerFinishedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val timerFinishedEvent: SharedFlow<Unit> = _timerFinishedEvent.asSharedFlow()

  private var timerJob: Job? = null

  fun toggleTimerVisible() {
  _timerState.value = _timerState.value.copy(isVisible = !_timerState.value.isVisible)
  if (!_timerState.value.isVisible) pauseTimer()
  }

  fun toggleTimerFullscreen() {
  _timerState.value = _timerState.value.copy(isFullscreen = !_timerState.value.isFullscreen)
  }

  /**
   * Start after a lead-in: the display counts -[seconds] … -1 (time to rack in / get set),
   * then the timer proper starts. Tapping pause/reset during the lead-in cancels it.
   */
  fun startTimerWithDelay(seconds: Int = 10) {
  val s = _timerState.value
  if (s.isRunning || s.delayRemaining > 0) return
  timerJob?.cancel()
  _timerState.value = s.copy(delayRemaining = seconds)
  timerJob = viewModelScope.launch {
  while (true) {
  delay(1000L)
  val current = _timerState.value
  if (current.delayRemaining <= 0) break
  val next = current.delayRemaining - 1
  _timerState.value = current.copy(delayRemaining = next)
  if (next <= 0) { startTimer(); break }
  }
  }
  }

  fun setTimerMode(mode: TimerMode) {
  timerJob?.cancel()
  val duration = _timerState.value.restDuration
  _timerState.value = _timerState.value.copy(
  mode = mode,
  currentSeconds = if (mode == TimerMode.REST) duration else 0,
  isRunning = false
  )
  }

  fun setRestDuration(seconds: Int) {
  val clamped = seconds.coerceIn(5, 600)
  _timerState.value = _timerState.value.copy(
  restDuration = clamped,
  currentSeconds = if (_timerState.value.mode == TimerMode.REST && !_timerState.value.isRunning) clamped
  else _timerState.value.currentSeconds
  )
  }

  fun startTimer() {
  val s = _timerState.value
  if (s.isRunning) return
  // If REST and already finished (0), reset first
  val startFrom = if (s.mode == TimerMode.REST && s.currentSeconds <= 0) s.restDuration
  else s.currentSeconds
  _timerState.value = s.copy(currentSeconds = startFrom, isRunning = true)
  timerJob?.cancel()
  timerJob = viewModelScope.launch {
  while (true) {
  delay(1000L)
  val current = _timerState.value
  if (!current.isRunning) break
  when (current.mode) {
  TimerMode.REST -> {
  val next = current.currentSeconds - 1
  _timerState.value = current.copy(currentSeconds = next.coerceAtLeast(0))
  if (next <= 0) {
  _timerState.value = _timerState.value.copy(isRunning = false)
  _timerFinishedEvent.tryEmit(Unit)
  break
  }
  }
  TimerMode.STOPWATCH -> {
  _timerState.value = current.copy(currentSeconds = current.currentSeconds + 1)
  }
  }
  }
  }
  }

  fun pauseTimer() {
  timerJob?.cancel()
  _timerState.value = _timerState.value.copy(isRunning = false, delayRemaining = 0)
  }

  fun resetTimer() {
  timerJob?.cancel()
  val s = _timerState.value
  _timerState.value = s.copy(
  currentSeconds = if (s.mode == TimerMode.REST) s.restDuration else 0,
  isRunning = false,
  delayRemaining = 0
  )
  }

  /** Called internally when a set transitions from incomplete → complete. */
  private fun onSetCompleted() {
  val s = _timerState.value
  if (s.isVisible && s.mode == TimerMode.REST) {
  timerJob?.cancel()
  _timerState.value = s.copy(currentSeconds = s.restDuration, isRunning = true)
  timerJob = viewModelScope.launch {
  while (true) {
  delay(1000L)
  val current = _timerState.value
  if (!current.isRunning) break
  val next = current.currentSeconds - 1
  _timerState.value = current.copy(currentSeconds = next.coerceAtLeast(0))
  if (next <= 0) {
  _timerState.value = _timerState.value.copy(isRunning = false)
  _timerFinishedEvent.tryEmit(Unit)
  break
  }
  }
  }
  }
  }

  // ── Internals ─────────────────────────────────────────────────────────────

  private fun updateSets(exerciseIndex: Int, transform: (List<SetRowState>) -> List<SetRowState>) {
  val exercises = _state.value.exercises.toMutableList()
  val ex = exercises[exerciseIndex]
  exercises[exerciseIndex] = ex.copy(sets = transform(ex.sets))
  _state.value = _state.value.copy(exercises = exercises)
  }

  // ── Smart-progression helpers (Phase 3B) ────────────────────────────────────

  private fun buildHistory(logs: List<SetLogWithWeek>, maxWeekInclusive: Int): List<WeekRecord> =
  logs.filter { it.weekNumber <= maxWeekInclusive }
  .groupBy { it.weekNumber }
  .toSortedMap()
  .map { (wk, rows) -> WeekRecord(wk, rows.sortedBy { it.setNumber }.map { SetRecord(it.weightKg, it.reps) }) }

  /** "8-12" → (8,12); "8" → (8,8); "AMRAP" → (null,null). */
  private fun parseRepRange(s: String): Pair<Int?, Int?> {
  val nums = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
  return when {
  nums.isEmpty() -> null to null
  nums.size == 1 -> nums[0] to nums[0]
  else -> nums.min() to nums.max()
  }
  }

  // (%1RM prefill parsing lives in data/profile/PctPrefill — pure and unit-tested, and shared
  // with the Library's start-gating which asks for missing 1RMs.)

  private fun parseRpeTarget(s: String): Float? =
  Regex("\\d+(\\.\\d+)?").find(s)?.value?.toFloatOrNull()

  private fun scheduleSave(exerciseIndex: Int, setIndex: Int) {
  val key = "$exerciseIndex-$setIndex"
  debounceJobs[key]?.cancel()
  debounceJobs[key] = viewModelScope.launch {
  delay(500)
  saveSet(exerciseIndex, setIndex)
  }
  }

  private suspend fun saveSet(exerciseIndex: Int, setIndex: Int) {
  val exercise = _state.value.exercises.getOrNull(exerciseIndex) ?: return
  val set  = exercise.sets.getOrNull(setIndex) ?: return
  val log = set.toSetLog(exercise.workoutLogId) ?: return
  repo.upsertSetLog(log)
  recomputeFatigue()
  }

}

/** Normalised exercise name — keys per-exercise preferences (autofill toggle, athlete notes). */
internal fun exerciseKey(exerciseName: String): String = exerciseName.trim().lowercase()

/** The row as a stored log, or null when nothing has been entered in it. */
internal fun SetRowState.toSetLog(workoutLogId: Long): SetLog? {
  val w  = weightKg.toFloatOrNull()
  val r  = reps.toIntOrNull()
  val wRight = weightRight.toFloatOrNull()
  val rRight = repsRight.toIntOrNull()
  if (w == null && r == null && wRight == null && rRight == null) return null
  return SetLog(
  workoutLogId = workoutLogId,
  setNumber  = setNumber,
  weightKg  = w,
  reps  = r,
  rpe  = rpe.toFloatOrNull(),
  pct1rm  = pct1rm.toFloatOrNull(),
  weightRightKg = wRight,
  repsRight  = rRight
  )
}

/**
 * Pure weight-autofill: given the current set rows and the value just typed into set 1
 * ([firstWeight]), returns the new rows with set 1 set and the value propagated forward.
 *
 * Precedence (manual > carry-forward): a later set is updated only when it is blank, was
 * previously auto-filled, or still holds an untouched carried-forward prefill — and a fresh
 * non-blank entry overwrites that prefill. A set the user manually edited is never clobbered.
 * Clearing set 1 (blank) clears blank/auto-filled followers but leaves carry-forward prefills.
 */
/**
 * Fill a percentage-prescribed exercise from one typed number.
 *
 * [typed] is whatever the user entered in the row they touched; it is interpreted as that row's
 * prescribed percentage, so the implied top-set weight is `typed / percent * 100`. Every other
 * percentage row is then resolved from that top set. A row the user typed by hand is never
 * overwritten — the ramp is a suggestion, not a cage.
 */
internal fun autofillFromRamp(
  sets: List<SetRowState>,
  typed: String,
  anchorIndex: Int = 0
): List<SetRowState> {
  val result = sets.toMutableList()
  result[anchorIndex] = result[anchorIndex].copy(
  weightKg = typed, isPrefilled = false, isWeightAutoFilled = false)

  val anchorPercent = sets.getOrNull(anchorIndex)?.plannedPercent ?: 100
  val typedValue = typed.toFloatOrNull()
  // Blank the derived rows when the anchor is cleared, rather than leaving stale numbers behind.
  val topLoad = if (typedValue == null || typedValue <= 0f || anchorPercent <= 0) null
  else typedValue / anchorPercent * 100f

  for (i in result.indices) {
  if (i == anchorIndex) continue
  val s = result[i]
  val isManual = !s.isWeightAutoFilled && !s.isPrefilled && s.weightKg.isNotBlank()
  if (isManual) continue
  val percent = s.plannedPercent ?: continue
  val resolved = com.wildodds.gymtracker.data.builder.SetShape.resolveLoad(topLoad, percent)
  result[i] = s.copy(
  weightKg = resolved?.let { w -> if (w % 1f == 0f) w.toInt().toString() else w.toString() } ?: "",
  isWeightAutoFilled = resolved != null,
  isPrefilled = false
  )
  }
  return result
}

internal fun autofillWeights(sets: List<SetRowState>, firstWeight: String): List<SetRowState> {
  if (sets.isEmpty()) return sets

  // EVOLVING SETS: when the exercise was prescribed as a ramp, the sets are not all the same
  // weight, so copying set 1 everywhere would be wrong. Instead, treat the typed number as the
  // reference the percentages hang off — type your working weight once and the warm-ups, back-offs
  // and drops all fill themselves in at the right proportion.
  if (sets.any { it.plannedPercent != null }) return autofillFromRamp(sets, firstWeight)

  val result = sets.toMutableList()
  result[0] = result[0].copy(weightKg = firstWeight, isPrefilled = false, isWeightAutoFilled = false)
  for (i in 1 until result.size) {
  val s = result[i]
  val isManual = !s.isWeightAutoFilled && !s.isPrefilled && s.weightKg.isNotBlank()
  when {
  isManual -> { /* user typed this set — never overwrite */ }
  s.isPrefilled ->
  if (firstWeight.isNotBlank())
  result[i] = s.copy(weightKg = firstWeight, isWeightAutoFilled = true, isPrefilled = false)
  s.weightKg.isBlank() || s.isWeightAutoFilled ->
  result[i] = s.copy(weightKg = firstWeight, isWeightAutoFilled = firstWeight.isNotBlank(), isPrefilled = false)
  }
  }
  return result
}

/**
 * Whether pressing back should pop up the leave-session confirmation: only when the guard is
 * enabled, the user has logged something this visit, and the session isn't already complete.
 */
internal fun shouldGuardExit(guardEnabled: Boolean, hasLoggedThisVisit: Boolean, isCompleted: Boolean): Boolean =
  guardEnabled && hasLoggedThisVisit && !isCompleted

/**
 * Active-session duration in whole seconds, or null if no start was captured (nothing logged) or
 * the clock went backwards. [startMillis] is when the first set was logged; [endMillis] is now.
 */
internal fun computeDurationSeconds(startMillis: Long?, endMillis: Long): Long? {
  if (startMillis == null) return null
  val deltaMs = endMillis - startMillis
  if (deltaMs < 0) return null
  return deltaMs / 1000
}

/** Human-readable duration, e.g. 3725 → "1h 2m", 125 → "2m 5s", 40 → "40s", null → "—". */
internal fun formatDuration(seconds: Long?): String {
  if (seconds == null) return "—"
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return when {
  h > 0 -> "${h}h ${m}m"
  m > 0 -> "${m}m ${s}s"
  else -> "${s}s"
  }
}
