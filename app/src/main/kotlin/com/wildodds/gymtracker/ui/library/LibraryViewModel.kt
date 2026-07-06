package com.wildodds.gymtracker.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.parser.DefaultProgramCatalogue
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Analysis models ───────────────────────────────────────────────────────────

data class MuscleAnalysisRow(
  val muscle: MuscleGroup,
  val setsPerWeek: Float
)

data class AnalysisResult(
  val programName: String,
  val rows: List<MuscleAnalysisRow>,
  val unknownExercises: List<String>,
  val dashboard: ProgramDashboard
)

// ── Dashboard models ──────────────────────────────────────────────────────────

data class ProgramDashboard(
  val topMuscle: MuscleGroup?,
  val underworkedMuscles: List<MuscleGroup>,
  val overallBalance: String,  // e.g. "Push-heavy", "Well-balanced"
  val weeklySetTotal: Int,  // total sets across all muscles per week
  val pushPullRatio: String,  // e.g. "1.2 : 1"
  val weakestMuscle: MuscleGroup?,  // lowest sets relative to MEV
  val insights: List<String>  // readable insight strings
)

// ── Progression models ────────────────────────────────────────────────────────

enum class Trend { IMPROVING, FLAT, DECLINING }

data class WeeklySnapshot(
  val week: Int,
  val maxWeightKg: Float,
  val totalVolume: Float,  // sum of weight*reps
  val avgReps: Float,
  val sets: Int
)

data class ExerciseProgression(
  val exerciseName: String,
  val dayNumber: Int,
  val orderIndex: Int,
  val snapshots: List<WeeklySnapshot>,  // one per week with data
  val trend: Trend,
  val weightChangeKg: Float,  // latest - first (positive = gaining)
  val volumeChangePercent: Float,  // % change from first to last week
  val volumeCorrelation: String?  // human-readable correlation insight
)

data class ProgressionResult(
  val programName: String,
  val exercises: List<ExerciseProgression>,
  val bestGain: ExerciseProgression?,  // biggest positive weight change
  val biggestDrop: ExerciseProgression? // biggest decline (if any)
)

// ── View/Edit program models ──────────────────────────────────────────────────

data class ExerciseDetail(
  val id: Long,
  val name: String,
  val sets: Int,
  val repsTarget: String,
  val orderIndex: Int
)

data class DayDetail(
  val dayNumber: Int,
  val sessionName: String,
  val exercises: List<ExerciseDetail>
)

data class WeekDetail(
  val weekNumber: Int,
  val days: List<DayDetail>
)

data class ProgramDetail(
  val program: com.wildodds.gymtracker.data.db.entity.Program,
  val weeks: List<WeekDetail>
)

// ── MEV reference ─────────────────────────────────────────────────────────────

private val MEV: Map<MuscleGroup, Int> = mapOf(
  MuscleGroup.CHEST  to 10, MuscleGroup.FRONT_DELT  to 8,  MuscleGroup.SIDE_DELT  to 12,
  MuscleGroup.REAR_DELT  to 10, MuscleGroup.LATS  to 10, MuscleGroup.UPPER_BACK to 10,
  MuscleGroup.LOWER_BACK to 6,  MuscleGroup.TRAPS  to 10, MuscleGroup.BICEPS  to 10,
  MuscleGroup.TRICEPS  to 10, MuscleGroup.QUADS  to 10, MuscleGroup.HAMSTRINGS  to 10,
  MuscleGroup.GLUTES  to 8,  MuscleGroup.CALVES  to 12, MuscleGroup.ABS  to 10
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = GymRepository(AppDatabase.getInstance(app))

  val programs: StateFlow<List<Program>> = repo.allPrograms()
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val pausedPrograms: StateFlow<List<Program>> = repo.pausedPrograms()
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _defaultPrograms = MutableStateFlow<List<ParsedProgram>>(emptyList())
  val defaultPrograms: StateFlow<List<ParsedProgram>> = _defaultPrograms.asStateFlow()

  init {
  viewModelScope.launch(Dispatchers.IO) {
  _defaultPrograms.value = DefaultProgramCatalogue.load(getApplication())
  }
  }

  fun deleteProgram(program: Program) {
  viewModelScope.launch(Dispatchers.IO) { repo.deleteProgram(program.id) }
  }

  fun addDefaultToLibrary(program: ParsedProgram) {
  viewModelScope.launch(Dispatchers.IO) {
  repo.importProgram(program.copy(isUserCreated = true), activate = false)
  }
  }

  fun setDefaultAsActive(program: ParsedProgram) {
  viewModelScope.launch(Dispatchers.IO) {
  repo.importProgram(program.copy(isUserCreated = true), activate = true)
  }
  }

  private val db = AppDatabase.getInstance(app)

  // Dialog state: non-null = pending start; contains the program to start + current program name
  data class PauseConfirmState(val programToStart: Program, val currentProgramName: String)
  private val _pauseConfirm = MutableStateFlow<PauseConfirmState?>(null)
  val pauseConfirm: StateFlow<PauseConfirmState?> = _pauseConfirm.asStateFlow()

  fun dismissPauseConfirm() { _pauseConfirm.value = null }

  fun startProgram(program: Program) {
  viewModelScope.launch {
  val active = withContext(Dispatchers.IO) { db.programDao().getCurrentProgramOnce() }
  if (active != null && active.id != program.id) {
  _pauseConfirm.value = PauseConfirmState(program, active.name)
  } else {
  doStartProgram(program.id)
  }
  }
  }

  fun confirmStartProgram() {
  val state = _pauseConfirm.value ?: return
  _pauseConfirm.value = null
  viewModelScope.launch { doStartProgram(state.programToStart.id) }
  }

  private suspend fun doStartProgram(programId: Long) {
  withContext(Dispatchers.IO) { repo.startProgramFromLibrary(programId) }
  }

  fun resumeProgram(program: Program) {
  viewModelScope.launch {
  withContext(Dispatchers.IO) { repo.resumePausedProgram(program.id) }
  }
  }

  fun restartPausedProgram(program: Program) {
  viewModelScope.launch {
  withContext(Dispatchers.IO) { repo.restartPausedProgram(program.id) }
  }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val programDays: StateFlow<Map<Long, Int>> = programs
  .transformLatest { progs ->
  val map = progs.associate { prog ->
  val sessions = db.sessionDao().getSessionsForWeek(1, prog.id)
  prog.id to sessions.map { it.dayNumber }.distinct().size
  }
  emit(map)
  }
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  // Analysis
  private val _analysis  = MutableStateFlow<AnalysisResult?>(null)
  val analysis: StateFlow<AnalysisResult?> = _analysis.asStateFlow()

  private val _analysing = MutableStateFlow(false)
  val analysing: StateFlow<Boolean> = _analysing.asStateFlow()

  // Progression
  private val _progression  = MutableStateFlow<ProgressionResult?>(null)
  val progression: StateFlow<ProgressionResult?> = _progression.asStateFlow()

  private val _progressing  = MutableStateFlow(false)
  val progressing: StateFlow<Boolean> = _progressing.asStateFlow()

  // ── Analyse ───────────────────────────────────────────────────────────────

  fun analyseProgram(program: Program) {
  viewModelScope.launch {
  _analysing.value = true
  _analysis.value  = null
  withContext(Dispatchers.IO) {
  val db  = AppDatabase.getInstance(getApplication())
  val w1  = db.sessionDao().getSessionsForWeek(1, program.id)
  val muscles  = mutableMapOf<MuscleGroup, Float>()
  val unknowns = mutableSetOf<String>()

  for (session in w1) {
  for (ex in db.exerciseDao().getExercisesForSession(session.id)) {
  val info = ExerciseLibrary.lookup(ex.name)
  if (info == null) { unknowns.add(ex.name); continue }
  val sets = ex.sets.toFloat()
  info.primary.forEach  { m -> muscles[m] = (muscles[m] ?: 0f) + sets }
  info.secondary.forEach { m -> muscles[m] = (muscles[m] ?: 0f) + sets * 0.5f }
  }
  }

  val rows = muscles.map { (m, s) -> MuscleAnalysisRow(m, s) }
  .sortedByDescending { it.setsPerWeek }

  _analysis.value = AnalysisResult(
  programName  = program.name,
  rows  = rows,
  unknownExercises = unknowns.sorted(),
  dashboard  = buildDashboard(muscles)
  )
  }
  _analysing.value = false
  }
  }

  private fun buildDashboard(muscles: Map<MuscleGroup, Float>): ProgramDashboard {
  val pushMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.FRONT_DELT, MuscleGroup.SIDE_DELT, MuscleGroup.TRICEPS)
  val pullMuscles = setOf(MuscleGroup.LATS, MuscleGroup.UPPER_BACK, MuscleGroup.REAR_DELT, MuscleGroup.BICEPS, MuscleGroup.TRAPS)

  val pushSets = muscles.filterKeys { it in pushMuscles }.values.sum()
  val pullSets = muscles.filterKeys { it in pullMuscles }.values.sum()
  val ratio  = if (pullSets > 0) pushSets / pullSets else 0f
  val ratioStr = "%.1f : 1".format(ratio)
  val balance  = when {
  ratio > 1.3f -> "Push-heavy"
  ratio < 0.7f -> "Pull-heavy"
  else  -> "Well-balanced"
  }

  val underworked = MEV.entries
  .filter { (m, min) -> (muscles[m] ?: 0f) < min * 0.5f }
  .map { it.key }

  val topMuscle = muscles.maxByOrNull { it.value }?.key

  val weakest = MEV.entries
  .filter { muscles.containsKey(it.key) }
  .minByOrNull { (m, min) -> (muscles[m] ?: 0f) / min }
  ?.key

  val totalSets = muscles.values.sum().roundToInt()

  val insights = mutableListOf<String>()
  if (underworked.isNotEmpty()) {
  insights.add("Consider adding volume for: ${underworked.joinToString(", ") { it.display }}")
  }
  if (ratio > 1.3f) insights.add("Push:pull ratio is $ratioStr - add more pulling movements")
  if (ratio < 0.7f) insights.add("Push:pull ratio is $ratioStr - add more pressing movements")
  topMuscle?.let { insights.add("Most trained: ${it.display}") }
  if (muscles.containsKey(MuscleGroup.CALVES) == false) {
  insights.add("No direct calf work detected")
  }

  return ProgramDashboard(
  topMuscle  = topMuscle,
  underworkedMuscles = underworked,
  overallBalance  = balance,
  weeklySetTotal  = totalSets,
  pushPullRatio  = ratioStr,
  weakestMuscle  = weakest,
  insights  = insights
  )
  }

  fun clearAnalysis() { _analysis.value = null }

  // ── Progression ───────────────────────────────────────────────────────────

  fun loadProgression(program: Program) {
  viewModelScope.launch {
  _progressing.value = true
  _progression.value = null
  withContext(Dispatchers.IO) {
  val db  = AppDatabase.getInstance(getApplication())
  val w1  = db.sessionDao().getSessionsForWeek(1, program.id)
  val results  = mutableListOf<ExerciseProgression>()

  for (session in w1) {
  for (ex in db.exerciseDao().getExercisesForSession(session.id)) {
  val logs = db.setLogDao().getAllWeekSetLogs(
  programId  = program.id,
  dayNumber  = session.dayNumber,
  orderIndex = ex.orderIndex
  ).filter { it.weightKg != null && it.reps != null }

  if (logs.isEmpty()) continue

  // Group by week
  val byWeek = logs.groupBy { it.weekNumber }
  val snapshots = byWeek.entries.sortedBy { it.key }.map { (week, sets) ->
  val maxW  = sets.mapNotNull { it.weightKg }.maxOrNull() ?: 0f
  val totVol = sets.sumOf { ((it.weightKg ?: 0f) * (it.reps ?: 0)).toDouble() }.toFloat()
  val avgR  = sets.mapNotNull { it.reps?.toFloat() }.average().toFloat()
  WeeklySnapshot(week, maxW, totVol, avgR, sets.size)
  }

  if (snapshots.size < 2) continue

  val firstW = snapshots.first().maxWeightKg
  val lastW  = snapshots.last().maxWeightKg
  val wDelta = lastW - firstW

  val firstVol = snapshots.first().totalVolume
  val lastVol  = snapshots.last().totalVolume
  val volPct  = if (firstVol > 0) (lastVol - firstVol) / firstVol * 100f else 0f

  val trend = when {
  wDelta > 2.5f  -> Trend.IMPROVING
  wDelta < -2.5f -> Trend.DECLINING
  else  -> Trend.FLAT
  }

  // Correlation: when volume went up did weight go up the following week?
  val correlation = computeVolumeCorrelation(snapshots)

  results.add(ExerciseProgression(
  exerciseName  = ex.name,
  dayNumber  = session.dayNumber,
  orderIndex  = ex.orderIndex,
  snapshots  = snapshots,
  trend  = trend,
  weightChangeKg  = wDelta,
  volumeChangePercent = volPct,
  volumeCorrelation  = correlation
  ))
  }
  }

  val sorted  = results.sortedByDescending { abs(it.weightChangeKg) }
  val bestGain  = results.filter { it.weightChangeKg > 0 }.maxByOrNull { it.weightChangeKg }
  val biggestDrop = results.filter { it.weightChangeKg < 0 }.minByOrNull { it.weightChangeKg }

  _progression.value = ProgressionResult(
  programName  = program.name,
  exercises  = sorted,
  bestGain  = bestGain,
  biggestDrop  = biggestDrop
  )
  }
  _progressing.value = false
  }
  }

  private fun computeVolumeCorrelation(snapshots: List<WeeklySnapshot>): String? {
  if (snapshots.size < 3) return null
  var volUpCount  = 0
  var volUpGainCount = 0
  for (i in 0 until snapshots.size - 1) {
  val curr = snapshots[i]
  val next = snapshots[i + 1]
  if (next.totalVolume > curr.totalVolume * 1.05f) {
  volUpCount++
  if (next.maxWeightKg >= curr.maxWeightKg) volUpGainCount++
  }
  }
  if (volUpCount == 0) return null
  val pct = (volUpGainCount * 100f / volUpCount).roundToInt()
  return "After higher-volume weeks, strength held or improved $pct% of the time"
  }

  fun clearProgression() { _progression.value = null }

  // ── Trends (Phase 3G) — aggregated on IO in the repository ──────────────────

  private val _trends = MutableStateFlow<com.wildodds.gymtracker.data.analytics.TrendData?>(null)
  val trends: StateFlow<com.wildodds.gymtracker.data.analytics.TrendData?> = _trends.asStateFlow()
  private val _trendsLoading = MutableStateFlow(false)
  val trendsLoading: StateFlow<Boolean> = _trendsLoading.asStateFlow()
  private val _trendsRangeDays = MutableStateFlow(0)   // 0 = all time
  val trendsRangeDays: StateFlow<Int> = _trendsRangeDays.asStateFlow()
  private var trendsProgram: Program? = null

  fun loadTrends(program: Program, rangeDays: Int = _trendsRangeDays.value) {
  trendsProgram = program
  _trendsRangeDays.value = rangeDays
  viewModelScope.launch {
  _trendsLoading.value = true
  _trends.value = null
  val range = if (rangeDays <= 0) null else {
  val now = System.currentTimeMillis()
  com.wildodds.gymtracker.data.analytics.DateRange(now - rangeDays * 86_400_000L, now)
  }
  _trends.value = repo.analyseTrends(program.id, range)  // aggregation runs on IO inside the repo
  _trendsLoading.value = false
  }
  }

  /** Re-load the current trends program with a new date range. */
  fun setTrendsRange(days: Int) { trendsProgram?.let { loadTrends(it, days) } }

  fun clearTrends() { _trends.value = null; trendsProgram = null }

  // ── On-demand library (Phase 3H) ────────────────────────────────────────────

  private val _onDemand = MutableStateFlow<List<com.wildodds.gymtracker.data.ondemand.OnDemandSession>>(emptyList())
  val onDemand: StateFlow<List<com.wildodds.gymtracker.data.ondemand.OnDemandSession>> = _onDemand.asStateFlow()
  private val _onDemandLoading = MutableStateFlow(false)
  val onDemandLoading: StateFlow<Boolean> = _onDemandLoading.asStateFlow()
  private val _onDemandFilter = MutableStateFlow(com.wildodds.gymtracker.data.ondemand.OnDemandFilter())
  val onDemandFilter: StateFlow<com.wildodds.gymtracker.data.ondemand.OnDemandFilter> = _onDemandFilter.asStateFlow()

  fun setOnDemandFilter(f: com.wildodds.gymtracker.data.ondemand.OnDemandFilter) { _onDemandFilter.value = f }

  fun loadOnDemand() {
  viewModelScope.launch {
  _onDemandLoading.value = true
  _onDemand.value = withContext(Dispatchers.IO) { buildOnDemand() }
  _onDemandLoading.value = false
  }
  }

  private suspend fun buildOnDemand(): List<com.wildodds.gymtracker.data.ondemand.OnDemandSession> {
  val active = db.programDao().getCurrentProgramOnce()
  val volume = if (active != null) repo.weeklySetsByMuscle(active.id) else emptyMap()
  val deficits = com.wildodds.gymtracker.data.ondemand.OnDemandRecommender.deficits(volume)
  val underWorked = deficits.entries.filter { it.value > 0.3f }.sortedByDescending { it.value }.map { it.key }

  val templates = buildTemplateSessions()
  val generated = com.wildodds.gymtracker.data.ondemand.GapSessionGenerator.generate(underWorked.take(3))
  val catalogue = buildCatalogueSessions()
  return com.wildodds.gymtracker.data.ondemand.OnDemandRecommender.rank(templates + generated + catalogue, volume)
  }

  private suspend fun buildTemplateSessions(): List<com.wildodds.gymtracker.data.ondemand.OnDemandSession> {
  val templates = repo.librarySessions().first()
  return templates.map { t ->
  val exs = repo.getLibrarySessionExercisesOnce(t.id)
  toOnDemand("tmpl_${t.id}", t.name, com.wildodds.gymtracker.data.ondemand.SessionSource.TEMPLATE,
  exs.map { Triple(it.name, it.sets, it.repsTarget) })
  }
  }

  private fun buildCatalogueSessions(): List<com.wildodds.gymtracker.data.ondemand.OnDemandSession> =
  _defaultPrograms.value.take(12).mapNotNull { prog ->
  val day = prog.sessions.firstOrNull { it.weekNumber == 1 } ?: return@mapNotNull null
  toOnDemand("cat_${prog.name}", "${day.name} · ${prog.name}",
  com.wildodds.gymtracker.data.ondemand.SessionSource.CATALOGUE,
  day.exercises.map { Triple(it.name, it.sets, it.repsTarget) })
  }

  private fun toOnDemand(
  id: String, title: String, source: com.wildodds.gymtracker.data.ondemand.SessionSource,
  rows: List<Triple<String, Int, String>>
  ): com.wildodds.gymtracker.data.ondemand.OnDemandSession {
  val engine = com.wildodds.gymtracker.data.intelligence.ExerciseGraph.engine(getApplication())
  val focus = rows.flatMap { ExerciseLibrary.lookup(it.first)?.primary ?: emptyList() }.toSet()
  val equipment = rows.flatMap { engine.node(it.first)?.equipment ?: emptySet() }.toSet()
  val duration = (rows.sumOf { it.second } * 2 + 5).coerceIn(10, 75)
  return com.wildodds.gymtracker.data.ondemand.OnDemandSession(
  id = id, title = title, source = source, muscleFocus = focus, durationMin = duration,
  equipment = equipment, exercises = rows.map { com.wildodds.gymtracker.data.ondemand.OnDemandExercise(it.first, it.second, it.third) })
  }

  /** Materialise the chosen on-demand session and hand back its sessionId to navigate to. */
  fun runOnDemand(session: com.wildodds.gymtracker.data.ondemand.OnDemandSession, onReady: (Long) -> Unit) {
  viewModelScope.launch {
  val sessionId = repo.startOnDemandSession(
  title = session.title,
  muscleGroups = session.muscleFocus.joinToString(", ") { it.display },
  exercises = session.exercises.map { Triple(it.name, it.sets, it.repsTarget) }
  )
  onReady(sessionId)
  }
  }

  // ── View / Edit program ───────────────────────────────────────────────────

  private val _programDetail = MutableStateFlow<ProgramDetail?>(null)
  val programDetail: StateFlow<ProgramDetail?> = _programDetail.asStateFlow()

  fun loadProgramForView(program: com.wildodds.gymtracker.data.db.entity.Program) {
    viewModelScope.launch(Dispatchers.IO) {
      val allSessions = db.sessionDao().getAllSessions(program.id).first()
      // Group by week then day
      val byWeek = allSessions.groupBy { it.weekNumber }.toSortedMap()
      val weeks = byWeek.map { (weekNum, sessions) ->
        val days = sessions.sortedBy { it.dayNumber }.map { session ->
          val exercises = db.exerciseDao().getExercisesForSession(session.id)
            .map { ex -> ExerciseDetail(ex.id, ex.name, ex.sets, ex.repsTarget ?: "", ex.orderIndex) }
          DayDetail(session.dayNumber, session.name ?: "Day ${session.dayNumber}", exercises)
        }
        WeekDetail(weekNum, days)
      }
      _programDetail.value = ProgramDetail(program, weeks)
    }
  }

  fun clearProgramDetail() { _programDetail.value = null }

  fun updateExerciseInDetail(
    exerciseId: Long, name: String, sets: Int, repsTarget: String,
    orderIndex: Int, dayNumber: Int, programId: Long
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      db.exerciseDao().updateName(exerciseId, name)
      db.exerciseDao().updateSets(exerciseId, sets)
      db.exerciseDao().updateRepsTarget(exerciseId, repsTarget)
      // Propagate name + sets to all weeks; reps to all weeks too
      db.exerciseDao().updateNameForFutureWeeks(name, orderIndex, dayNumber, programId, 0)
      db.exerciseDao().updateSetsForFutureWeeks(sets, orderIndex, dayNumber, programId, 0)
      db.exerciseDao().updateRepsTargetForAllWeeks(repsTarget, orderIndex, dayNumber, programId)
      // Refresh the detail
      val current = _programDetail.value ?: return@launch
      loadProgramForView(current.program)
    }
  }
}
