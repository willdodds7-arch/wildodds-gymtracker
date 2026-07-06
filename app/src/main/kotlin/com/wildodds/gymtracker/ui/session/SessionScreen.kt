@file:OptIn(
  androidx.compose.foundation.ExperimentalFoundationApi::class,
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
package com.wildodds.gymtracker.ui.session

import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.R
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.data.intelligence.FatigueLevel
import com.wildodds.gymtracker.data.intelligence.FatigueResult
import com.wildodds.gymtracker.data.intelligence.ScoredExercise
import com.wildodds.gymtracker.data.intelligence.TranslatedExercise
import com.wildodds.gymtracker.data.intelligence.TravelEquipment
import com.wildodds.gymtracker.ui.components.ProgressionBadge
import com.wildodds.gymtracker.ui.settings.FeatureFlags
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import com.wildodds.gymtracker.ui.settings.SettingsViewModel
import com.wildodds.gymtracker.ui.tools.PlateCalculatorSheet
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.LocalDarkMode
import com.wildodds.gymtracker.ui.theme.SetRowFilled
import com.wildodds.gymtracker.ui.theme.SetRowFilledDark

@Composable
fun SessionScreen(
  navController: NavController,
  vm: SessionViewModel = viewModel(),
  settingsVm: SettingsViewModel = viewModel()
) {
  val state         by vm.state.collectAsStateWithLifecycle()
  val timerState    by vm.timerState.collectAsStateWithLifecycle()
  val featTimer     by settingsVm.featSessionTimer.collectAsStateWithLifecycle()
  val featAutofill  by settingsVm.featWeightAutofill.collectAsStateWithLifecycle()
  val featAddEx     by settingsVm.featAddExercise.collectAsStateWithLifecycle()
  val featProgression by settingsVm.featProgressionPicker.collectAsStateWithLifecycle()
  val feat1rmCalc     by settingsVm.feat1rmCalculator.collectAsStateWithLifecycle()
  val timerSound      by settingsVm.timerSound.collectAsStateWithLifecycle()

  // Exit-session confirmation guard (registry flag, default ON). Only intercepts back when the
  // user has logged something this visit and the session isn't already complete.
  val exitGuardEnabled   = FeatureFlags.isEnabled(SettingsRegistry.SESSION_EXIT_GUARD)
  val hasLoggedThisVisit by vm.hasLoggedThisVisit.collectAsStateWithLifecycle()
  val shouldGuardLeaving = shouldGuardExit(exitGuardEnabled, hasLoggedThisVisit, state.isCompleted)
  var showExitConfirm    by remember { mutableStateOf(false) }
  BackHandler(enabled = shouldGuardLeaving) { showExitConfirm = true }

  // Post-session summary + plate calculator (Phase 2A), each gated by its registry flag.
  val summaryEnabled     = FeatureFlags.isEnabled(SettingsRegistry.SESSION_SUMMARY)
  val hrInSummaryEnabled = FeatureFlags.isEnabled(SettingsRegistry.SUMMARY_HEART_RATE)
  val plateCalcEnabled   = FeatureFlags.isEnabled(SettingsRegistry.PLATE_CALCULATOR)
  val summaryData        by vm.summary.collectAsStateWithLifecycle()
  var showPlateCalc      by remember { mutableStateOf(false) }

  // Travel mode (Phase 3D) — gated; OFF hides the entry point entirely.
  val travelEnabled      = FeatureFlags.isEnabled(SettingsRegistry.TRAVEL_MODE)
  val travelOverlay      by vm.travelOverlay.collectAsStateWithLifecycle()
  val travelActive       = travelOverlay != null
  var showTravelSheet    by remember { mutableStateOf(false) }

  summaryData?.let { data ->
  SessionSummarySheet(
  data = data,
  showHeartRate = hrInSummaryEnabled,
  onDone = { strain -> vm.finishSummary(strain) { navController.popBackStack() } }
  )
  }
  if (showPlateCalc) {
  PlateCalculatorSheet(onDismiss = { showPlateCalc = false })
  }
  if (showTravelSheet) {
  TravelModeSheet(
  initial = vm.lastTravelEquipment,
  onStart = { selected -> vm.activateTravelMode(selected); showTravelSheet = false },
  onDismiss = { showTravelSheet = false }
  )
  }

  // Injury triage + accommodation (Phase 3E) — gated; OFF hides the entry point.
  val injuryEnabled   = FeatureFlags.isEnabled(SettingsRegistry.INJURY_TRIAGE)
  val injuryOverlay   by vm.injuryOverlay.collectAsStateWithLifecycle()
  var showInjurySheet by remember { mutableStateOf(false) }
  if (showInjurySheet) {
  InjuryTriageSheet(
  onApply = { region, tags -> vm.applyInjuryAccommodation(region, tags) },
  onDismiss = { showInjurySheet = false }
  )
  }
  // Injury swaps take precedence over travel for the per-exercise overlay.
  val activeOverlay = injuryOverlay?.swaps ?: travelOverlay
  val overlayMarker = if (injuryOverlay != null) "Modified" else "Travel"

  // Real-time fatigue (Phase 3F). The VM only populates this when the feature is on.
  val fatigue          by vm.fatigue.collectAsStateWithLifecycle()
  val fatigueDismissed by vm.fatigueSuggestionDismissed.collectAsStateWithLifecycle()

  // Live heart rate (next to the pager dots). Null = no wearable data / HR off → indicator hidden.
  val liveHeartRate by vm.liveHeartRate.collectAsStateWithLifecycle()

  val focusManager  = LocalFocusManager.current
  val context = LocalContext.current

  // "How should you progress?" section: collapsible so it doesn't permanently eat screen
  // space, and auto-minimised while the keyboard is up so the field being typed into
  // never ends up squeezed off-screen behind it.
  val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  var progressionExpandedByUser by remember { mutableStateOf(true) }
  val progressionExpanded = progressionExpandedByUser && !imeVisible

  var showGlobal1rmCalc by remember { mutableStateOf(false) }
  if (showGlobal1rmCalc) {
    OneRmCalculatorSheet(onDismiss = { showGlobal1rmCalc = false })
  }

  // rememberUpdatedState ensures the coroutine always reads the latest timerSound
  // even though LaunchedEffect(Unit) only launches once.
  val currentTimerSound = rememberUpdatedState(timerSound)

  // Play sound when rest timer finishes
  LaunchedEffect(Unit) {
  vm.timerFinishedEvent.collect {
  suspend fun playDing() {
  try {
  val toneGen = ToneGenerator(AudioManager.STREAM_RING, ToneGenerator.MAX_VOLUME)
  toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
  kotlinx.coroutines.delay(800)
  toneGen.release()
  } catch (_: Exception) { /* device doesn't support ToneGenerator */ }
  }
  when (currentTimerSound.value) {
  "ding" -> playDing()
  "ronnie" -> {
  try {
  val mp = MediaPlayer.create(context, R.raw.ronnie_coleman)
  if (mp != null) {
  mp.start()
  mp.setOnCompletionListener { it.release() }
  } else {
  playDing() // placeholder file — fall back to ding
  }
  } catch (_: Exception) {
  playDing()
  }
  }
  // "none" → do nothing
  }
  }
  }
  val pages     = remember(state.exercises) { buildSupersetPages(state.exercises) }
  val pagerState  = rememberPagerState { pages.size.coerceAtLeast(1) }
  val scope   = rememberCoroutineScope()

  if (state.isLoading) {
  SessionLoadingSkeleton()
  return
  }

  val session = state.session ?: return

  var showReorderSheet by remember { mutableStateOf(false) }
  if (showReorderSheet) {
    ReorderExercisesSheet(
      exercises = state.exercises,
      onMove    = { from, to -> vm.moveExercise(from, to) },
      onDismiss = { showReorderSheet = false }
    )
  }

  var supersetPickerFor by remember { mutableStateOf<Int?>(null) }
  supersetPickerFor?.let { forIdx ->
    SupersetPickerSheet(
      forIndex  = forIdx,
      exercises = state.exercises,
      onPick    = { partnerIdx -> vm.linkSuperset(forIdx, partnerIdx); supersetPickerFor = null },
      onDismiss = { supersetPickerFor = null }
    )
  }

  var showAddExerciseSheet by remember { mutableStateOf(false) }
  if (showAddExerciseSheet) {
  AddExerciseSheet(
  onAdd  = { name, sets, reps -> vm.addNewExercise(name, sets, reps); showAddExerciseSheet = false },
  onDismiss = { showAddExerciseSheet = false }
  )
  }

  var showResetConfirm by remember { mutableStateOf(false) }
  if (showResetConfirm) {
  AlertDialog(
  onDismissRequest = { showResetConfirm = false },
  title  = { Text("Reset this day?") },
  text  = { Text("Clears every logged set for ${session.name} in Week ${vm.weekNumber} and unmarks it as done. It reverts to the carried-over values from the previous week. Other weeks aren't affected.") },
  confirmButton = {
  TextButton(
  onClick = { showResetConfirm = false; vm.resetDay() },
  colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
  ) { Text("Reset day") }
  },
  dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } }
  )
  }

  if (showExitConfirm) {
  AlertDialog(
  onDismissRequest = { showExitConfirm = false },
  title  = { Text("Leave this session?") },
  text  = { Text("Your logged sets are saved, but the session isn't marked complete.") },
  confirmButton = {
  TextButton(onClick = { showExitConfirm = false; navController.popBackStack() }) { Text("Leave") }
  },
  dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text("Stay") } }
  )
  }

  // Derived values for bottom bar
  val currentPage    = pagerState.currentPage
  val currentPageIdxs = pages.getOrElse(currentPage) { emptyList() }
  val isLastExercise = currentPage == pages.size - 1
  val currentEx  = state.exercises.getOrNull(currentPageIdxs.firstOrNull() ?: -1)

  // canComplete is computed here so the button reacts to set-log state changes
  val canComplete by remember { derivedStateOf { vm.canComplete } }

  // All sets for every exercise on the current page have weight + reps filled
  val allSetsDone by remember {
  derivedStateOf {
  val idxs = pages.getOrElse(pagerState.currentPage) { emptyList() }
  idxs.isNotEmpty() && idxs.all { idx ->
  val ex = state.exercises.getOrNull(idx)
  ex != null && ex.sets.isNotEmpty() &&
  ex.sets.all { s -> s.weightKg.toFloatOrNull() != null && s.reps.toIntOrNull() != null }
  }
  }
  }

  Column(
  modifier = Modifier
  .fillMaxSize()
  .background(MaterialTheme.colorScheme.background)
  .windowInsetsPadding(WindowInsets.statusBars)
  .imePadding()
  .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
  focusManager.clearFocus()
  }
  ) {
  // ── Top bar ──────────────────────────────────────────────────────────
  Row(
  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  IconButton(onClick = { if (shouldGuardLeaving) showExitConfirm = true else navController.popBackStack() }) {
  Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
  }
  Column(modifier = Modifier.weight(1f)) {
  Text(session.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
  Text("Week ${vm.weekNumber} · ${state.exercises.size} exercises", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  }
  if (feat1rmCalc) {
  IconButton(onClick = { showGlobal1rmCalc = true }) {
  Text(
  "%",
  color    = LocalAccentColor.current,
  fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
  fontSize  = 15.sp
  )
  }
  }
  if (featTimer) {
  IconButton(onClick = { vm.toggleTimerVisible() }) {
  Icon(
  Icons.Default.Timer,
  "Toggle timer",
  tint = if (timerState.isVisible) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
  )
  }
  }
  if (state.exercises.size > 1) {
  IconButton(onClick = { showReorderSheet = true }) {
  Icon(Icons.Default.SwapVert, "Reorder exercises",
  tint = LocalAccentColor.current)
  }
  }
  if (featAddEx) {
  IconButton(onClick = { showAddExerciseSheet = true }) {
  Icon(Icons.Default.Add, "Add exercise", tint = LocalAccentColor.current)
  }
  }
  // Overflow menu (reset day, etc.)
  Box {
  var showMenu by remember { mutableStateOf(false) }
  IconButton(onClick = { showMenu = true }) {
  Icon(Icons.Default.MoreVert, "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
  val oneRmShown by vm.oneRmVisible.collectAsStateWithLifecycle()
  DropdownMenuItem(
  text = { Text(if (oneRmShown) "Hide %1RM logging" else "Log %1RM") },
  onClick = { showMenu = false; vm.toggleOneRmVisible() },
  leadingIcon = {
  Text("%", color = LocalAccentColor.current, fontWeight = FontWeight.Bold, fontSize = 16.sp)
  }
  )
  if (travelEnabled) {
  DropdownMenuItem(
  text = { Text(if (travelActive) "Exit travel mode" else "Travel mode") },
  onClick = { showMenu = false; if (travelActive) vm.exitTravelMode() else showTravelSheet = true },
  leadingIcon = { Icon(Icons.Default.Flight, null, tint = LocalAccentColor.current) }
  )
  }
  if (injuryEnabled) {
  DropdownMenuItem(
  text = { Text("Injury check") },
  onClick = { showMenu = false; showInjurySheet = true },
  leadingIcon = { Icon(Icons.Default.MedicalServices, null, tint = LocalAccentColor.current) }
  )
  }
  if (plateCalcEnabled) {
  DropdownMenuItem(
  text = { Text("Plate calculator") },
  onClick = { showMenu = false; showPlateCalc = true },
  leadingIcon = { Icon(Icons.Default.Calculate, null, tint = LocalAccentColor.current) }
  )
  }
  DropdownMenuItem(
  text = { Text("Reset day") },
  onClick = { showMenu = false; showResetConfirm = true },
  leadingIcon = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.error) }
  )
  }
  }
  }

  // ── Travel-mode banner (quiet, with one-tap exit) ────────────────────
  if (travelActive) {
  Surface(
  color = LocalAccentColor.current.copy(alpha = 0.12f),
  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
  shape = RoundedCornerShape(10.dp)
  ) {
  Row(
  Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Icon(Icons.Default.Flight, null, tint = LocalAccentColor.current, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(8.dp))
  Text("Travel Mode — translated for your kit. Your program is untouched.",
  modifier = Modifier.weight(1f),
  style = MaterialTheme.typography.labelMedium, color = LocalAccentColor.current)
  TextButton(onClick = { vm.exitTravelMode() }) { Text("Exit") }
  }
  }
  }

  // ── Injury-accommodation banner + prehab guidance (Phase 3E) ─────────
  injuryOverlay?.let { acc ->
  Surface(
  color = LocalAccentColor.current.copy(alpha = 0.12f),
  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
  shape = RoundedCornerShape(10.dp)
  ) {
  Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(Icons.Default.MedicalServices, null, tint = LocalAccentColor.current, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(8.dp))
  Text("Accommodating your ${acc.region.display.lowercase()} — ${acc.swapCount} lift(s) swapped. Program untouched.",
  modifier = Modifier.weight(1f),
  style = MaterialTheme.typography.labelMedium, color = LocalAccentColor.current)
  TextButton(onClick = { vm.revertInjuryAccommodation() }) { Text("Revert") }
  }
  if (acc.prehab.isNotEmpty()) {
  Spacer(Modifier.height(4.dp))
  Text("Prehab (do these, not logged):",
  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  acc.prehab.forEach { ph ->
  Text("• ${ph.name} — ${ph.sets} × ${ph.reps}",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
  }
  }
  }
  }
  }

  // ── Progress bar ─────────────────────────────────────────────────────
  val loggedCount = state.exercises.count { ex ->
  ex.sets.any { s -> s.weightKg.toFloatOrNull() != null && s.reps.toIntOrNull() != null }
  }
  LinearProgressIndicator(
  progress = { if (state.exercises.isNotEmpty()) loggedCount.toFloat() / state.exercises.size else 0f },
  modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(4.dp).clip(CircleShape),
  color = LocalAccentColor.current,
  trackColor = MaterialTheme.colorScheme.outline
  )

  // ── Fatigue indicator (Phase 3F) — a single calm pill + dismissible suggestion ──
  fatigue?.let { f ->
  FatiguePill(f)
  if (f.suggestion != null && !fatigueDismissed) {
  FatigueSuggestionChip(f.suggestion, onDismiss = { vm.dismissFatigueSuggestion() })
  }
  }

  // ── Pager dots + live heart-rate indicator (beside the dots) ─────────
  Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
  if (pages.size > 1) {
  Row(
  modifier = Modifier.align(Alignment.Center),
  horizontalArrangement = Arrangement.Center,
  verticalAlignment = Alignment.CenterVertically
  ) {
  pages.indices.forEach { i ->
  val isSuperset = (pages[i].size > 1)
  Box(
  modifier = Modifier
  .size(if (i == currentPage) 10.dp else 7.dp)
  .clip(if (isSuperset) RoundedCornerShape(3.dp) else CircleShape)
  .background(if (i == currentPage) LocalAccentColor.current else MaterialTheme.colorScheme.outline)
  )
  if (i < pages.size - 1) Spacer(Modifier.width(6.dp))
  }
  }
  }
  liveHeartRate?.let { bpm ->
  Row(
  modifier = Modifier.align(if (pages.size > 1) Alignment.CenterEnd else Alignment.Center)
  .padding(end = if (pages.size > 1) 20.dp else 0.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Icon(Icons.Default.Favorite, "Heart rate", tint = Color(0xFFE53935), modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(4.dp))
  Text("$bpm bpm", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onBackground)
  }
  }
  }

  // ── Exercise pager (or empty-state for flexible sessions) ───────────
  if (state.exercises.isEmpty() && vm.isFlexible) {
  Box(
  modifier = Modifier.weight(1f).fillMaxWidth(),
  contentAlignment = Alignment.Center
  ) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(
  Icons.Default.FitnessCenter,
  contentDescription = null,
  tint  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
  modifier = Modifier.size(64.dp)
  )
  Spacer(Modifier.height(16.dp))
  Text(
  "No exercises yet",
  style = MaterialTheme.typography.titleMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant
  )
  Spacer(Modifier.height(8.dp))
  Text(
  "Add your first exercise to get started",
  style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
  )
  Spacer(Modifier.height(24.dp))
  Button(
  onClick = { showAddExerciseSheet = true },
  shape  = RoundedCornerShape(12.dp),
  colors  = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)
  ) {
  Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(8.dp))
  Text("Add Exercise", fontWeight = FontWeight.SemiBold)
  }
  }
  }
  } else {
  HorizontalPager(
  state = pagerState,
  modifier = Modifier.weight(1f).fillMaxWidth()
  ) { pageIdx ->
  val idxs = pages.getOrElse(pageIdx) { emptyList() }
  if (idxs.size >= 2) {
  SupersetPage(
  primaryIdx = idxs[0], partnerIdx = idxs[1],
  exercises  = state.exercises, vm = vm, featAutofill = featAutofill,
  travelOverlay = activeOverlay, overlayMarker = overlayMarker,
  onSupersetUnlink = { vm.unlinkSuperset(idxs[0]) }
  )
  } else {
  val idx = idxs.firstOrNull() ?: return@HorizontalPager
  val exState = state.exercises.getOrNull(idx) ?: return@HorizontalPager
  val partnerName = exState.exercise.supersetGroupId?.let { gid ->
  state.exercises.firstOrNull { it.exercise.id != exState.exercise.id && it.exercise.supersetGroupId == gid }?.exercise?.name
  }
  ExerciseCard(
  exerciseState = exState, exerciseIndex = idx, vm = vm, featAutofill = featAutofill,
  supersetPartnerName = partnerName,
  travel = activeOverlay?.getOrNull(idx), overlayMarker = overlayMarker,
  onSupersetClick = {
  if (exState.exercise.supersetGroupId != null) vm.unlinkSuperset(idx)
  else supersetPickerFor = idx
  }
  )
  }
  }
  }

  // ── Timer panel (below exercise, so keyboard doesn't cover reps) ────
  if (featTimer && timerState.isVisible) {
  TimerPanel(timerState = timerState, vm = vm)
  }

  // ── "+ Add Exercise" row ─────────────────────────────────────────────
  if (featAddEx && state.exercises.isNotEmpty()) {
  TextButton(
  onClick  = { showAddExerciseSheet = true },
  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
  ) {
  Icon(Icons.Default.Add, null, tint = LocalAccentColor.current, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(6.dp))
  Text("Add Exercise", color = LocalAccentColor.current, fontWeight = FontWeight.SemiBold)
  }
  }

  // ── Bottom bar: progression + next/complete ──────────────────────────
  if (state.exercises.isNotEmpty()) {
  // The progression choice must target the exercise shown on this page — not the pager
  // page index, which diverges from the exercise index once supersets merge pages.
  val currentExIndex = currentPageIdxs.firstOrNull() ?: -1
  SessionBottomBar(
  currentExercise     = currentEx,
  isLastExercise      = isLastExercise,
  canComplete         = canComplete,
  showProgression     = featProgression && allSetsDone,
  progressionExpanded = progressionExpanded,
  onToggleProgressionExpanded = { progressionExpandedByUser = !progressionExpandedByUser },
  onChoiceSelected    = { if (currentExIndex >= 0) vm.setExerciseProgressionChoice(currentExIndex, it) },
  onNext  = {
  scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
  },
  onComplete  = {
  vm.completeSession(showSummary = summaryEnabled) { navController.popBackStack() }
  }
  )
  }
  }
}

// ── Loading skeleton (grey placeholder tiles, shown while the session loads) ──

@Composable
private fun SkeletonTile(
  color: Color,
  modifier: Modifier,
  shape: Shape = RoundedCornerShape(6.dp)
) {
  Box(modifier = modifier.clip(shape).background(color))
}

@Composable
private fun SessionLoadingSkeleton() {
  val transition = rememberInfiniteTransition(label = "skeleton")
  val alpha by transition.animateFloat(
  initialValue = 0.35f,
  targetValue  = 0.75f,
  animationSpec = infiniteRepeatable(
  animation  = tween(900, easing = LinearEasing),
  repeatMode = RepeatMode.Reverse
  ),
  label = "skeletonAlpha"
  )
  val tile = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

  Column(
  modifier = Modifier
  .fillMaxSize()
  .background(MaterialTheme.colorScheme.background)
  .windowInsetsPadding(WindowInsets.statusBars)
  .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
  // Top bar
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  SkeletonTile(tile, Modifier.size(32.dp), CircleShape)
  Spacer(Modifier.width(12.dp))
  Column(modifier = Modifier.weight(1f)) {
  SkeletonTile(tile, Modifier.fillMaxWidth(0.5f).height(18.dp))
  Spacer(Modifier.height(6.dp))
  SkeletonTile(tile, Modifier.fillMaxWidth(0.3f).height(12.dp))
  }
  Spacer(Modifier.width(12.dp))
  SkeletonTile(tile, Modifier.size(28.dp), CircleShape)
  }

  Spacer(Modifier.height(20.dp))
  SkeletonTile(tile, Modifier.fillMaxWidth().height(4.dp), CircleShape)

  Spacer(Modifier.height(24.dp))
  // Pager dots
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
  repeat(3) { i ->
  SkeletonTile(tile, Modifier.size(if (i == 0) 10.dp else 7.dp), CircleShape)
  if (i < 2) Spacer(Modifier.width(6.dp))
  }
  }

  Spacer(Modifier.height(20.dp))
  // Exercise card
  Column(
  modifier = Modifier
  .fillMaxWidth()
  .weight(1f)
  .clip(RoundedCornerShape(12.dp))
  .background(MaterialTheme.colorScheme.surface)
  .padding(16.dp)
  ) {
  SkeletonTile(tile, Modifier.fillMaxWidth(0.6f).height(26.dp))
  Spacer(Modifier.height(20.dp))
  SkeletonTile(tile, Modifier.fillMaxWidth().height(36.dp), RoundedCornerShape(10.dp))
  Spacer(Modifier.height(20.dp))
  repeat(4) {
  SkeletonTile(tile, Modifier.fillMaxWidth().height(44.dp), RoundedCornerShape(8.dp))
  Spacer(Modifier.height(8.dp))
  }
  }

  Spacer(Modifier.height(16.dp))
  SkeletonTile(tile, Modifier.fillMaxWidth().height(50.dp), RoundedCornerShape(12.dp))
  }
}

// ── Timer panel (compact bar) ─────────────────────────────────────────────────

@Composable
private fun TimerPanel(timerState: SessionViewModel.TimerState, vm: SessionViewModel) {
  val accent = LocalAccentColor.current
  fun Int.toMmSs() = "%d:%02d".format(this / 60, this % 60)
  val isRest = timerState.mode == SessionViewModel.TimerMode.REST
  val displaySeconds = timerState.currentSeconds
  val timeColor = when {
  isRest && displaySeconds <= 10 && timerState.isRunning -> MaterialTheme.colorScheme.error
  timerState.isRunning -> accent
  else -> MaterialTheme.colorScheme.onBackground
  }

  Surface(
  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
  shape  = RoundedCornerShape(12.dp),
  color  = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
  ) {
  Row(
  modifier  = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
  // Compact mode toggle
  Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
  Row(Modifier.padding(2.dp)) {
  listOf(SessionViewModel.TimerMode.REST to "Rest",
  SessionViewModel.TimerMode.STOPWATCH to "SW").forEach { (mode, label) ->
  val sel = timerState.mode == mode
  Surface(onClick = { vm.setTimerMode(mode) }, shape = RoundedCornerShape(18.dp),
  color = if (sel) accent else Color.Transparent) {
  Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
  color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelSmall,
  fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
  }
  }
  }
  }

  // Time display
  Text(displaySeconds.toMmSs(), fontSize = 26.sp, fontWeight = FontWeight.Bold,
  color = timeColor, modifier = Modifier.padding(horizontal = 4.dp))

  // REST duration adjuster
  if (isRest) {
  IconButton(onClick = { vm.setRestDuration(timerState.restDuration - 15) },
  modifier = Modifier.size(28.dp)) {
  Text("−", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent)
  }
  Text("${timerState.restDuration}s", style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  IconButton(onClick = { vm.setRestDuration(timerState.restDuration + 15) },
  modifier = Modifier.size(28.dp)) {
  Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent)
  }
  }

  Spacer(Modifier.weight(1f))

  // Play / Pause
  IconButton(onClick = { if (timerState.isRunning) vm.pauseTimer() else vm.startTimer() },
  modifier = Modifier.size(36.dp)) {
  Icon(
  if (timerState.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
  null,
  tint  = if (timerState.isRunning) MaterialTheme.colorScheme.onSurfaceVariant else accent,
  modifier = Modifier.size(20.dp)
  )
  }
  // Reset
  IconButton(onClick = { vm.resetTimer() }, modifier = Modifier.size(36.dp)) {
  Icon(Icons.Default.Refresh, null,
  tint  = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.size(18.dp))
  }
  }
  }
}

// ── Exercise card ─────────────────────────────────────────────────────────────

@Composable
private fun ExerciseCard(
  exerciseState: ExerciseUiState,
  exerciseIndex: Int,
  vm: SessionViewModel,
  featAutofill: Boolean = true,
  isInSuperset: Boolean = false,
  supersetPartnerName: String? = null,
  travel: com.wildodds.gymtracker.data.intelligence.TranslatedExercise? = null,
  overlayMarker: String = "Travel",
  onSupersetClick: (() -> Unit)? = null
) {
  val exercise  = exerciseState.exercise
  val accent  = LocalAccentColor.current
  var selectedTab  by remember(exercise.id) { mutableIntStateOf(0) }
  var isEditingName by remember(exercise.id) { mutableStateOf(false) }
  var nameFieldValue by remember(exercise.id) {
  mutableStateOf(TextFieldValue(exercise.name, TextRange(exercise.name.length)))
  }
  // User note (SharedPreferences, persists across programs)
  var userNote by remember(exercise.name) { mutableStateOf(vm.getNoteForExercise(exercise.name)) }
  var isEditingNote by remember(exercise.id) { mutableStateOf(false) }
  var noteFieldValue by remember(exercise.name) { mutableStateOf(userNote) }

  var show1rmCalc by remember(exercise.id) { mutableStateOf(false) }
  if (show1rmCalc) {
  OneRmCalculatorSheet(onDismiss = { show1rmCalc = false })
  }

  // Swap + easier/harder (Phase 3C), each gated by its registry flag.
  val swapsEnabled = FeatureFlags.isEnabled(SettingsRegistry.EXERCISE_SWAPS)
  val easierHarderEnabled = FeatureFlags.isEnabled(SettingsRegistry.EASIER_HARDER)
  var showSwapSheet by remember(exercise.id) { mutableStateOf(false) }
  var showExerciseMenu by remember(exercise.id) { mutableStateOf(false) }
  var easierHarderTarget by remember(exercise.id) { mutableStateOf<ScoredExercise?>(null) }
  var easierHarderIsEasier by remember(exercise.id) { mutableStateOf(true) }

  if (showSwapSheet) {
  SwapExerciseSheet(
  exerciseName = exercise.name,
  onSwap = { newName, future -> vm.swapExercise(exerciseIndex, newName, future) },
  onDismiss = { showSwapSheet = false }
  )
  }
  easierHarderTarget?.let { target ->
  AlertDialog(
  onDismissRequest = { easierHarderTarget = null },
  title = { Text(if (easierHarderIsEasier) "Make easier?" else "Make harder?") },
  text  = { Text("Swap to ${target.node.name}.\n${target.reason}") },
  confirmButton = {
  TextButton(onClick = {
  vm.swapExercise(exerciseIndex, target.node.name, applyToFuture = false)
  easierHarderTarget = null
  }) { Text("Swap") }
  },
  dismissButton = { TextButton(onClick = { easierHarderTarget = null }) { Text("Cancel") } }
  )
  }

  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(exercise.name) {
  if (!isEditingName) nameFieldValue = TextFieldValue(exercise.name, TextRange(exercise.name.length))
  }

  Column(
  modifier = if (isInSuperset) Modifier.fillMaxWidth().padding(horizontal = 16.dp)
         else Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
  ) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
  Column {
  // Travel-mode header (Phase 3D): the translated movement becomes the title; the
  // original is one tap away. Logging below is unchanged (it writes to the original).
  if (travel != null && travel.isSubstituted) {
  TravelExerciseHeader(travel, accent, overlayMarker)
  }
  // ── Exercise name (tap pencil to edit) ────────────────────
  if (travel == null || !travel.isSubstituted)
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  if (isEditingName) {
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  OutlinedTextField(
  value  = nameFieldValue,
  onValueChange = { nameFieldValue = it },
  modifier  = Modifier.weight(1f).focusRequester(focusRequester),
  textStyle  = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground),
  singleLine  = true,
  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
  keyboardActions = KeyboardActions(onDone = {
  vm.updateExerciseName(exerciseIndex, nameFieldValue.text)
  isEditingName = false
  }),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  IconButton(onClick = {
  vm.updateExerciseName(exerciseIndex, nameFieldValue.text)
  isEditingName = false
  }) {
  Icon(Icons.Default.Check, "Save", tint = accent)
  }
  } else {
  Text(
  exercise.name,
  style  = MaterialTheme.typography.headlineMedium,
  color  = MaterialTheme.colorScheme.onBackground,
  modifier = Modifier.weight(1f)
  )
  if (vm.trackOneRm) {
  IconButton(onClick = { show1rmCalc = true }, modifier = Modifier.size(36.dp)) {
  Icon(Icons.Default.Calculate, "1RM Calculator", tint = accent, modifier = Modifier.size(20.dp))
  }
  }
  IconButton(onClick = { isEditingName = true }) {
  Icon(Icons.Default.Edit, "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
  }
  if (swapsEnabled || easierHarderEnabled) {
  Box {
  IconButton(onClick = { showExerciseMenu = true }, modifier = Modifier.size(36.dp)) {
  Icon(Icons.Default.MoreVert, "Exercise options",
  tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
  }
  DropdownMenu(expanded = showExerciseMenu, onDismissRequest = { showExerciseMenu = false }) {
  if (swapsEnabled) {
  DropdownMenuItem(
  text = { Text("Swap exercise") },
  onClick = { showExerciseMenu = false; showSwapSheet = true },
  leadingIcon = { Icon(Icons.Default.SwapVert, null, tint = accent) }
  )
  }
  if (easierHarderEnabled) {
  DropdownMenuItem(
  text = { Text("Make easier") },
  onClick = {
  showExerciseMenu = false
  vm.easierFor(exercise.name)?.let { easierHarderIsEasier = true; easierHarderTarget = it }
  },
  leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, tint = accent) }
  )
  DropdownMenuItem(
  text = { Text("Make harder") },
  onClick = {
  showExerciseMenu = false
  vm.harderFor(exercise.name)?.let { easierHarderIsEasier = false; easierHarderTarget = it }
  },
  leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null, tint = accent) }
  )
  }
  }
  }
  }
  }
  }

  // Action row: superset · unilateral toggle · delete
  Spacer(Modifier.height(4.dp))
  Row(
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = Arrangement.spacedBy(6.dp),
  modifier = Modifier.fillMaxWidth()
  ) {
  // Superset chip
  if (onSupersetClick != null) {
  Surface(
  onClick = onSupersetClick,
  color  = if (supersetPartnerName != null) accent.copy(alpha = 0.12f)
           else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
  shape  = RoundedCornerShape(8.dp),
  modifier = Modifier.weight(1f)
  ) {
  Row(
  verticalAlignment = Alignment.CenterVertically,
  modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
  ) {
  Icon(
  if (supersetPartnerName != null) Icons.Default.LinkOff else Icons.Default.Link,
  null,
  tint = if (supersetPartnerName != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.size(14.dp)
  )
  Spacer(Modifier.width(5.dp))
  Text(
  if (supersetPartnerName != null) "SS · $supersetPartnerName" else "Superset",
  style = MaterialTheme.typography.labelSmall,
  color = if (supersetPartnerName != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
  maxLines = 1
  )
  }
  }
  }

  // Unilateral toggle chip — cycles Off → L/R reps → L/R weight + reps
  val uniMode = exercise.unilateralMode
  Surface(
  onClick = { vm.cycleUnilateralMode(exerciseIndex) },
  color  = if (uniMode != 0) accent.copy(alpha = 0.12f)
           else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
  shape  = RoundedCornerShape(8.dp)
  ) {
  Text(
  when (uniMode) {
  1 -> "L/R reps"
  2 -> "L/R wt+reps"
  else -> "L/R off"
  },
  modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
  style  = MaterialTheme.typography.labelSmall,
  color  = if (uniMode != 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
  fontWeight = if (uniMode != 0) FontWeight.Bold else FontWeight.Normal
  )
  }

  // Delete exercise
  var showDeleteConfirm by remember(exercise.id) { mutableStateOf(false) }
  IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(30.dp)) {
  Icon(Icons.Default.Delete, "Delete exercise",
  tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
  }
  if (showDeleteConfirm) {
  AlertDialog(
  onDismissRequest = { showDeleteConfirm = false },
  title  = { Text("Delete exercise?") },
  text  = { Text("\"${exercise.name}\" will be removed from this session and all other weeks of the program.") },
  confirmButton = {
  TextButton(onClick = {
  showDeleteConfirm = false
  vm.deleteExercise(exerciseIndex)
  }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
  },
  dismissButton = {
  TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
  }
  )
  }
  }

  // Program notes (from import)
  if (exercise.notes.isNotBlank()) {
  Spacer(Modifier.height(4.dp))
  Text(exercise.notes, fontStyle = FontStyle.Italic, color = accent, fontSize = 13.sp)
  }

  // Prescribed RPE / %1RM targets from the program week plan
  val rpeTarget  = exercise.rpeTarget.trim()
  val pct1rmTarget = exercise.pct1rmTarget.trim()
  if (rpeTarget.isNotBlank() || pct1rmTarget.isNotBlank()) {
  Spacer(Modifier.height(4.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  if (rpeTarget.isNotBlank()) {
  Surface(
  color = accent.copy(alpha = 0.12f),
  shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
  ) {
  Text(
  "RPE $rpeTarget",
  modifier  = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
  color  = accent,
  fontSize  = 11.sp,
  fontWeight= FontWeight.SemiBold
  )
  }
  }
  if (pct1rmTarget.isNotBlank()) {
  Surface(
  color = accent.copy(alpha = 0.12f),
  shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
  ) {
  Text(
  pct1rmTarget,
  modifier  = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
  color  = accent,
  fontSize  = 11.sp,
  fontWeight= FontWeight.SemiBold
  )
  }
  }
  }
  }

  // Previous-week progression badge
  exerciseState.prevProgressionChoice?.let {
  Spacer(Modifier.height(8.dp))
  ProgressionBadge(it)
  }

  Spacer(Modifier.height(8.dp))

  // ── Tabs: Sets | Notes ────────────────────────────────────
  TabRow(
  selectedTabIndex = selectedTab,
  containerColor  = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
  contentColor  = accent,
  divider  = {}
  ) {
  Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
  Text("Sets", modifier = Modifier.padding(vertical = 10.dp),
  fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
  style = MaterialTheme.typography.labelMedium)
  }
  Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  Text("Notes", modifier = Modifier.padding(vertical = 10.dp),
  fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
  style = MaterialTheme.typography.labelMedium)
  if (userNote.isNotBlank()) {
  Spacer(Modifier.width(4.dp))
  Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(accent))
  }
  }
  }
  }

  Spacer(Modifier.height(12.dp))

  if (selectedTab == 0) {
  // ── Sets tab ──────────────────────────────────────────
  exerciseState.sets.forEachIndexed { setIndex, setRow ->
  // key() by setNumber so swipe-dismiss state doesn't leak to the
  // next row when a set is deleted and the list shifts.
  key(setRow.setNumber) {
  SwipeToDeleteSetRow(
  setRow = setRow, setIndex = setIndex, exerciseIndex = exerciseIndex,
  vm = vm, featAutofill = featAutofill, unilateralMode = exercise.unilateralMode
  )
  }
  if (setIndex < exerciseState.sets.size - 1) Spacer(Modifier.height(8.dp))
  }

  Spacer(Modifier.height(12.dp))
  TextButton(onClick = { vm.addSet(exerciseIndex) }) {
  Text("+ Add set", color = accent)
  }

  Spacer(Modifier.height(4.dp))
  Surface(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp)) {
  Text(
  "Target: ${exercise.repsTarget} reps",
  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  color  = MaterialTheme.colorScheme.onSurfaceVariant,
  style  = MaterialTheme.typography.labelSmall
  )
  }
  } else {
  // ── Notes tab ─────────────────────────────────────────
  if (isEditingNote) {
  val noteBringIntoView = remember { BringIntoViewRequester() }
  var noteHasFocus by remember { mutableStateOf(false) }
  val noteImeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
  LaunchedEffect(noteHasFocus, noteImeBottom) {
  if (noteHasFocus) noteBringIntoView.bringIntoView()
  }
  OutlinedTextField(
  value  = noteFieldValue,
  onValueChange = { noteFieldValue = it },
  modifier  = Modifier.fillMaxWidth()
  .bringIntoViewRequester(noteBringIntoView)
  .onFocusChanged { noteHasFocus = it.isFocused },
  placeholder  = { Text("Add notes about form, cues, weight progression...") },
  minLines  = 3,
  colors  = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
  )
  Spacer(Modifier.height(8.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  Button(
  onClick = {
  userNote = noteFieldValue
  vm.saveNoteForExercise(exercise.name, noteFieldValue)
  isEditingNote = false
  },
  colors = ButtonDefaults.buttonColors(containerColor = accent),
  shape  = RoundedCornerShape(10.dp)
  ) { Text("Save") }
  TextButton(onClick = {
  noteFieldValue = userNote
  isEditingNote = false
  }) { Text("Cancel") }
  if (userNote.isNotBlank()) {
  TextButton(
  onClick = {
  userNote = ""
  noteFieldValue = ""
  vm.saveNoteForExercise(exercise.name, "")
  isEditingNote = false
  },
  colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
  ) { Text("Delete") }
  }
  }
  } else {
  if (userNote.isBlank()) {
  Text(
  "No notes yet. Tap to add notes about this exercise.",
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.bodySmall,
  fontStyle = FontStyle.Italic
  )
  } else {
  Text(userNote, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium)
  }
  Spacer(Modifier.height(8.dp))
  TextButton(onClick = { noteFieldValue = userNote; isEditingNote = true }) {
  Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = accent)
  Spacer(Modifier.width(4.dp))
  Text(if (userNote.isBlank()) "Add Note" else "Edit Note", color = accent)
  }
  }
  }
  }
  }
  Spacer(Modifier.height(16.dp))
  }
}

// ── Swipe-to-delete wrapper ───────────────────────────────────────────────────

@Composable
private fun SwipeToDeleteSetRow(
  setRow: SetRowState,
  setIndex: Int,
  exerciseIndex: Int,
  vm: SessionViewModel,
  featAutofill: Boolean = true,
  unilateralMode: Int = 0
) {
  val dismissState = rememberSwipeToDismissBoxState(
  confirmValueChange = { value ->
  if (value == SwipeToDismissBoxValue.EndToStart) {
  vm.deleteSet(exerciseIndex, setIndex)
  true
  } else false
  }
  )
  SwipeToDismissBox(
  state = dismissState,
  enableDismissFromStartToEnd = false,
  enableDismissFromEndToStart = true,
  backgroundContent = {
  // progress returns 1f when currentValue == targetValue == Settled (Compose bug),
  // so gate on actual swipe state rather than raw fraction.
  val isSwiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled
  val errorColor = MaterialTheme.colorScheme.error
  val bg = if (isSwiping) errorColor.copy(alpha = (dismissState.progress * 2f).coerceIn(0f, 1f))
  else Color.Transparent
  Box(
  modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(bg),
  contentAlignment = Alignment.CenterEnd
  ) {
  if (isSwiping) {
  Icon(
  Icons.Default.Close,
  contentDescription = "Delete set",
  tint = Color.White,
  modifier = Modifier.padding(end = 20.dp).size(22.dp)
  )
  }
  }
  }
  ) {
  SetRow(setRow = setRow, setIndex = setIndex, exerciseIndex = exerciseIndex, vm = vm, featAutofill = featAutofill, unilateralMode = unilateralMode)
  }
}

// ── Set row ───────────────────────────────────────────────────────────────────

@Composable
private fun SetRow(setRow: SetRowState, setIndex: Int, exerciseIndex: Int, vm: SessionViewModel, featAutofill: Boolean = true, unilateralMode: Int = 0) {
  val isFilled  = setRow.weightKg.toFloatOrNull() != null && setRow.reps.toIntOrNull() != null
  val isDark  = LocalDarkMode.current
  val accent  = LocalAccentColor.current
  val bgColor = when {
  isFilled && isDark  -> SetRowFilledDark
  isFilled   -> SetRowFilled
  isDark   -> MaterialTheme.colorScheme.surface
  else   -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
  }
  val textColor = if (setRow.isPrefilled) accent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onBackground
  val fontStyle = if (setRow.isPrefilled) FontStyle.Italic else FontStyle.Normal
  val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

  // Scrolls this row above the keyboard when one of its fields gains focus — and again once
  // the IME finishes animating in, since its final height isn't known at the moment of focus.
  val bringIntoViewRequester = remember { BringIntoViewRequester() }
  var rowHasFocus by remember { mutableStateOf(false) }
  val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
  LaunchedEffect(rowHasFocus, imeBottom) {
  if (rowHasFocus) bringIntoViewRequester.bringIntoView()
  }

  Column(
  modifier = Modifier
  .fillMaxWidth()
  .clip(RoundedCornerShape(8.dp))
  .background(bgColor)
  .padding(horizontal = 8.dp, vertical = 4.dp)
  .bringIntoViewRequester(bringIntoViewRequester)
  .onFocusChanged { rowHasFocus = it.hasFocus }
  .focusGroup()
  ) {
  when (unilateralMode) {
  1 -> {
  // Single weight, separate L/R reps
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Text("${setRow.setNumber}", modifier = Modifier.width(24.dp),
  color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
  OutlinedTextField(
  value  = setRow.weightKg,
  onValueChange = {
  if (setIndex == 0 && featAutofill) vm.updateSetWeight(exerciseIndex, setIndex, it)
  else vm.updateSetWeightManual(exerciseIndex, setIndex, it)
  },
  modifier  = Modifier.weight(1f),
  placeholder  = { Text("kg", style = MaterialTheme.typography.labelMedium) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  Text("x", modifier = Modifier.padding(horizontal = 6.dp),
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
  OutlinedTextField(
  value  = setRow.reps,
  onValueChange = { vm.updateSetReps(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(0.85f),
  label  = { Text("L", style = MaterialTheme.typography.labelSmall) },
  placeholder  = { Text("reps", style = MaterialTheme.typography.labelSmall) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  Spacer(Modifier.width(4.dp))
  OutlinedTextField(
  value  = setRow.repsRight,
  onValueChange = { vm.updateSetRepsRight(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(0.85f),
  label  = { Text("R", style = MaterialTheme.typography.labelSmall) },
  placeholder  = { Text("reps", style = MaterialTheme.typography.labelSmall) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  }
  }
  2 -> {
  // Separate weight AND reps per side: two stacked lines (L then R)
  Column(modifier = Modifier.fillMaxWidth()) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Text("${setRow.setNumber}", modifier = Modifier.width(24.dp),
  color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
  Text("L", modifier = Modifier.width(16.dp),
  color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
  OutlinedTextField(
  value  = setRow.weightKg,
  onValueChange = { vm.updateSetWeightManual(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(1f),
  placeholder  = { Text("kg", style = MaterialTheme.typography.labelMedium) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  Text("x", modifier = Modifier.padding(horizontal = 6.dp),
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
  OutlinedTextField(
  value  = setRow.reps,
  onValueChange = { vm.updateSetReps(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(1f),
  placeholder  = { Text("reps", style = MaterialTheme.typography.labelMedium) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  }
  Spacer(Modifier.height(4.dp))
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Spacer(Modifier.width(24.dp))
  Text("R", modifier = Modifier.width(16.dp),
  color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
  OutlinedTextField(
  value  = setRow.weightRight,
  onValueChange = { vm.updateSetWeightRight(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(1f),
  placeholder  = { Text("kg", style = MaterialTheme.typography.labelMedium) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  Text("x", modifier = Modifier.padding(horizontal = 6.dp),
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
  OutlinedTextField(
  value  = setRow.repsRight,
  onValueChange = { vm.updateSetRepsRight(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(1f),
  placeholder  = { Text("reps", style = MaterialTheme.typography.labelMedium) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  }
  }
  }
  else -> {
  val showOneRm by vm.oneRmVisible.collectAsStateWithLifecycle()
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Text("${setRow.setNumber}", modifier = Modifier.width(24.dp),
  color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
  if (showOneRm) {
  OutlinedTextField(
  value  = setRow.pct1rm,
  onValueChange = { vm.updateSetPct1rm(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(0.7f),
  placeholder  = { Text("%", style = MaterialTheme.typography.labelSmall) },
  label  = { Text("%1RM", style = MaterialTheme.typography.labelSmall) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodySmall.copy(color = textColor),
  colors  = fieldColors
  )
  Spacer(Modifier.width(4.dp))
  }
  OutlinedTextField(
  value  = setRow.weightKg,
  onValueChange = {
  if (setIndex == 0 && featAutofill) vm.updateSetWeight(exerciseIndex, setIndex, it)
  else vm.updateSetWeightManual(exerciseIndex, setIndex, it)
  },
  modifier  = Modifier.weight(1f),
  placeholder  = { Text("kg", style = MaterialTheme.typography.labelMedium) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  Text("x", modifier = Modifier.padding(horizontal = 6.dp),
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
  OutlinedTextField(
  value  = setRow.reps,
  onValueChange = { vm.updateSetReps(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(1f),
  placeholder  = { Text("reps", style = MaterialTheme.typography.labelMedium) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number,
  imeAction = if (vm.trackRpe) ImeAction.Next else ImeAction.Done),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontStyle = fontStyle),
  colors  = fieldColors
  )
  if (vm.trackRpe) {
  Spacer(Modifier.width(4.dp))
  OutlinedTextField(
  value  = setRow.rpe,
  onValueChange = { vm.updateSetRpe(exerciseIndex, setIndex, it) },
  modifier  = Modifier.weight(0.7f),
  placeholder  = { Text("RPE", style = MaterialTheme.typography.labelSmall) },
  label  = { Text("RPE", style = MaterialTheme.typography.labelSmall) },
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
  singleLine  = true,
  textStyle  = MaterialTheme.typography.bodySmall.copy(color = textColor),
  colors  = fieldColors
  )
  }
  }
  }
  }
  }
}

// ── 1RM Calculator sheet ──────────────────────────────────────────────────────

@Composable
private fun OneRmCalculatorSheet(onDismiss: () -> Unit) {
  val accent  = LocalAccentColor.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var weight by remember { mutableStateOf("") }
  var reps  by remember { mutableStateOf("") }

  val oneRm = remember(weight, reps) {
  val w = weight.toFloatOrNull()
  val r = reps.toIntOrNull()
  if (w != null && r != null && r > 0) w * (1f + r / 30f) else null
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
  Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 48.dp)) {
  Text("1RM Calculator", fontWeight = FontWeight.Bold, fontSize = 18.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Text("Epley formula: weight x (1 + reps/30)",
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(20.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
  OutlinedTextField(
  value  = weight,
  onValueChange = { weight = it },
  modifier  = Modifier.weight(1f),
  label  = { Text("Weight (kg)") },
  singleLine  = true,
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  OutlinedTextField(
  value  = reps,
  onValueChange = { reps = it },
  modifier  = Modifier.weight(1f),
  label  = { Text("Reps") },
  singleLine  = true,
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  }
  if (oneRm != null) {
  Spacer(Modifier.height(20.dp))
  Surface(color = accent.copy(alpha = 0.1f), shape = RoundedCornerShape(14.dp),
  modifier = Modifier.fillMaxWidth()) {
  Column(modifier = Modifier.padding(16.dp)) {
  Text("Estimated 1RM", style = MaterialTheme.typography.labelMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text("%.1f kg".format(oneRm), fontWeight = FontWeight.Bold, fontSize = 28.sp, color = accent)
  Spacer(Modifier.height(8.dp))
  val pcts = listOf(90f, 85f, 80f, 75f, 70f, 65f, 60f)
  pcts.forEach { pct ->
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
  Text("${pct.toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelSmall)
  Text("%.1f kg".format(oneRm * pct / 100f), color = MaterialTheme.colorScheme.onBackground,
  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
  }
  }
  }
  }
  }
  }
  }
}

// ── Bottom bar ────────────────────────────────────────────────────────────────

private val PROGRESSION_CHOICES = listOf(
  "MORE_SETS"  to "More sets",
  "MORE_WEIGHT" to "More weight",
  "MORE_REPS"  to "More reps",
  "BETTER_FORM" to "Better form",
  "NO_CHANGE"  to "No change"
)

@Composable
private fun SessionBottomBar(
  currentExercise:  ExerciseUiState?,
  isLastExercise:  Boolean,
  canComplete:  Boolean,
  showProgression:  Boolean,
  progressionExpanded: Boolean,
  onToggleProgressionExpanded: () -> Unit,
  onChoiceSelected: (String) -> Unit,
  onNext:  () -> Unit,
  onComplete:  () -> Unit
) {
  Surface(
  modifier  = Modifier.fillMaxWidth(),
  color  = MaterialTheme.colorScheme.surface,
  shadowElevation = 8.dp,
  shape  = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
  ) {
  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
  // Per-exercise progression section — only when all sets are done and feature is on.
  // Collapsible (and auto-collapsed while the keyboard is open) so it doesn't
  // permanently occupy space above the Next/Complete button.
  if (showProgression) {
  Row(
  verticalAlignment = Alignment.CenterVertically,
  modifier = Modifier.fillMaxWidth().clickable(
  indication = null,
  interactionSource = remember { MutableInteractionSource() },
  onClick = onToggleProgressionExpanded
  )
  ) {
  Text(
  "How should you progress this exercise next week?",
  fontWeight = FontWeight.SemiBold,
  color  = MaterialTheme.colorScheme.onSurface,
  fontSize  = 13.sp,
  modifier = Modifier.weight(1f)
  )
  if (!progressionExpanded) {
  val selectedLabel = PROGRESSION_CHOICES.firstOrNull { it.first == currentExercise?.currentProgressionChoice }?.second
  if (selectedLabel != null) {
  Text(
  selectedLabel,
  color = LocalAccentColor.current,
  style = MaterialTheme.typography.labelSmall,
  fontWeight = FontWeight.SemiBold
  )
  Spacer(Modifier.width(6.dp))
  }
  }
  Icon(
  if (progressionExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
  contentDescription = if (progressionExpanded) "Minimize" else "Expand",
  tint = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.size(20.dp)
  )
  }

  if (progressionExpanded) {
  // Smart-progression suggestion (Phase 3B) — a subtle chip, only when the engine has one.
  // The suggestion fields are populated by the VM only while the feature is enabled.
  val suggestedKey = currentExercise?.suggestedProgressionKey
  currentExercise?.suggestionRationale?.let { rationale ->
  Spacer(Modifier.height(8.dp))
  Surface(
  color = LocalAccentColor.current.copy(alpha = 0.10f),
  shape = RoundedCornerShape(10.dp)
  ) {
  Text(
  rationale,
  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
  color = LocalAccentColor.current,
  style = MaterialTheme.typography.labelSmall
  )
  }
  }

  Spacer(Modifier.height(8.dp))
  FlowRow(
  horizontalArrangement = Arrangement.spacedBy(8.dp),
  verticalArrangement  = Arrangement.spacedBy(8.dp)
  ) {
  PROGRESSION_CHOICES.forEach { (key, label) ->
  val isSelected = currentExercise?.currentProgressionChoice == key
  val isSuggested = !isSelected && key == suggestedKey
  Surface(
  onClick = { onChoiceSelected(key) },
  color  = if (isSelected) LocalAccentColor.current else Color.Transparent,
  shape  = RoundedCornerShape(20.dp),
  border  = BorderStroke(
  if (isSuggested) 1.5.dp else 1.dp,
  when {
  isSelected -> LocalAccentColor.current
  isSuggested -> LocalAccentColor.current
  else -> MaterialTheme.colorScheme.outline
  }
  )
  ) {
  Text(
  if (isSuggested) "$label •" else label,
  modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
  color  = when {
  isSelected -> Color.White
  isSuggested -> LocalAccentColor.current
  else -> MaterialTheme.colorScheme.onSurface
  },
  style  = MaterialTheme.typography.labelMedium
  )
  }
  }
  }
  }
  Spacer(Modifier.height(10.dp))
  }

  // Navigation / complete button
  if (isLastExercise) {
  Button(
  onClick  = onComplete,
  enabled  = canComplete,
  modifier = Modifier.fillMaxWidth().height(50.dp),
  shape  = RoundedCornerShape(12.dp),
  colors  = ButtonDefaults.buttonColors(
  containerColor  = LocalAccentColor.current,
  disabledContainerColor = MaterialTheme.colorScheme.outline
  )
  ) {
  Text("Complete Session ✓", fontWeight = FontWeight.Bold, fontSize = 16.sp)
  }
  } else {
  Button(
  onClick  = onNext,
  modifier = Modifier.fillMaxWidth().height(50.dp),
  shape  = RoundedCornerShape(12.dp),
  colors  = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)
  ) {
  Text("Next Exercise →", fontWeight = FontWeight.Bold, fontSize = 16.sp)
  }
  }
  Spacer(Modifier.height(4.dp))
  }
  }
}

// ── Post-session summary sheet ──────────────────────────────────────────────────

private val STRAIN_LABELS = listOf(
  1 to "Easy", 2 to "Light", 3 to "Moderate", 4 to "Hard", 5 to "Maximal"
)

@Composable
private fun SessionSummarySheet(
  data: SessionSummaryData,
  showHeartRate: Boolean,
  onDone: (strain: Int?) -> Unit
) {
  val accent = LocalAccentColor.current
  var strain by remember { mutableStateOf(data.strainRating) }

  ModalBottomSheet(onDismissRequest = { onDone(strain) }) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
  Text("Session complete", style = MaterialTheme.typography.titleLarge,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(16.dp))

  // Headline stats.
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
  SummaryStat("Time", formatDuration(data.durationSeconds), accent, Modifier.weight(1f))
  SummaryStat("Sets", data.totalSets.toString(), accent, Modifier.weight(1f))
  SummaryStat("Volume", "${data.totalVolumeKg.toInt()} kg", accent, Modifier.weight(1f))
  }

  // Real-life equivalent for the heaviest lift (fun fact).
  val heaviestName = data.heaviestLiftName
  val heaviestW = data.heaviestLiftWeightKg
  val heaviestR = data.heaviestLiftReps
  if (heaviestName != null && heaviestW != null && heaviestR != null) {
  Spacer(Modifier.height(16.dp))
  Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.10f),
  modifier = Modifier.fillMaxWidth()) {
  Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
  Icon(Icons.Default.FitnessCenter, null, tint = accent, modifier = Modifier.size(20.dp))
  Spacer(Modifier.width(10.dp))
  Text(
  com.wildodds.gymtracker.data.profile.WeightEquivalents.summaryLine(heaviestName, heaviestW, heaviestR),
  style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground
  )
  }
  }
  }

  // Achievements unlocked by this session.
  if (data.unlockedAchievements.isNotEmpty()) {
  Spacer(Modifier.height(16.dp))
  Text("Achievement${if (data.unlockedAchievements.size != 1) "s" else ""} unlocked",
  style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
  Spacer(Modifier.height(6.dp))
  data.unlockedAchievements.forEach { title ->
  Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.12f),
  modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
  Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
  Icon(Icons.Default.EmojiEvents, null, tint = accent, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(10.dp))
  Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onBackground)
  }
  }
  }
  }

  // Heart rate — only when a provider returned data AND the setting is on. No empty placeholder.
  if (showHeartRate && data.hasHeartRate) {
  Spacer(Modifier.height(20.dp))
  Text("Heart rate", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
  Spacer(Modifier.height(6.dp))
  Text("avg ${data.avgHeartRate} · peak ${data.peakHeartRate} bpm",
  style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
  if (data.hrSeries.size >= 2) {
  Spacer(Modifier.height(8.dp))
  HrLineChart(data.hrSeries, accent,
  Modifier.fillMaxWidth().height(72.dp))
  }
  }

  Spacer(Modifier.height(24.dp))
  Text("How hard was that?", style = MaterialTheme.typography.titleMedium,
  fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  STRAIN_LABELS.forEach { (value, label) ->
  val selected = strain == value
  Surface(
  onClick = { strain = if (selected) null else value },
  shape = RoundedCornerShape(14.dp),
  color = if (selected) accent else accent.copy(alpha = 0.10f),
  modifier = Modifier.weight(1f).height(64.dp)
  ) {
  Column(
  Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
  verticalArrangement = Arrangement.Center
  ) {
  Text("$value", fontWeight = FontWeight.Bold, fontSize = 18.sp,
  color = if (selected) Color.White else accent)
  Text(label, style = MaterialTheme.typography.labelSmall,
  color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  }
  }

  Spacer(Modifier.height(24.dp))
  Button(
  onClick = { onDone(strain) },
  modifier = Modifier.fillMaxWidth().height(50.dp),
  shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent)
  ) { Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
  }
  }
}

@Composable
private fun SummaryStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
  Surface(
  modifier = modifier, shape = RoundedCornerShape(14.dp),
  color = accent.copy(alpha = 0.10f)
  ) {
  Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
  Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground)
  Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
}

/** Minimal Compose-Canvas line chart for the HR series (only drawn when there's real data). */
@Composable
private fun HrLineChart(series: List<Int>, color: Color, modifier: Modifier = Modifier) {
  val minV = (series.min()).toFloat()
  val maxV = (series.max()).toFloat()
  val range = (maxV - minV).coerceAtLeast(1f)
  androidx.compose.foundation.Canvas(modifier) {
  val w = size.width
  val h = size.height
  val stepX = if (series.size > 1) w / (series.size - 1) else w
  val path = androidx.compose.ui.graphics.Path()
  series.forEachIndexed { i, v ->
  val x = stepX * i
  val y = h - ((v - minV) / range) * h
  if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
  }
  drawPath(
  path = path, color = color,
  style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
  )
  }
}

// ── Fatigue indicator (Phase 3F) ────────────────────────────────────────────────

@Composable
private fun FatiguePill(f: FatigueResult) {
  val accent = LocalAccentColor.current
  val (label, dot) = when (f.level) {
  FatigueLevel.FRESH -> "Fresh" to Color(0xFF2E7D32)
  FatigueLevel.WORKING -> "Working" to accent
  FatigueLevel.FATIGUED -> "Fatigued" to Color(0xFFE08A00)
  }
  Row(
  Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center,
  verticalAlignment = Alignment.CenterVertically
  ) {
  Surface(color = dot.copy(alpha = 0.12f), shape = RoundedCornerShape(20.dp)) {
  Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
  Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
  Spacer(Modifier.width(6.dp))
  Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = dot)
  }
  }
  }
}

@Composable
private fun FatigueSuggestionChip(text: String, onDismiss: () -> Unit) {
  Surface(
  color = MaterialTheme.colorScheme.surfaceVariant,
  shape = RoundedCornerShape(10.dp),
  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
  ) {
  Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
  Text(text, modifier = Modifier.weight(1f),
  style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
  Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
  }
  }
  }
}

// ── Travel mode (Phase 3D) ──────────────────────────────────────────────────────

@Composable
private fun TravelExerciseHeader(travel: TranslatedExercise, accent: Color, marker: String = "Travel") {
  var showOriginal by remember(travel.name) { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth()) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  Text(travel.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
  Surface(color = accent.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
  Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
  Icon(Icons.Default.Flight, null, tint = accent, modifier = Modifier.size(12.dp))
  Spacer(Modifier.width(4.dp))
  Text(marker, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
  }
  }
  }
  Text("${travel.sets} × ${travel.repsTarget}" + if (travel.adjusted) "  · adjusted" else "",
  style = MaterialTheme.typography.labelMedium, color = accent)
  Text(travel.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  TextButton(onClick = { showOriginal = !showOriginal }, contentPadding = PaddingValues(0.dp)) {
  Text(if (showOriginal) "Hide original" else "View original", style = MaterialTheme.typography.labelSmall)
  }
  if (showOriginal) {
  Text("Originally: ${travel.original.name} · ${travel.original.sets} × ${travel.original.repsTarget}",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Spacer(Modifier.height(4.dp))
  }
}

@Composable
private fun TravelModeSheet(
  initial: Set<TravelEquipment>,
  onStart: (Set<TravelEquipment>) -> Unit,
  onDismiss: () -> Unit
) {
  var selected by remember { mutableStateOf(initial) }
  val labels = listOf(
  TravelEquipment.BANDS to "Resistance bands",
  TravelEquipment.DUMBBELLS to "A pair of dumbbells",
  TravelEquipment.HOTEL_GYM to "Hotel gym"
  )
  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
  Text("Travel Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(6.dp))
  Text("Translate this session for what you've got — you always have bodyweight.",
  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(16.dp))
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  labels.forEach { (eq, label) ->
  FilterChip(
  selected = eq in selected,
  onClick = { selected = if (eq in selected) selected - eq else selected + eq },
  label = { Text(label) }
  )
  }
  }
  Spacer(Modifier.height(20.dp))
  Button(
  onClick = { onStart(selected) },
  modifier = Modifier.fillMaxWidth().height(50.dp),
  shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)
  ) { Text("Start travel session", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
  }
  }
}

// ── Add Exercise sheet ────────────────────────────────────────────────────────

@Composable
private fun AddExerciseSheet(
  onAdd:  (name: String, sets: Int, reps: String) -> Unit,
  onDismiss: () -> Unit
) {
  var name by remember { mutableStateOf("") }
  var sets by remember { mutableIntStateOf(3) }
  var reps by remember { mutableStateOf("8-12") }

  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(modifier = Modifier.imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
  Text("Add Exercise", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(20.dp))

  OutlinedTextField(
  value  = name,
  onValueChange = { name = it },
  label  = { Text("Exercise name") },
  modifier  = Modifier.fillMaxWidth(),
  singleLine  = true,
  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )
  Spacer(Modifier.height(16.dp))

  // Sets stepper
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Text("Sets", style = MaterialTheme.typography.bodyLarge,
  color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
  IconButton(onClick = { if (sets > 1) sets-- }) {
  Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold,
  color = if (sets > 1) LocalAccentColor.current else MaterialTheme.colorScheme.outline)
  }
  Text("$sets", style = MaterialTheme.typography.titleMedium,
  color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.width(32.dp),
  textAlign = androidx.compose.ui.text.style.TextAlign.Center)
  IconButton(onClick = { if (sets < 20) sets++ }) {
  Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LocalAccentColor.current)
  }
  }
  Spacer(Modifier.height(12.dp))

  OutlinedTextField(
  value  = reps,
  onValueChange = { reps = it },
  label  = { Text("Target reps (e.g. 8-12)") },
  modifier  = Modifier.fillMaxWidth(),
  singleLine  = true,
  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )
  Spacer(Modifier.height(24.dp))

  Button(
  onClick  = { if (name.isNotBlank()) onAdd(name, sets, reps) },
  enabled  = name.isNotBlank(),
  modifier = Modifier.fillMaxWidth().height(50.dp),
  shape  = RoundedCornerShape(12.dp),
  colors  = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)
  ) {
  Text("Add Exercise", fontWeight = FontWeight.Bold, fontSize = 16.sp)
  }
  }
  }
}

// ── Reorder exercises sheet ───────────────────────────────────────────────────

@Composable
private fun ReorderExercisesSheet(
  exercises: List<ExerciseUiState>,
  onMove:    (fromIndex: Int, toIndex: Int) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(
  modifier = Modifier
  .fillMaxWidth()
  .padding(horizontal = 20.dp)
  .padding(bottom = 40.dp)
  ) {
  Text(
  "Reorder Exercises",
  fontWeight = FontWeight.Bold,
  fontSize  = 18.sp,
  color  = MaterialTheme.colorScheme.onBackground
  )
  Text(
  "Use the arrows to change exercise order",
  style  = MaterialTheme.typography.bodySmall,
  color  = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
  )
  exercises.forEachIndexed { index, ex ->
  Row(
  modifier = Modifier
  .fillMaxWidth()
  .padding(vertical = 4.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Surface(
  color    = accent,
  shape    = CircleShape,
  modifier = Modifier.size(28.dp)
  ) {
  Box(contentAlignment = Alignment.Center) {
  Text(
  "${index + 1}",
  color  = Color.White,
  fontWeight = FontWeight.Bold,
  fontSize  = 12.sp
  )
  }
  }
  Spacer(Modifier.width(12.dp))
  Text(
  ex.exercise.name,
  modifier  = Modifier.weight(1f),
  fontWeight = FontWeight.Medium,
  fontSize  = 14.sp,
  color  = MaterialTheme.colorScheme.onBackground
  )
  IconButton(
  onClick  = { onMove(index, index - 1) },
  enabled  = index > 0,
  modifier = Modifier.size(36.dp)
  ) {
  Icon(
  Icons.Default.KeyboardArrowUp,
  contentDescription = "Move up",
  tint  = if (index > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f),
  modifier = Modifier.size(22.dp)
  )
  }
  IconButton(
  onClick  = { onMove(index, index + 1) },
  enabled  = index < exercises.size - 1,
  modifier = Modifier.size(36.dp)
  ) {
  Icon(
  Icons.Default.KeyboardArrowDown,
  contentDescription = "Move down",
  tint  = if (index < exercises.size - 1) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f),
  modifier = Modifier.size(22.dp)
  )
  }
  }
  if (index < exercises.size - 1) {
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.15f))
  }
  }
  }
  }
}

// ── Superset page (two stacked exercise cards) ────────────────────────────────

@Composable
private fun SupersetPage(
  primaryIdx: Int,
  partnerIdx: Int,
  exercises:  List<ExerciseUiState>,
  vm:     SessionViewModel,
  featAutofill: Boolean,
  travelOverlay: List<com.wildodds.gymtracker.data.intelligence.TranslatedExercise>? = null,
  overlayMarker: String = "Travel",
  onSupersetUnlink: () -> Unit
) {
  val accent = LocalAccentColor.current
  val primary = exercises.getOrNull(primaryIdx) ?: return
  val partner = exercises.getOrNull(partnerIdx) ?: return

  Column(
  modifier = Modifier
  .fillMaxSize()
  .verticalScroll(rememberScrollState())
  ) {
  ExerciseCard(
  exerciseState = primary,
  exerciseIndex = primaryIdx,
  vm      = vm,
  featAutofill  = featAutofill,
  isInSuperset  = true,
  supersetPartnerName = partner.exercise.name,
  travel  = travelOverlay?.getOrNull(primaryIdx), overlayMarker = overlayMarker,
  onSupersetClick   = onSupersetUnlink
  )

  // Superset divider label
  Row(
  modifier  = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  HorizontalDivider(modifier = Modifier.weight(1f), color = accent.copy(alpha = 0.4f))
  Surface(
  color  = accent.copy(alpha = 0.15f),
  shape  = RoundedCornerShape(20.dp),
  modifier = Modifier.padding(horizontal = 8.dp)
  ) {
  Row(
  modifier  = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
  Icon(Icons.Default.Link, null, tint = accent, modifier = Modifier.size(12.dp))
  Text("SUPERSET", style = MaterialTheme.typography.labelSmall,
  color = accent, fontWeight = FontWeight.Bold)
  }
  }
  HorizontalDivider(modifier = Modifier.weight(1f), color = accent.copy(alpha = 0.4f))
  }

  ExerciseCard(
  exerciseState = partner,
  exerciseIndex = partnerIdx,
  vm      = vm,
  featAutofill  = featAutofill,
  isInSuperset  = true,
  supersetPartnerName = primary.exercise.name,
  travel  = travelOverlay?.getOrNull(partnerIdx), overlayMarker = overlayMarker,
  onSupersetClick   = onSupersetUnlink
  )

  Spacer(Modifier.height(16.dp))
  }
}

// ── Superset picker sheet ─────────────────────────────────────────────────────

@Composable
private fun SupersetPickerSheet(
  forIndex:  Int,
  exercises:  List<ExerciseUiState>,
  onPick:  (partnerIndex: Int) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  val forEx  = exercises.getOrNull(forIndex)
  val eligible = exercises.mapIndexedNotNull { idx, ex ->
  if (idx != forIndex && ex.exercise.supersetGroupId == null) idx to ex
  else null
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(
  modifier = Modifier
  .fillMaxWidth()
  .padding(horizontal = 20.dp)
  .padding(bottom = 40.dp)
  ) {
  Text(
  "Superset with…",
  fontWeight = FontWeight.Bold,
  fontSize  = 18.sp,
  color  = MaterialTheme.colorScheme.onBackground
  )
  if (forEx != null) {
  Text(
  "Pairing with: ${forEx.exercise.name}",
  style  = MaterialTheme.typography.bodySmall,
  color  = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
  )
  } else {
  Spacer(Modifier.height(16.dp))
  }

  if (eligible.isEmpty()) {
  Text(
  "No available exercises to pair with.",
  style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant
  )
  } else {
  eligible.forEachIndexed { i, (idx, ex) ->
  Row(
  modifier = Modifier
  .fillMaxWidth()
  .clip(RoundedCornerShape(10.dp))
  .clickable { onPick(idx) }
  .padding(vertical = 12.dp, horizontal = 8.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Surface(
  color  = accent.copy(alpha = 0.12f),
  shape  = CircleShape,
  modifier = Modifier.size(32.dp)
  ) {
  Box(contentAlignment = Alignment.Center) {
  Text(
  "${idx + 1}",
  color  = accent,
  fontWeight = FontWeight.Bold,
  fontSize  = 13.sp
  )
  }
  }
  Spacer(Modifier.width(12.dp))
  Text(
  ex.exercise.name,
  modifier  = Modifier.weight(1f),
  fontWeight = FontWeight.Medium,
  fontSize  = 15.sp,
  color  = MaterialTheme.colorScheme.onBackground
  )
  Icon(Icons.Default.Link, null,
  tint = accent.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
  }
  if (i < eligible.size - 1) {
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.12f))
  }
  }
  }
  }
  }
}

// ── Superset page builder ─────────────────────────────────────────────────────

fun buildSupersetPages(exercises: List<ExerciseUiState>): List<List<Int>> {
  val pages = mutableListOf<List<Int>>()
  val seen  = mutableSetOf<Int>()
  exercises.forEachIndexed { idx, ex ->
  if (idx in seen) return@forEachIndexed
  val gid = ex.exercise.supersetGroupId
  if (gid != null) {
  val partnerIdx = exercises.indexOfFirst { it != ex && it.exercise.supersetGroupId == gid }
  if (partnerIdx >= 0 && partnerIdx !in seen) {
  pages.add(listOf(idx, partnerIdx))
  seen.add(idx)
  seen.add(partnerIdx)
  return@forEachIndexed
  }
  }
  pages.add(listOf(idx))
  seen.add(idx)
  }
  return pages
}
