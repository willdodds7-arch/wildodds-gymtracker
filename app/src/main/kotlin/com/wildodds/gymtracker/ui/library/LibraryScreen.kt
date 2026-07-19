@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.home.AiProgramReviewSheet
import com.wildodds.gymtracker.ui.home.ExcelOptionsSheet
import com.wildodds.gymtracker.ui.home.HomeViewModel
import com.wildodds.gymtracker.ui.home.ImportState
import com.wildodds.gymtracker.ui.home.SpreadsheetPreviewSheet
import com.wildodds.gymtracker.ui.settings.FeatureFlags
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.tools.PlateCalculatorSheet

// ── Volume-status thresholds ──────────────────────────────────────────────────
// Optimal: 6-8+ sets/week (green)
// OK:  4-5 sets/week  (amber)
// Low:  < 4  (red)
private enum class VolumeStatus { OPTIMAL, OK, LOW }
private fun setsStatus(sets: Float) = when {
  sets >= 6f -> VolumeStatus.OPTIMAL
  sets >= 4f -> VolumeStatus.OK
  else  -> VolumeStatus.LOW
}

// ── Muscle groups ordered by body region for display ─────────────────────────
private val MUSCLE_ORDER: List<Pair<String, List<MuscleGroup>>> = listOf(
  "Chest"  to listOf(MuscleGroup.CHEST),
  "Shoulders" to listOf(MuscleGroup.FRONT_DELT, MuscleGroup.SIDE_DELT, MuscleGroup.REAR_DELT),
  "Back"  to listOf(MuscleGroup.LATS, MuscleGroup.UPPER_BACK, MuscleGroup.LOWER_BACK, MuscleGroup.TRAPS),
  "Arms"  to listOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS),
  "Legs"  to listOf(MuscleGroup.QUADS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES,
  MuscleGroup.CALVES, MuscleGroup.ADDUCTORS, MuscleGroup.ABDUCTORS, MuscleGroup.HIP_FLEXORS),
  "Core"  to listOf(MuscleGroup.ABS, MuscleGroup.OBLIQUES),
  "Other"  to listOf(MuscleGroup.NECK)
)

// ── Main screen ───────────────────────────────────────────────────────────────

@Composable
fun LibraryScreen(
  navController: androidx.navigation.NavController,
  vm: LibraryViewModel = viewModel(),
  homeVm: HomeViewModel = viewModel()
) {
  val programs    by vm.programs.collectAsStateWithLifecycle()
  val pausedPrograms  by vm.pausedPrograms.collectAsStateWithLifecycle()
  val programDays  by vm.programDays.collectAsStateWithLifecycle()
  val analysis    by vm.analysis.collectAsStateWithLifecycle()
  val analysing   by vm.analysing.collectAsStateWithLifecycle()
  val progression  by vm.progression.collectAsStateWithLifecycle()
  val progressing  by vm.progressing.collectAsStateWithLifecycle()
  val pauseConfirm  by vm.pauseConfirm.collectAsStateWithLifecycle()
  val programDetail  by vm.programDetail.collectAsStateWithLifecycle()
  val defaultPrograms by vm.defaultPrograms.collectAsStateWithLifecycle()
  val accent    = LocalAccentColor.current
  val analyticsEnabled = FeatureFlags.isEnabled(SettingsRegistry.ADVANCED_ANALYTICS)

  val snackbarHost  = remember { SnackbarHostState() }
  var showError   by remember { mutableStateOf(false) }
  var errorMessage  by remember { mutableStateOf("") }
  var query       by remember { mutableStateOf("") }
  var dayFilter     by remember { mutableStateOf<Int?>(null) }
  var showBrowseSheet  by remember { mutableStateOf(false) }

  if (showError) {
  AlertDialog(onDismissRequest = { showError = false },
  title = { Text("Import Error") }, text = { Text(errorMessage) },
  confirmButton = { TextButton(onClick = { showError = false }) { Text("OK") } })
  }
  programDetail?.let { detail ->
  ViewProgramSheet(
    detail = detail,
    onDismiss = { vm.clearProgramDetail() },
    onSaveExercise = { exId, name, sets, reps, orderIndex, dayNumber ->
    vm.updateExerciseInDetail(exId, name, sets, reps, orderIndex, dayNumber, detail.program.id)
    }
  )
  }
  // %1RM programs pass through the start gate (lift picker for swappable programs + a prompt
  // for any 1RMs the percentages need but the user hasn't set).
  var pendingStart by remember { mutableStateOf<PendingProgramStart?>(null) }
  ProgramStartGate(
  pending = pendingStart,
  onDismiss = { pendingStart = null },
  onProceed = { prog, activate ->
  pendingStart = null
  if (activate) vm.setDefaultAsActive(prog) else vm.addDefaultToLibrary(prog)
  }
  )

  if (showBrowseSheet) {
  BrowseDefaultsSheet(
  programs  = defaultPrograms,
  onAddToLibrary = { pendingStart = PendingProgramStart(it, activate = false); showBrowseSheet = false },
  onSetAsActive  = { pendingStart = PendingProgramStart(it, activate = true); showBrowseSheet = false },
  onDismiss  = { showBrowseSheet = false }
  )
  }

  // Program to show resume/restart dialog for
  var resumeTarget by remember { mutableStateOf<Program?>(null) }

  val activePrograms = programs.filter { !it.isPaused }
  val activeProgram  = activePrograms.firstOrNull { it.isActive }
  val libraryPrograms = activePrograms.filter { !it.isActive }
  val filtered = libraryPrograms.filter { prog ->
  val matchesQuery = query.isBlank() || prog.name.contains(query.trim(), ignoreCase = true)
  val days = programDays[prog.id] ?: 0
  val matchesDays = dayFilter == null || (if (dayFilter == 6) days >= 6 else days == dayFilter)
  matchesQuery && matchesDays
  }

  // Pause-and-start confirmation dialog
  pauseConfirm?.let { pending ->
  AlertDialog(
  onDismissRequest = { vm.dismissPauseConfirm() },
  title = { Text("Switch Program?") },
  text  = { Text("\"${pending.currentProgramName}\" is currently active. It will be paused — you can resume it any time from the Library.") },
  confirmButton = {
  Button(onClick = { vm.confirmStartProgram() },
  colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Pause & Start") }
  },
  dismissButton = { TextButton(onClick = { vm.dismissPauseConfirm() }) { Text("Cancel") } }
  )
  }

  // Resume/restart dialog for paused programs
  resumeTarget?.let { prog ->
  AlertDialog(
  onDismissRequest = { resumeTarget = null },
  title = { Text(prog.name) },
  text  = { Text("This program is paused. Would you like to pick up where you left off, or restart from Week 1?") },
  confirmButton = {
  Button(onClick = { vm.resumeProgram(prog); resumeTarget = null },
  colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Resume") }
  },
  dismissButton = {
  Column {
  TextButton(onClick = { vm.restartPausedProgram(prog); resumeTarget = null }) { Text("Restart from Week 1") }
  TextButton(onClick = { resumeTarget = null }) { Text("Cancel") }
  }
  }
  )
  }

  if (analysing || progressing) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
  CircularProgressIndicator(color = accent)
  Spacer(Modifier.height(12.dp))
  Text(if (analysing) "Analysing program..." else "Loading progression...",
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  return
  }
  analysis?.let  { AnalysisSheet(it, onDismiss = { vm.clearAnalysis() }) }
  progression?.let { ProgressionSheet(it, onDismiss = { vm.clearProgression() }) }

  // Trends (Phase 3G) — shown for the "Progress" action when advanced analytics is on.
  val trends         by vm.trends.collectAsStateWithLifecycle()
  val trendsLoading  by vm.trendsLoading.collectAsStateWithLifecycle()
  val trendsRange    by vm.trendsRangeDays.collectAsStateWithLifecycle()
  if (trends != null || trendsLoading) {
  TrendsSheet(
  trends = trends, loading = trendsLoading, rangeDays = trendsRange,
  onRange = { vm.setTrendsRange(it) }, onDismiss = { vm.clearTrends() }
  )
  }

  Scaffold(
  snackbarHost    = { SnackbarHost(snackbarHost) },
  containerColor  = MaterialTheme.colorScheme.background,
  contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
  ) { padding ->
  LazyColumn(
  modifier  = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).statusBarsPadding(),
  contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp)
  ) {
  // ── Header ────────────────────────────────────────────────────────
  item {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f)) {
  Text("My Library", style = MaterialTheme.typography.headlineMedium,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Text("${activePrograms.size} program${if (activePrograms.size != 1) "s" else ""}",
  style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  OutlinedButton(
  onClick  = { showBrowseSheet = true },
  shape  = RoundedCornerShape(12.dp),
  colors  = ButtonDefaults.outlinedButtonColors(contentColor = accent),
  border  = androidx.compose.foundation.BorderStroke(1.dp, accent)
  ) {
  Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(4.dp))
  Text("Add", fontWeight = FontWeight.Bold)
  }
  Button(
  onClick  = { navController.navigate("create_program") },
  shape  = RoundedCornerShape(12.dp),
  colors  = ButtonDefaults.buttonColors(containerColor = accent)
  ) {
  Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(4.dp))
  Text("Create", fontWeight = FontWeight.Bold)
  }
  }
  }

  Spacer(Modifier.height(16.dp))

  OutlinedTextField(
  value    = query,
  onValueChange = { query = it },
  modifier  = Modifier.fillMaxWidth(),
  placeholder = { Text("Search programs...", style = MaterialTheme.typography.bodyMedium) },
  leadingIcon = { Icon(Icons.Default.Search, null, tint = accent) },
  trailingIcon = if (query.isNotEmpty()) {{
    IconButton(onClick = { query = "" }) {
    Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }} else null,
  singleLine  = true,
  shape   = RoundedCornerShape(14.dp),
  colors  = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
  )

  Spacer(Modifier.height(10.dp))

  Row(
  modifier = Modifier.horizontalScroll(rememberScrollState()),
  horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
  DayFilterChip("Any", dayFilter == null, accent) { dayFilter = null }
  listOf(2, 3, 4, 5, 6).forEach { d ->
  DayFilterChip(if (d == 6) "6+" else "$d days", dayFilter == d, accent) {
    dayFilter = if (dayFilter == d) null else d
  }
  }
  }
  Spacer(Modifier.height(16.dp))
  }

  // ── Active program ─────────────────────────────────────────────────
  if (activeProgram != null) {
  item(key = "active_header") {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
  Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(0.15f)) {
  Text("ACTIVE", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  color = accent, style = MaterialTheme.typography.labelSmall,
  fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
  }
  }
  }
  item(key = "active_${activeProgram.id}") {
  ProgramCard(
  program  = activeProgram,
  onAnalyse  = { vm.analyseProgram(activeProgram) },
  onProgress  = { if (analyticsEnabled) vm.loadTrends(activeProgram) else vm.loadProgression(activeProgram) },
  onStart  = { vm.startProgram(activeProgram) },
  onDelete  = { vm.deleteProgram(activeProgram) },
  onView   = { vm.loadProgramForView(activeProgram) }
  )
  Spacer(Modifier.height(16.dp))
  }
  item(key = "active_divider") {
  HorizontalDivider(Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.outline.copy(0.3f))
  }
  }

  // Paused programs section
  if (pausedPrograms.isNotEmpty()) {
  item(key = "paused_header") {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
  Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer.copy(0.5f)) {
  Text("PAUSED", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  color = MaterialTheme.colorScheme.error,
  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
  }
  Spacer(Modifier.width(8.dp))
  Text("${pausedPrograms.size} program${if (pausedPrograms.size != 1) "s" else ""} paused",
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  items(pausedPrograms, key = { "paused_${it.id}" }) { prog ->
  PausedProgramCard(prog, accent,
  onResume  = { resumeTarget = prog },
  onRestart  = { resumeTarget = prog },
  onDelete  = { vm.deleteProgram(prog) }
  )
  Spacer(Modifier.height(10.dp))
  }
  item(key = "paused_divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(0.3f)) }
  }

  // Library programs (non-active)
  if (libraryPrograms.isEmpty() && activeProgram == null) {
  item {
  Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), Alignment.Center) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(Icons.AutoMirrored.Filled.LibraryBooks, null,
  tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f),
  modifier = Modifier.size(64.dp))
  Spacer(Modifier.height(12.dp))
  Text("No programs yet", style = MaterialTheme.typography.titleMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text("Tap Create above to build your first program",
  style = MaterialTheme.typography.bodySmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
  }
  }
  }
  } else if (filtered.isEmpty()) {
  item {
  Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
  Text("No programs match your filters",
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.bodyMedium)
  }
  }
  } else {
  items(filtered, key = { it.id }) { program ->
  ProgramCard(
  program  = program,
  onAnalyse  = { vm.analyseProgram(program) },
  onProgress  = { if (analyticsEnabled) vm.loadTrends(program) else vm.loadProgression(program) },
  onStart  = { vm.startProgram(program) },
  onDelete  = { vm.deleteProgram(program) },
  onView   = { vm.loadProgramForView(program) }
  )
  Spacer(Modifier.height(16.dp))
  }
  }

  // Session Library entry (moved to the bottom of the list)
  item {
  Spacer(Modifier.height(8.dp))
  GlassCard(modifier = Modifier.fillMaxWidth().clickable { navController.navigate("session_library") }) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
  .background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
  Icon(Icons.Default.FitnessCenter, null, tint = accent, modifier = Modifier.size(20.dp))
  }
  Spacer(Modifier.width(12.dp))
  Column(modifier = Modifier.weight(1f)) {
  Text("Session Library", fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
  Text("Reusable workouts — build an Arm Day, reuse anywhere",
  color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
  }
  Icon(Icons.Default.ChevronRight, null, tint = accent.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
  }
  }
  }
  } // end LazyColumn
  } // end Scaffold
}

@Composable
private fun DayFilterChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
  Surface(
  onClick = onClick,
  shape = RoundedCornerShape(20.dp),
  color = if (selected) accent else accent.copy(alpha = 0.1f),
  ) {
  Text(
  label,
  modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
  color = if (selected) Color.White else accent,
  style = MaterialTheme.typography.labelMedium,
  fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
  )
  }
}

// ── Program card ──────────────────────────────────────────────────────────────

@Composable
private fun ProgramCard(
  program:  Program,
  onAnalyse:  () -> Unit,
  onProgress: () -> Unit,
  onStart:  () -> Unit,
  onDelete:  () -> Unit,
  onView:  () -> Unit
) {
  val accent = LocalAccentColor.current
  var showDeleteConfirm by remember { mutableStateOf(false) }

  if (showDeleteConfirm) {
  AlertDialog(
  onDismissRequest = { showDeleteConfirm = false },
  title = { Text("Delete Program?") },
  text  = { Text("\"${program.name}\" will be permanently deleted along with all its logs and history.") },
  confirmButton = {
  TextButton(
  onClick = { showDeleteConfirm = false; onDelete() },
  colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
  ) { Text("Delete") }
  },
  dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
  )
  }

  GlassCard(modifier = Modifier.fillMaxWidth()) {
  Column {
  Box(modifier = Modifier.fillMaxWidth().height(3.dp)
  .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
  .background(accent))
  Spacer(Modifier.height(14.dp))
  Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
  verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f)) {
  Text(program.name, fontWeight = FontWeight.Bold, fontSize = 17.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(4.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  LibChip("${program.totalWeeks} weeks")
  if (program.isFlexible) LibChip("Flexible")
  if (program.isActive) LibChip("Active")
  }
  }
  if (!program.isActive) {
  Surface(onClick = onStart, shape = RoundedCornerShape(10.dp), color = accent) {
  Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
  verticalAlignment = Alignment.CenterVertically) {
  Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(4.dp))
  Text("Set Active", color = Color.White, fontWeight = FontWeight.Bold,
  style = MaterialTheme.typography.labelMedium)
  }
  }
  }
  }
  Spacer(Modifier.height(14.dp))
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.3f))
  Row(Modifier.fillMaxWidth()) {
  ActionButton("View", Icons.Default.Edit, accent, onView, Modifier.weight(1f))
  VertDivider()
  ActionButton("Analyse", Icons.Default.Psychology, accent, onAnalyse, Modifier.weight(1f))
  VertDivider()
  ActionButton("Progress", Icons.AutoMirrored.Filled.TrendingUp, accent, onProgress, Modifier.weight(1f))
  VertDivider()
  ActionButton("Delete", Icons.Default.Delete,
  MaterialTheme.colorScheme.error, { showDeleteConfirm = true }, Modifier.weight(1f))
  }
  }
  }
}

@Composable
private fun PausedProgramCard(program: Program, accent: Color, onResume: () -> Unit, onRestart: () -> Unit, onDelete: () -> Unit) {
  var showDeleteConfirm by remember { mutableStateOf(false) }
  if (showDeleteConfirm) {
  AlertDialog(
  onDismissRequest = { showDeleteConfirm = false },
  title = { Text("Delete program?") },
  text  = { Text("\"${program.name}\" and its logged sets will be permanently removed.") },
  confirmButton = {
  TextButton(onClick = { showDeleteConfirm = false; onDelete() },
  colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
  },
  dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
  )
  }
  GlassCard(modifier = Modifier.fillMaxWidth()) {
  Column {
  Box(modifier = Modifier.fillMaxWidth().height(3.dp)
  .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
  .background(MaterialTheme.colorScheme.error.copy(0.5f)))
  Spacer(Modifier.height(14.dp))
  Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
  verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f)) {
  Text(program.name, fontWeight = FontWeight.Bold, fontSize = 17.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(4.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  LibChip("${program.totalWeeks} weeks")
  Surface(shape = RoundedCornerShape(20.dp),
  color = MaterialTheme.colorScheme.errorContainer.copy(0.5f)) {
  Text("Paused", modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
  color = MaterialTheme.colorScheme.error,
  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
  }
  }
  }
  }
  Spacer(Modifier.height(14.dp))
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.3f))
  Row(Modifier.fillMaxWidth()) {
  ActionButton("Resume", Icons.Default.PlayArrow, accent, onResume, Modifier.weight(1f))
  VertDivider()
  ActionButton("Restart", Icons.Default.Refresh,
  MaterialTheme.colorScheme.onSurfaceVariant, onRestart, Modifier.weight(1f))
  VertDivider()
  ActionButton("Delete", Icons.Default.Delete,
  MaterialTheme.colorScheme.error, { showDeleteConfirm = true }, Modifier.weight(1f))
  }
  }
  }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
  tint: Color, onClick: () -> Unit, modifier: Modifier) {
  TextButton(
    onClick = onClick,
    modifier = modifier,
    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
  ) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
    Spacer(Modifier.width(3.dp))
    Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
  }
}

@Composable
private fun VertDivider() {
  Box(Modifier.width(1.dp).height(36.dp)
  .background(MaterialTheme.colorScheme.outline.copy(0.3f)))
}

@Composable
private fun LibChip(label: String) {
  val accent = LocalAccentColor.current
  Surface(shape = RoundedCornerShape(20.dp), color = accent.copy(alpha = 0.12f)) {
  Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
  color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
  }
}

// ── Analysis bottom sheet ─────────────────────────────────────────────────────

@Composable
private fun AnalysisSheet(result: AnalysisResult, onDismiss: () -> Unit) {
  val accent  = LocalAccentColor.current
  val muscleMap = result.rows.associate { it.muscle to it.setsPerWeek }
  val maxSets  = result.rows.maxOfOrNull { it.setsPerWeek }?.coerceAtLeast(1f) ?: 1f

  ModalBottomSheet(onDismissRequest = onDismiss) {
  LazyColumn(
  modifier  = Modifier.fillMaxWidth(),
  contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)
  ) {
  // Header
  item {
  Text("Program Analysis", style = MaterialTheme.typography.titleLarge,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Text(result.programName, style = MaterialTheme.typography.bodyMedium, color = accent)
  Text("Sets per week  ·  primary = 1x  ·  secondary = 0.5x",
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(16.dp))
  }

  // Dashboard summary cards
  item {
  DashboardSection(result.dashboard, accent)
  Spacer(Modifier.height(20.dp))
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.3f))
  Spacer(Modifier.height(16.dp))
  Text("Volume by Muscle", style = MaterialTheme.typography.titleMedium,
  fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(4.dp))
  }

  // Muscle rows by region
  MUSCLE_ORDER.forEach { (region, muscles) ->
  val present = muscles.filter { muscleMap.containsKey(it) }
  if (present.isEmpty()) return@forEach

  item(key = "hdr_$region") {
  Spacer(Modifier.height(10.dp))
  Text(region.uppercase(), style = MaterialTheme.typography.labelSmall,
  fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
  Spacer(Modifier.height(6.dp))
  }
  items(present, key = { it.name }) { muscle ->
  val sets = muscleMap[muscle] ?: 0f
  MuscleBar(muscle, sets, maxSets)
  Spacer(Modifier.height(8.dp))
  }
  }

  // Legend
  item {
  Spacer(Modifier.height(8.dp))
  VolumeLegend()
  }

  // Unknown exercises
  if (result.unknownExercises.isNotEmpty()) {
  item {
  Spacer(Modifier.height(16.dp))
  Surface(shape = RoundedCornerShape(12.dp),
  color = MaterialTheme.colorScheme.errorContainer.copy(0.4f)) {
  Column(Modifier.padding(12.dp)) {
  Text("${result.unknownExercises.size} exercise${if (result.unknownExercises.size != 1) "s" else ""} not recognised",
  fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onErrorContainer,
  style = MaterialTheme.typography.bodySmall)
  Spacer(Modifier.height(4.dp))
  result.unknownExercises.forEach { name ->
  Text("- $name", color = MaterialTheme.colorScheme.onErrorContainer.copy(0.8f),
  style = MaterialTheme.typography.bodySmall)
  }
  Spacer(Modifier.height(4.dp))
  Text("These are excluded from the breakdown.",
  color = MaterialTheme.colorScheme.onErrorContainer.copy(0.6f),
  style = MaterialTheme.typography.labelSmall)
  }
  }
  }
  }
  }
  }
}

// ── Dashboard section ─────────────────────────────────────────────────────────

@Composable
private fun DashboardSection(dash: ProgramDashboard, accent: Color) {
  Text("Dashboard", style = MaterialTheme.typography.titleMedium,
  fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))

  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  StatCard("Weekly sets", "${dash.weeklySetTotal}", "across all muscles", Modifier.weight(1f), accent)
  StatCard("Balance", dash.overallBalance, "Push : Pull ${dash.pushPullRatio}", Modifier.weight(1f), accent)
  }
  Spacer(Modifier.height(10.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  StatCard("Most trained", dash.topMuscle?.display ?: " - ", "highest weekly sets", Modifier.weight(1f), accent)
  StatCard("Needs work", dash.weakestMuscle?.display ?: " - ",
  "lowest relative to target", Modifier.weight(1f),
  if (dash.weakestMuscle != null) Color(0xFFFFC107) else accent)
  }

  if (dash.insights.isNotEmpty()) {
  Spacer(Modifier.height(12.dp))
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
  dash.insights.forEach { insight ->
  Row(verticalAlignment = Alignment.Top) {
  Icon(Icons.Default.Info, null, tint = accent,
  modifier = Modifier.size(15.dp).padding(top = 2.dp))
  Spacer(Modifier.width(6.dp))
  Text(insight, style = MaterialTheme.typography.bodySmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  }
  }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String, modifier: Modifier, accent: Color) {
  Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
  color = accent.copy(alpha = 0.08f)) {
  Column(Modifier.padding(12.dp)) {
  Text(title, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Text(subtitle, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
  }
  }
}

// ── Muscle bar ────────────────────────────────────────────────────────────────

@Composable
private fun MuscleBar(muscle: MuscleGroup, sets: Float, maxSets: Float) {
  var triggered by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { triggered = true }
  val fraction by animateFloatAsState(
  if (triggered) (sets / maxSets).coerceIn(0f, 1f) else 0f,
  tween(500), label = muscle.name
  )

  val status  = setsStatus(sets)
  val barColor = when (status) {
  VolumeStatus.OPTIMAL -> Color(0xFF4CAF50)
  VolumeStatus.OK  -> Color(0xFFFFC107)
  VolumeStatus.LOW  -> Color(0xFFF44336)
  }
  val setsLabel = if (sets % 1f == 0f) sets.toInt().toString() else "%.1f".format(sets)
  val statusLabel = when (status) {
  VolumeStatus.OPTIMAL -> "Optimal"
  VolumeStatus.OK  -> "OK"
  VolumeStatus.LOW  -> "Low"
  }

  Column {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
  Text(muscle.display, style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
  Surface(shape = RoundedCornerShape(8.dp), color = barColor.copy(0.15f)) {
  Text("$setsLabel sets  $statusLabel",
  style = MaterialTheme.typography.labelSmall,
  color = barColor,
  fontWeight = FontWeight.SemiBold,
  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
  }
  }
  Spacer(Modifier.height(3.dp))
  Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape)
  .background(MaterialTheme.colorScheme.outline.copy(0.2f))) {
  Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(barColor))
  }
  }
}

@Composable
private fun VolumeLegend() {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
  Text("Volume guide:", style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
  Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
  LegendItem(Color(0xFF4CAF50), "6-8+ sets = Optimal")
  LegendItem(Color(0xFFFFC107), "4-5 = OK")
  LegendItem(Color(0xFFF44336), "< 4 = Low")
  }
  }
}

@Composable
private fun LegendItem(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  Box(Modifier.size(9.dp).clip(CircleShape).background(color))
  Spacer(Modifier.width(4.dp))
  Text(label, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// ── Progression bottom sheet ──────────────────────────────────────────────────

@Composable
private fun ProgressionSheet(result: ProgressionResult, onDismiss: () -> Unit) {
  val accent = LocalAccentColor.current

  ModalBottomSheet(onDismissRequest = onDismiss) {
  LazyColumn(
  modifier  = Modifier.fillMaxWidth(),
  contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)
  ) {
  item {
  Text("Progression", style = MaterialTheme.typography.titleLarge,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Text(result.programName, style = MaterialTheme.typography.bodyMedium, color = accent)
  Spacer(Modifier.height(4.dp))
  Text("Week-by-week strength and volume for each movement",
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(16.dp))
  }

  // Headline stats
  if (result.bestGain != null || result.biggestDrop != null) {
  item {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  result.bestGain?.let { g ->
  HighlightCard(
  title  = "Best gain",
  value  = "+%.1f kg".format(g.weightChangeKg),
  subtitle = g.exerciseName,
  color  = Color(0xFF4CAF50),
  modifier = Modifier.weight(1f)
  )
  }
  result.biggestDrop?.let { d ->
  HighlightCard(
  title  = "Biggest drop",
  value  = "%.1f kg".format(d.weightChangeKg),
  subtitle = d.exerciseName,
  color  = Color(0xFFF44336),
  modifier = Modifier.weight(1f)
  )
  } ?: if (result.bestGain != null) Spacer(Modifier.weight(1f)) else Unit
  }
  Spacer(Modifier.height(16.dp))
  }
  }

  if (result.exercises.isEmpty()) {
  item {
  Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
  Text("No logged data yet. Complete some sessions to see progression.",
  textAlign = TextAlign.Center,
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.bodyMedium)
  }
  }
  } else {
  items(result.exercises, key = { "${it.dayNumber}_${it.orderIndex}" }) { ex ->
  ExerciseProgressionCard(ex, accent)
  Spacer(Modifier.height(14.dp))
  }
  }
  }
  }
}

@Composable
private fun HighlightCard(title: String, value: String, subtitle: String, color: Color, modifier: Modifier) {
  Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = color.copy(0.1f)) {
  Column(Modifier.padding(12.dp)) {
  Text(title, style = MaterialTheme.typography.labelSmall, color = color)
  Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color)
  Text(subtitle, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
  }
  }
}

@Composable
private fun ExerciseProgressionCard(ex: ExerciseProgression, accent: Color) {
  val trendColor = when (ex.trend) {
  Trend.IMPROVING -> Color(0xFF4CAF50)
  Trend.FLAT  -> Color(0xFFFFC107)
  Trend.DECLINING -> Color(0xFFF44336)
  }
  val trendIcon = when (ex.trend) {
  Trend.IMPROVING -> Icons.AutoMirrored.Filled.TrendingUp
  Trend.FLAT  -> Icons.AutoMirrored.Filled.TrendingFlat
  Trend.DECLINING -> Icons.AutoMirrored.Filled.TrendingDown
  }
  val trendLabel = when (ex.trend) {
  Trend.IMPROVING -> "Improving"
  Trend.FLAT  -> "Flat"
  Trend.DECLINING -> "Declining"
  }

  GlassCard(modifier = Modifier.fillMaxWidth()) {
  Column {
  // Header row
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f)) {
  Text(ex.exerciseName, fontWeight = FontWeight.Bold, fontSize = 15.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Text("Day ${ex.dayNumber}", style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Surface(shape = RoundedCornerShape(20.dp), color = trendColor.copy(0.12f)) {
  Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
  verticalAlignment = Alignment.CenterVertically) {
  Icon(trendIcon, null, tint = trendColor, modifier = Modifier.size(14.dp))
  Spacer(Modifier.width(3.dp))
  Text(trendLabel, style = MaterialTheme.typography.labelSmall,
  color = trendColor, fontWeight = FontWeight.SemiBold)
  }
  }
  }

  Spacer(Modifier.height(8.dp))

  // Weight change summary
  val wChangeStr = if (ex.weightChangeKg >= 0) "+%.1f kg".format(ex.weightChangeKg)
  else "%.1f kg".format(ex.weightChangeKg)
  val volChangeStr = if (ex.volumeChangePercent >= 0) "+%.0f%%".format(ex.volumeChangePercent)
  else "%.0f%%".format(ex.volumeChangePercent)

  Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
  MiniStat("Weight change", wChangeStr,
  if (ex.weightChangeKg >= 0) Color(0xFF4CAF50) else Color(0xFFF44336))
  MiniStat("Volume change", volChangeStr,
  if (ex.volumeChangePercent >= 0) Color(0xFF4CAF50) else Color(0xFFF44336))
  MiniStat("Weeks tracked", "${ex.snapshots.size}", accent)
  }

  Spacer(Modifier.height(10.dp))

  // Week-by-week bar chart (max weight per week)
  val maxW = ex.snapshots.maxOfOrNull { it.maxWeightKg }?.coerceAtLeast(1f) ?: 1f
  Text("Max weight per week", style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(6.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
  verticalAlignment = Alignment.Bottom) {
  ex.snapshots.forEach { snap ->
  val frac = (snap.maxWeightKg / maxW).coerceIn(0.05f, 1f)
  Column(horizontalAlignment = Alignment.CenterHorizontally,
  modifier = Modifier.weight(1f)) {
  Text(
  if (snap.maxWeightKg % 1f == 0f) snap.maxWeightKg.toInt().toString()
  else "%.0f".format(snap.maxWeightKg),
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  fontSize = 9.sp
  )
  Spacer(Modifier.height(2.dp))
  Box(Modifier.fillMaxWidth().height((frac * 40f).dp)
  .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
  .background(accent))
  Spacer(Modifier.height(2.dp))
  Text("W${snap.week}", style = MaterialTheme.typography.labelSmall,
  fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  }

  // Correlation insight
  ex.volumeCorrelation?.let { corr ->
  Spacer(Modifier.height(8.dp))
  Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(0.08f)) {
  Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
  verticalAlignment = Alignment.Top) {
  Icon(Icons.Default.Insights, null, tint = accent,
  modifier = Modifier.size(14.dp).padding(top = 1.dp))
  Spacer(Modifier.width(6.dp))
  Text(corr, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  fontStyle = FontStyle.Italic)
  }
  }
  }
  }
  }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
  Column {
  Text(label, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 15.sp)
  }
}

// ── Browse default programs sheet ─────────────────────────────────────────────

/** Days/week for a catalogue program: explicit tag if set, else distinct week-1 day count. */
internal fun browseDays(p: ParsedProgram): Int =
  if (p.daysPerWeek > 0) p.daysPerWeek
  else p.sessions.filter { it.weekNumber == 1 }.map { it.dayNumber }.distinct().size

/** Style tag for a catalogue program, falling back to its category. */
internal fun browseStyle(p: ParsedProgram): String = p.style.ifBlank { p.category }

@Composable
fun BrowseDefaultsSheet(
  programs: List<ParsedProgram>,
  onAddToLibrary: (ParsedProgram) -> Unit,
  onSetAsActive: (ParsedProgram) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  var query   by remember { mutableStateOf("") }
  var coachF  by remember { mutableStateOf<String?>(null) }
  var daysF   by remember { mutableStateOf<Int?>(null) }
  var detail    by remember { mutableStateOf<ParsedProgram?>(null) }
  var coachPage by remember { mutableStateOf<String?>(null) }

  val coaches = remember(programs) { programs.map { it.coach }.filter { it.isNotBlank() }.distinct().sorted() }
  val dayOptions = remember(programs) { programs.map { browseDays(it) }.filter { it > 0 }.distinct().sorted() }

  val filtered = programs.filter { p ->
  (query.isBlank() || p.name.contains(query.trim(), true) || p.coach.contains(query.trim(), true)) &&
  (coachF == null || p.coach == coachF) &&
  (daysF == null || browseDays(p) == daysF)
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
  when {
  detail != null -> ProgramDetailContent(
  prog = detail!!, accent = accent,
  onBack = { detail = null },
  onAddToLibrary = { onAddToLibrary(it); onDismiss() },
  onSetAsActive = { onSetAsActive(it); onDismiss() },
  onCoach = { coachPage = it; detail = null },
  onPickDays = { daysF = it; detail = null }
  )
  coachPage != null -> CoachContent(
  coach = coachPage!!, programs = programs, accent = accent,
  onBack = { coachPage = null },
  onOpenProgram = { detail = it; coachPage = null }
  )
  else -> {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
  Text("Browse Programs", fontWeight = FontWeight.Bold, fontSize = 18.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))
  OutlinedTextField(
  value = query, onValueChange = { query = it },
  modifier = Modifier.fillMaxWidth(),
  placeholder = { Text("Search programs or coaches…", style = MaterialTheme.typography.bodyMedium) },
  leadingIcon = { Icon(Icons.Default.Search, null, tint = accent) },
  trailingIcon = if (query.isNotEmpty()) {{
    IconButton(onClick = { query = "" }) {
    Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }} else null,
  singleLine = true, shape = RoundedCornerShape(12.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
  )
  Spacer(Modifier.height(8.dp))

  if (coaches.isNotEmpty())
  BrowseFacetRow("Coach", coaches.map { it to it }, coachF) { coachF = it }
  if (dayOptions.isNotEmpty())
  BrowseFacetRow("Days", dayOptions.map { it to ("$it days") }, daysF) { daysF = it }
  Spacer(Modifier.height(8.dp))
  }

  LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp)) {
  if (programs.isEmpty()) {
  item {
  Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
  Text("No programs yet — coaches & programs arrive in the next update.",
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.bodyMedium)
  }
  }
  } else if (filtered.isEmpty()) {
  item {
  Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
  Text("No programs match your filters", color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.bodyMedium)
  }
  }
  } else {
  items(filtered, key = { "prog_${it.name}" }) { prog ->
  ProgramBrowseCard(prog = prog, accent = accent,
  onOpen = { detail = prog }, onCoach = { prog.coach.takeIf { c -> c.isNotBlank() }?.let { coachPage = it } })
  }
  }
  }
  }
  }
  }
}

/** A labelled, horizontally-scrolling row of selectable filter chips (with an "All" reset). */
@Composable
private fun <T> BrowseFacetRow(
  label: String,
  options: List<Pair<T, String>>,
  selected: T?,
  onSelect: (T?) -> Unit
) {
  val accent = LocalAccentColor.current
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
  Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(46.dp))
  Row(
  modifier = Modifier.horizontalScroll(rememberScrollState()).weight(1f),
  horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
  DayFilterChip("All", selected == null, accent) { onSelect(null) }
  options.forEach { (value, text) ->
  DayFilterChip(text, selected == value, accent) { onSelect(if (selected == value) null else value) }
  }
  }
  }
}

/** A tappable program card in the browse list: name, coach, meta + quick tag chips. */
@Composable
private fun ProgramBrowseCard(
  prog: ParsedProgram,
  accent: Color,
  onOpen: () -> Unit,
  onCoach: () -> Unit
) {
  val weeks = prog.sessions.map { it.weekNumber }.distinct().size
  Surface(
  onClick = onOpen,
  shape = RoundedCornerShape(12.dp),
  color = MaterialTheme.colorScheme.surface,
  modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
  ) {
  Column(Modifier.padding(14.dp)) {
  Text(prog.name, fontWeight = FontWeight.Bold, fontSize = 15.sp,
  color = MaterialTheme.colorScheme.onBackground)
  if (prog.coach.isNotBlank()) {
  Text("by ${prog.coach}", style = MaterialTheme.typography.labelMedium, color = accent,
  modifier = Modifier.clickable(onClick = onCoach).padding(top = 2.dp))
  }
  Spacer(Modifier.height(6.dp))
  Text("$weeks weeks · ${browseDays(prog)} days/week", style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(8.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  if (prog.split.isNotBlank()) BrowseTag(prog.split, accent)
  val style = browseStyle(prog)
  if (style.isNotBlank()) BrowseTag(style, accent)
  }
  }
  }
}

/** A small, non-interactive tag pill. */
@Composable
private fun BrowseTag(text: String, accent: Color) {
  Surface(shape = RoundedCornerShape(20.dp), color = accent.copy(alpha = 0.1f)) {
  Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  style = MaterialTheme.typography.labelSmall, color = accent)
  }
}

/** Program detail: description + clickable tags (filter on tap) + add/activate. */
@Composable
private fun ProgramDetailContent(
  prog: ParsedProgram,
  accent: Color,
  onBack: () -> Unit,
  onAddToLibrary: (ParsedProgram) -> Unit,
  onSetAsActive: (ParsedProgram) -> Unit,
  onCoach: (String) -> Unit,
  onPickDays: (Int) -> Unit
) {
  val weeks = prog.sessions.map { it.weekNumber }.distinct().size
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)
  .verticalScroll(rememberScrollState())) {
  TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
  Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = accent, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(4.dp)); Text("Back", color = accent)
  }
  Text(prog.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
  if (prog.coach.isNotBlank()) {
  Text("by ${prog.coach}", style = MaterialTheme.typography.bodyMedium, color = accent,
  modifier = Modifier.clickable { onCoach(prog.coach) }.padding(top = 2.dp))
  }
  Spacer(Modifier.height(12.dp))
  if (prog.description.isNotBlank()) {
  Text(prog.description, style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(14.dp))
  }
  Text("TAGS — tap to filter", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(6.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  DayFilterChip("${browseDays(prog)} days", false, accent) { onPickDays(browseDays(prog)) }
  }
  Spacer(Modifier.height(8.dp))
  Text("$weeks weeks · ${browseDays(prog)} days/week", style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)

  // ── Sample-week preview (see what you're getting before you start) ──
  var showPreview by remember { mutableStateOf(false) }
  val firstWeek = prog.sessions.minOfOrNull { it.weekNumber } ?: 1
  val previewDays = prog.sessions.filter { it.weekNumber == firstWeek }.sortedBy { it.dayNumber }
  if (previewDays.isNotEmpty()) {
  Spacer(Modifier.height(12.dp))
  TextButton(onClick = { showPreview = !showPreview }, contentPadding = PaddingValues(0.dp)) {
  Icon(if (showPreview) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
  tint = accent, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(4.dp))
  Text(if (showPreview) "Hide sample week" else "Preview sample week", color = accent,
  fontWeight = FontWeight.SemiBold)
  }
  if (showPreview) {
  previewDays.forEach { day ->
  Surface(shape = RoundedCornerShape(12.dp),
  color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
  modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
  Column(Modifier.padding(12.dp)) {
  Text(day.name.ifBlank { "Day ${day.dayNumber}" }, fontWeight = FontWeight.SemiBold,
  fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(6.dp))
  if (day.exercises.isEmpty()) {
  Text("No exercises listed", style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  } else day.exercises.forEach { ex ->
  Text("• ${ex.name} — ${ex.sets} × ${ex.repsTarget.ifBlank { "—" }}",
  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  }
  }
  }
  }

  Spacer(Modifier.height(20.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  OutlinedButton(onClick = { onAddToLibrary(prog) }, modifier = Modifier.weight(1f),
  shape = RoundedCornerShape(10.dp),
  border = androidx.compose.foundation.BorderStroke(1.dp, accent),
  colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("Add to Library") }
  Button(onClick = { onSetAsActive(prog) }, modifier = Modifier.weight(1f),
  shape = RoundedCornerShape(10.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Set Active") }
  }
  }
}

/** Coach page: their bio + the programs they wrote. */
@Composable
private fun CoachContent(
  coach: String,
  programs: List<ParsedProgram>,
  accent: Color,
  onBack: () -> Unit,
  onOpenProgram: (ParsedProgram) -> Unit
) {
  val theirs = programs.filter { it.coach == coach }
  val bio = theirs.firstOrNull { it.coachBio.isNotBlank() }?.coachBio.orEmpty()
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
  TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
  Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = accent, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(4.dp)); Text("Back", color = accent)
  }
  Text(coach, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
  if (bio.isNotBlank()) {
  Spacer(Modifier.height(8.dp))
  Text(bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Spacer(Modifier.height(16.dp))
  Text("PROGRAMS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(6.dp))
  }
  LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp)) {
  items(theirs, key = { "coach_prog_${it.name}" }) { prog ->
  ProgramBrowseCard(prog = prog, accent = accent, onOpen = { onOpenProgram(prog) }, onCoach = {})
  }
  }
}

// ── View / Edit program bottom sheet ─────────────────────────────────────────

@Composable
private fun ViewProgramSheet(
  detail: ProgramDetail,
  onDismiss: () -> Unit,
  onSaveExercise: (id: Long, name: String, sets: Int, reps: String, orderIndex: Int, dayNumber: Int) -> Unit
) {
  val accent = LocalAccentColor.current
  val sheetState = rememberModalBottomSheetState()

  ModalBottomSheet(
  onDismissRequest = onDismiss,
  sheetState = sheetState,
  containerColor = MaterialTheme.colorScheme.background
  ) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
    Text(
    detail.program.name,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(4.dp))
    Text(
    "${detail.weeks.size} weeks · ${detail.weeks.firstOrNull()?.days?.size ?: 0} days/week",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(16.dp))
  }

  LazyColumn(
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp)
  ) {
    detail.weeks.forEach { week ->
    item(key = "week_${week.weekNumber}") {
      Text(
      "Week ${week.weekNumber}",
      fontWeight = FontWeight.SemiBold,
      fontSize = 15.sp,
      color = accent,
      modifier = Modifier.padding(vertical = 8.dp)
      )
    }
    week.days.forEach { day ->
      item(key = "week_${week.weekNumber}_day_${day.dayNumber}") {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
      ) {
        Column(Modifier.padding(12.dp)) {
        Text(
          day.sessionName,
          fontWeight = FontWeight.SemiBold,
          fontSize = 14.sp,
          color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        day.exercises.forEach { ex ->
          ViewExerciseRow(
          exercise = ex,
          onSave = { name, sets, reps ->
            onSaveExercise(ex.id, name, sets, reps, ex.orderIndex, day.dayNumber)
          }
          )
          Spacer(Modifier.height(6.dp))
        }
        }
      }
      }
    }
    }
  }
  }
}

@Composable
private fun ViewExerciseRow(
  exercise: ExerciseDetail,
  onSave: (name: String, sets: Int, reps: String) -> Unit
) {
  val accent = LocalAccentColor.current
  var editing by remember(exercise.id) { mutableStateOf(false) }
  var nameText by remember(exercise.id) { mutableStateOf(exercise.name) }
  var setsText by remember(exercise.id) { mutableStateOf(exercise.sets.toString()) }
  var repsText by remember(exercise.id) { mutableStateOf(exercise.repsTarget) }

  if (editing) {
  Column(
    modifier = Modifier
    .fillMaxWidth()
    .clip(RoundedCornerShape(8.dp))
    .background(MaterialTheme.colorScheme.surface)
    .padding(10.dp)
  ) {
    OutlinedTextField(
    value = nameText,
    onValueChange = { nameText = it },
    label = { Text("Exercise name") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = setsText,
      onValueChange = { setsText = it.filter { c -> c.isDigit() } },
      label = { Text("Sets") },
      singleLine = true,
      modifier = Modifier.weight(1f),
      colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    )
    OutlinedTextField(
      value = repsText,
      onValueChange = { repsText = it },
      label = { Text("Reps") },
      singleLine = true,
      modifier = Modifier.weight(1f),
      colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
    TextButton(onClick = {
      nameText = exercise.name; setsText = exercise.sets.toString()
      repsText = exercise.repsTarget; editing = false
    }) { Text("Cancel") }
    Spacer(Modifier.width(8.dp))
    Button(
      onClick = {
      val sets = setsText.toIntOrNull() ?: exercise.sets
      onSave(nameText.trim().ifEmpty { exercise.name }, sets, repsText.trim())
      editing = false
      },
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Save") }
    }
  }
  } else {
  Row(
    modifier = Modifier
    .fillMaxWidth()
    .clip(RoundedCornerShape(8.dp))
    .clickable { editing = true }
    .background(MaterialTheme.colorScheme.surface)
    .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
    Text(exercise.name, fontWeight = FontWeight.Medium, fontSize = 13.sp,
      color = MaterialTheme.colorScheme.onBackground)
    Text("${exercise.sets} sets · ${exercise.repsTarget.ifEmpty { "—" }} reps",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(Icons.Default.Edit, null, tint = accent.copy(alpha = 0.5f),
    modifier = Modifier.size(16.dp))
  }
  }
}
