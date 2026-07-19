@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
package com.wildodds.gymtracker.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.smart.ParseWarning
import com.wildodds.gymtracker.data.parser.smart.SmartParsedProgram
import com.wildodds.gymtracker.ui.components.AppDivider
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.components.MonoCheckbox
import com.wildodds.gymtracker.data.intelligence.RecalibrationTrigger
import com.wildodds.gymtracker.ui.settings.FeatureFlags
import com.wildodds.gymtracker.ui.session.InjuryAccommodationStore
import com.wildodds.gymtracker.ui.session.InjuryTriageSheet
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import com.wildodds.gymtracker.ui.settings.SettingsViewModel
import com.wildodds.gymtracker.ui.theme.*

@Composable
fun HomeScreen(
  navController: NavController,
  vm: HomeViewModel = viewModel(),
  settingsVm: SettingsViewModel = viewModel(),
  achievementsVm: com.wildodds.gymtracker.ui.achievements.AchievementsViewModel = viewModel(),
  insightsVm: ActivityInsightsViewModel = viewModel()
) {
  val program           by vm.program.collectAsStateWithLifecycle()
  val weekSections      by vm.weekSections.collectAsStateWithLifecycle()
  val exercisesBySession by vm.exercisesBySession.collectAsStateWithLifecycle()
  val phases            by vm.phases.collectAsStateWithLifecycle()
  val programStats      by vm.programStats.collectAsStateWithLifecycle()
  val restarting        by vm.restarting.collectAsStateWithLifecycle()
  val userPrograms      by vm.userPrograms.collectAsStateWithLifecycle()
  val favourites        by vm.favourites.collectAsStateWithLifecycle()
  val defaultPrograms   by vm.defaultPrograms.collectAsStateWithLifecycle()
  val sameSplitBlocks   by vm.sameSplitBlocks.collectAsStateWithLifecycle()
  val snackbarHost      = remember { SnackbarHostState() }
  val isDark            = LocalDarkMode.current
  val toggleDark        = LocalToggleDarkMode.current
  val c                 = LocalAppColors.current

  var showError            by remember { mutableStateOf(false) }
  var errorMessage         by remember { mutableStateOf("") }
  var showDefaultSheet     by remember { mutableStateOf(false) }
  var showRestartDialog    by remember { mutableStateOf(false) }
  var showNewBlockSheet    by remember { mutableStateOf(false) }
  var showCompletionDialog by remember { mutableStateOf(false) }
  var showCancelDialog     by remember { mutableStateOf(false) }
  var showManageDays       by remember { mutableStateOf(false) }
  // Per-week expand/collapse override (weekNumber -> expanded). Absent = default
  // (current week expanded, other weeks collapsed).
  val collapsedWeeks = remember { mutableStateMapOf<Int, Boolean>() }
  // Per-day expand/collapse override (sessionId -> expanded). Absent = default
  // (the next-up day of the current week is expanded; all others collapsed).
  val dayExpanded = remember { mutableStateMapOf<Long, Boolean>() }

  // Goal re-calibration (Phase 3B): the prompt fires on program completion OR a multi-week stall,
  // and only when the feature is on. OFF → no re-calibration prompts at all.
  val recalibrationEnabled = FeatureFlags.isEnabled(SettingsRegistry.GOAL_RECALIBRATION)
  val injuryEnabled = FeatureFlags.isEnabled(SettingsRegistry.INJURY_TRIAGE)
  val injuryContext = androidx.compose.ui.platform.LocalContext.current
  var showInjurySheet by remember { mutableStateOf(false) }
  if (showInjurySheet) {
    InjuryTriageSheet(
      onApply = { region, tags -> InjuryAccommodationStore.save(injuryContext, region, tags, System.currentTimeMillis()) },
      onDismiss = { showInjurySheet = false }
    )
  }
  val recalTrigger by vm.recalibrationTrigger.collectAsStateWithLifecycle()
  LaunchedEffect(recalTrigger, recalibrationEnabled) {
    if (recalibrationEnabled && recalTrigger != RecalibrationTrigger.NONE) showCompletionDialog = true
  }

  // Achievements / streak (Phase 6A).
  val achievementsEnabled = FeatureFlags.isEnabled(SettingsRegistry.ACHIEVEMENTS)
  val achievementsState by achievementsVm.state.collectAsStateWithLifecycle()
  val justUnlocked by achievementsVm.justUnlocked.collectAsStateWithLifecycle()

  // Activity insights (Phase 7A) — the user's own quiet dropout-risk card. Local-only, never social.
  val insightsEnabled = FeatureFlags.isEnabled(SettingsRegistry.SHOW_INSIGHTS)
  val insightsAssessment by insightsVm.assessment.collectAsStateWithLifecycle()
  val insightsDismissed by insightsVm.dismissed.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { insightsVm.refresh() }
  LaunchedEffect(Unit) { achievementsVm.evaluate() }
  LaunchedEffect(justUnlocked) {
    justUnlocked?.let {
      snackbarHost.showSnackbar("Achievement unlocked — ${it.definition.title}")
      achievementsVm.clearCelebration()
    }
  }

  if (showError) {
    AlertDialog(onDismissRequest = { showError = false },
      title = { Text("Import Error") }, text = { Text(errorMessage) },
      confirmButton = { TextButton(onClick = { showError = false }) { Text("OK") } })
  }

  if (showRestartDialog) {
    AlertDialog(
      onDismissRequest = { showRestartDialog = false },
      title  = { Text("Restart Block?") },
      text   = {
        Text(
          if (sameSplitBlocks.isNotEmpty())
            "This clears all logged sets and progress, starting this block fresh from Week 1. " +
            "Or start a new block with the same ${program?.split.orEmpty()} split."
          else
            "This clears all logged sets and progress. The block structure and exercises stay - you start from Week 1 fresh."
        )
      },
      confirmButton = {
        TextButton(
          onClick = { showRestartDialog = false; vm.restartProgram() },
          colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("Restart") }
      },
      dismissButton = {
        Column {
          if (sameSplitBlocks.isNotEmpty()) {
            TextButton(onClick = { showRestartDialog = false; showNewBlockSheet = true }) {
              Text("Start a new block", color = LocalAccentColor.current, fontWeight = FontWeight.SemiBold)
            }
          }
          TextButton(onClick = { showRestartDialog = false }) { Text("Cancel") }
        }
      }
    )
  }

  if (showNewBlockSheet) {
    StartNewBlockSheet(
      split    = program?.split.orEmpty(),
      blocks   = sameSplitBlocks,
      onStart  = { block -> showNewBlockSheet = false; vm.loadDefaultProgram(block) },
      onDismiss = { showNewBlockSheet = false }
    )
  }

  if (showManageDays) {
    val days = weekSections.firstOrNull()?.sessions?.map { it.session } ?: emptyList()
    ManageDaysSheet(
      days = days,
      onAdd = { name -> vm.addDay(name) },
      onRename = { dayNumber, name -> vm.renameDay(dayNumber, name) },
      onRemove = { dayNumber -> vm.removeDay(dayNumber) },
      onDismiss = { showManageDays = false }
    )
  }

  if (showCancelDialog) {
    AlertDialog(
      onDismissRequest = { showCancelDialog = false },
      title  = { Text("Cancel Program?") },
      text   = { Text("This will exit the current program. Your custom programs stay saved; default programs are removed.") },
      confirmButton = {
        TextButton(
          onClick = { showCancelDialog = false; vm.cancelProgram() },
          colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("Cancel Program") }
      },
      dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("Keep Going") } }
    )
  }

  if (showCompletionDialog) {
    val stalled = recalTrigger == RecalibrationTrigger.STALLED
    AlertDialog(
      onDismissRequest = { showCompletionDialog = false },
      title = { Text(if (stalled) "Progress has stalled" else "Block Complete!", fontWeight = FontWeight.Bold) },
      text  = {
        Text(
          if (stalled)
            "A few lifts haven't moved in several weeks. Re-calibrating with a fresh baseline (and an easier first week / wider rep range) often gets things moving again."
          else
            "Amazing work - you finished every session! How would you like to recalibrate and go again?"
        )
      },
      confirmButton = {
        Button(
          onClick = { showCompletionDialog = false; vm.restartFromWeek2() },
          colors  = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)
        ) { Text(if (stalled) "Repeat with new baseline" else "Use Week 2 weights") }
      },
      dismissButton = {
        Column {
          if (sameSplitBlocks.isNotEmpty()) {
            TextButton(onClick = { showCompletionDialog = false; showNewBlockSheet = true }) {
              Text("Start a new block", color = LocalAccentColor.current, fontWeight = FontWeight.SemiBold)
            }
          }
          TextButton(onClick = { showCompletionDialog = false; vm.restartProgram() }) { Text("Start Fresh") }
          TextButton(onClick = { showCompletionDialog = false }) { Text("Dismiss") }
        }
      }
    )
  }

  // %1RM programs pass through the start gate (lift picker + missing-1RM prompt) before import.
  var pendingStart by remember { mutableStateOf<com.wildodds.gymtracker.ui.library.PendingProgramStart?>(null) }
  com.wildodds.gymtracker.ui.library.ProgramStartGate(
    pending = pendingStart,
    onDismiss = { pendingStart = null },
    onProceed = { prog, activate ->
      pendingStart = null
      if (activate) vm.loadDefaultProgram(prog) else vm.addDefaultToLibrary(prog)
    }
  )

  if (showDefaultSheet) {
    DefaultProgramSheet(
      programs              = defaultPrograms,
      userPrograms          = userPrograms,
      favourites            = favourites,
      onAddToLibrary        = { prog -> showDefaultSheet = false; pendingStart = com.wildodds.gymtracker.ui.library.PendingProgramStart(prog, activate = false) },
      onSetAsActive         = { prog -> showDefaultSheet = false; pendingStart = com.wildodds.gymtracker.ui.library.PendingProgramStart(prog, activate = true) },
      onActivateUserProgram = { prog -> showDefaultSheet = false; vm.activateUserProgram(prog.id) },
      onToggleFavourite     = { name -> vm.toggleFavourite(name) },
      onDismiss             = { showDefaultSheet = false }
    )
  }

  Scaffold(
    snackbarHost        = { SnackbarHost(snackbarHost) },
    containerColor      = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets(0)
  ) { padding ->
    LazyColumn(
      modifier       = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(bottom = 120.dp)
    ) {
      // ── Top bar: wordmark + actions ─────────────────────────────────────
      item(key = "topbar") {
        Row(
          modifier = Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = Space.screenEdge, end = Space.sm, top = Space.lg, bottom = Space.sm),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("GYM TRACKER", style = AppType.title, color = c.text)
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (injuryEnabled) {
              IconButton(onClick = { showInjurySheet = true }) {
                Icon(Icons.Default.MedicalServices, "Injury check", tint = c.text)
              }
            }
            IconButton(onClick = toggleDark) {
              Icon(if (isDark) Icons.Default.WbSunny else Icons.Default.NightsStay,
                "Toggle dark mode", tint = c.text)
            }
          }
        }
      }

      if (program == null) {
        // ── Empty state ───────────────────────────────────────────────────
        item(key = "empty_cta") {
          Column(
            modifier = Modifier.padding(horizontal = Space.screenEdge, vertical = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)
          ) {
            Text("NO ACTIVE\nPROGRAM", style = AppType.display, color = c.text)
            Spacer(Modifier.height(Space.sm))
            Button(
              onClick  = { navController.navigate("create_program") },
              modifier = Modifier.fillMaxWidth().height(52.dp),
              shape    = Radii.cardShape,
              colors   = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)
            ) {
              Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(Space.sm))
              Text("Create Custom Program", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            OutlinedButton(
              onClick  = { showDefaultSheet = true },
              modifier = Modifier.fillMaxWidth().height(52.dp),
              shape    = Radii.cardShape,
              border   = BorderStroke(1.dp, c.border),
              colors   = ButtonDefaults.outlinedButtonColors(contentColor = c.text)
            ) {
              Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(Space.sm))
              Text("Choose a Default Program", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
          }
        }
      } else {
        // ── Program header ──────────────────────────────────────────────────
        item(key = "program_header") {
          ProgramHeaderBlock(
            program  = program!!,
            stats    = programStats,
            sections = weekSections,
            modifier = Modifier.padding(start = Space.screenEdge, end = Space.screenEdge, top = Space.md, bottom = Space.lg)
          )
        }

        // ── Streak (Phase 6A) ───────────────────────────────────────────────
        if (achievementsEnabled && achievementsState.currentStreakDays > 0) {
          item(key = "streak_chip") {
            StreakRow(
              days = achievementsState.currentStreakDays,
              onClick = { navController.navigate("achievements") },
              modifier = Modifier.padding(horizontal = Space.screenEdge, vertical = Space.sm)
            )
          }
        }

        // ── Activity insight (Phase 7A) ─────────────────────────────────────
        val insight = insightsAssessment
        if (insightsEnabled && !insightsDismissed && insight != null &&
          insight.risk != com.wildodds.gymtracker.data.retention.DropoutRisk.LOW) {
          item(key = "insight_card") {
            InsightCard(
              message = com.wildodds.gymtracker.data.retention.DropoutRiskEngine.encouragement(insight),
              onStart = {
                val next = weekSections.firstNotNullOfOrNull { sec ->
                  sec.sessions.firstOrNull { it.isNextUp }?.let { it.session.id to sec.weekNumber }
                }
                if (next != null) navController.navigate("session/${next.first}/${next.second}")
              },
              onDismiss = { insightsVm.dismiss() },
              modifier = Modifier.padding(horizontal = Space.screenEdge, vertical = Space.sm)
            )
          }
        }

        // ── Phase selector (multi-phase programs only) ──────────────────────
        if (phases.size > 1) {
          item(key = "phase_selector") {
            PhaseSelector(
              phases = phases,
              currentPhase = program?.currentPhase ?: 1,
              onSelect = { vm.setPhase(it) }
            )
          }
        }

        // ── Weekly day list (the home centrepiece) ──────────────────────────
        val currentWeekNo = weekSections.firstOrNull { s -> s.sessions.any { !it.isCompleted } }?.weekNumber
          ?: weekSections.firstOrNull()?.weekNumber
        val multiWeek = weekSections.size > 1

        weekSections.forEach { section ->
          val isCurrentWeek = section.weekNumber == currentWeekNo
          val weekExpanded = collapsedWeeks[section.weekNumber] ?: isCurrentWeek

          if (multiWeek) {
            item(key = "week_${section.weekNumber}") {
              WeekLabel(
                section = section,
                isCurrentWeek = isCurrentWeek,
                expanded = weekExpanded,
                onToggle = { collapsedWeeks[section.weekNumber] = !weekExpanded }
              )
            }
          }

          if (weekExpanded) {
            items(section.sessions, key = { "day_${it.session.id}_${section.weekNumber}" }) { row ->
              val defaultExpanded = isCurrentWeek && row.isNextUp
              val expanded = dayExpanded[row.session.id] ?: defaultExpanded
              DayBlock(
                row       = row,
                exercises = exercisesBySession[row.session.id].orEmpty(),
                expanded  = expanded,
                onToggle  = { dayExpanded[row.session.id] = !expanded },
                onOpen    = {
                  vm.startSession(row.session.id, row.session.dayNumber, section.weekNumber) { sid, wk ->
                    navController.navigate("session/$sid/$wk")
                  }
                },
                onInfo    = { navController.navigate("session_info/${row.session.id}/${section.weekNumber}") }
              )
            }
          }
        }

        // ── Bottom actions ──────────────────────────────────────────────────
        item(key = "bottom_actions") {
          Column(
            modifier = Modifier.padding(horizontal = Space.screenEdge).padding(top = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
          ) {
            OutlinedButton(
              onClick  = { showManageDays = true },
              modifier = Modifier.fillMaxWidth().height(48.dp),
              shape    = Radii.cardShape,
              border   = BorderStroke(1.dp, c.border),
              colors   = ButtonDefaults.outlinedButtonColors(contentColor = c.text)
            ) {
              Icon(Icons.Default.EditCalendar, null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(Space.sm))
              Text("Manage Days", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
              onClick  = { showRestartDialog = true },
              enabled  = !restarting,
              modifier = Modifier.fillMaxWidth().height(48.dp),
              shape    = Radii.cardShape,
              border   = BorderStroke(1.dp, c.border),
              colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
              if (restarting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.error)
              } else {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.sm))
                Text("Restart Block from Week 1", fontWeight = FontWeight.SemiBold)
              }
            }
            OutlinedButton(
              onClick  = { showCancelDialog = true },
              modifier = Modifier.fillMaxWidth().height(48.dp),
              shape    = Radii.cardShape,
              border   = BorderStroke(1.dp, c.border),
              colors   = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary)
            ) {
              Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(Space.sm))
              Text("Cancel Current Program", fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  }
}

// ─── Program header (monochrome) ──────────────────────────────────────────────

@Composable
private fun ProgramHeaderBlock(
  program:  Program,
  stats:    ProgramStats,
  sections: List<WeekSection>,
  modifier: Modifier = Modifier
) {
  val c = LocalAppColors.current
  val currentWeek = sections.firstOrNull { s -> s.sessions.any { !it.isCompleted } }?.weekNumber
    ?: sections.lastOrNull()?.weekNumber ?: 1
  val progress = if (stats.totalSessions > 0)
    stats.completedSessions.toFloat() / stats.totalSessions else 0f

  Column(modifier.fillMaxWidth()) {
    Text("WEEK $currentWeek OF ${program.totalWeeks}", style = AppType.section, color = c.textSecondary)
    Spacer(Modifier.height(Space.sm))
    Text(program.name, style = AppType.display, color = c.text)
    Spacer(Modifier.height(Space.lg))
    // Progress hairline
    Box(Modifier.fillMaxWidth().height(2.dp).background(c.border)) {
      Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(c.accent))
    }
    Spacer(Modifier.height(Space.sm))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("${(progress * 100).toInt()}% COMPLETE", style = AppType.meta, color = c.textTertiary)
      Text("${stats.completedSessions} / ${stats.totalSessions} SESSIONS", style = AppType.meta, color = c.textTertiary)
    }
  }
}

// ─── Week label ───────────────────────────────────────────────────────────────

@Composable
private fun WeekLabel(
  section: WeekSection,
  isCurrentWeek: Boolean,
  expanded: Boolean,
  onToggle: () -> Unit
) {
  val c = LocalAppColors.current
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
      .padding(start = Space.screenEdge, end = Space.screenEdge, top = Space.x3, bottom = Space.sm),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text("WEEK ${section.weekNumber}", style = AppType.section, color = if (isCurrentWeek) c.accent else c.textSecondary)
    Spacer(Modifier.weight(1f))
    Text("${section.completedCount}/${section.sessions.size}", style = AppType.section, color = c.textTertiary)
    Spacer(Modifier.width(Space.sm))
    Icon(
      if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
      contentDescription = if (expanded) "Collapse week" else "Expand week",
      tint = c.textSecondary, modifier = Modifier.size(18.dp)
    )
  }
}

// ─── Day block (big UPPERCASE header + expandable lift list) ───────────────────

@Composable
private fun DayBlock(
  row: SessionRowUi,
  exercises: List<Exercise>,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpen: () -> Unit,
  onInfo: () -> Unit
) {
  val c = LocalAppColors.current
  Column(Modifier.fillMaxWidth()) {
    // Header — tap toggles expand/collapse.
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
        .padding(horizontal = Space.screenEdge, vertical = Space.xl),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Text(row.session.name.uppercase(), style = AppType.display, color = c.text)
        Spacer(Modifier.height(Space.xs))
        val sub = listOfNotNull(
          "Day ${row.session.dayNumber}",
          row.session.muscleGroups.ifBlank { null }
        ).joinToString("  ·  ")
        Text(sub, style = AppType.label, color = c.textSecondary)
        if (row.isNextUp && !row.isCompleted) {
          Spacer(Modifier.height(Space.sm))
          Text("NEXT UP", style = AppType.section, color = c.accent)
        }
      }
      Spacer(Modifier.width(Space.md))
      // Per-day completion checkbox — reflects real session-completion state;
      // tapping it opens the session's logging flow (the app's existing logic).
      MonoCheckbox(checked = row.isCompleted, onClick = onOpen)
    }

    // Expanded: this day's lifts as a compact, read-only summary (small single-line
    // rows, no dividers) — the full detail lives in the session logging screen.
    AnimatedVisibility(visible = expanded) {
      Column(Modifier.fillMaxWidth().padding(horizontal = Space.screenEdge)) {
        Spacer(Modifier.height(Space.xs))
        exercises.forEach { ex ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              ex.name,
              style = AppType.body.copy(
                textDecoration = if (row.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
              ),
              color = if (row.isCompleted) c.textSecondary else c.text,
              maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Space.md))
            Text("${ex.sets} × ${ex.repsTarget}", style = AppType.meta, color = c.textTertiary)
          }
        }
        Spacer(Modifier.height(Space.sm))
        Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            if (row.isCompleted) "REVIEW SESSION  →" else "START SESSION  →",
            style = AppType.section, color = c.accent,
            modifier = Modifier.weight(1f).clickable(onClick = onOpen)
          )
          Text(
            "DETAILS",
            style = AppType.section, color = c.textSecondary,
            modifier = Modifier.clickable(onClick = onInfo)
          )
        }
      }
    }
    AppDivider()
  }
}

// ─── Streak row (Phase 6A) ────────────────────────────────────────────────────

@Composable
private fun StreakRow(days: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val c = LocalAppColors.current
  Surface(
    onClick = onClick,
    shape = Radii.cardShape,
    color = c.surface,
    border = BorderStroke(1.dp, c.border),
    modifier = modifier.fillMaxWidth()
  ) {
    Row(
      Modifier.padding(horizontal = Space.lg, vertical = Space.md),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("$days", style = AppType.title, color = c.accent)
      Spacer(Modifier.width(Space.sm))
      Text("DAY STREAK", style = AppType.section, color = c.text)
      Spacer(Modifier.weight(1f))
      Text("ACHIEVEMENTS  →", style = AppType.section, color = c.textSecondary)
    }
  }
}

// ─── Activity insight card (Phase 7A) ─────────────────────────────────────────

@Composable
private fun InsightCard(message: String, onStart: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  val c = LocalAppColors.current
  Surface(
    shape = Radii.cardShape,
    color = c.surface,
    border = BorderStroke(1.dp, c.border),
    modifier = modifier.fillMaxWidth()
  ) {
    Column(Modifier.fillMaxWidth().padding(Space.lg)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("A GENTLE NUDGE", Modifier.weight(1f), style = AppType.section, color = c.textSecondary)
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
          Icon(Icons.Default.Close, "Dismiss", tint = c.textSecondary, modifier = Modifier.size(18.dp))
        }
      }
      Spacer(Modifier.height(Space.sm))
      Text(message, style = AppType.body, color = c.text)
      Spacer(Modifier.height(Space.md))
      Text(
        "START AN EASY SESSION  →",
        style = AppType.section, color = c.accent,
        modifier = Modifier.clickable(onClick = onStart)
      )
    }
  }
}

// ─── Manage days (add / rename / remove days from Home) ───────────────────────

@Composable
private fun ManageDaysSheet(
  days: List<com.wildodds.gymtracker.data.db.entity.Session>,
  onAdd: (String) -> Unit,
  onRename: (dayNumber: Int, name: String) -> Unit,
  onRemove: (dayNumber: Int) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current
  var newDayName by remember { mutableStateOf("") }
  var pendingDelete by remember { mutableStateOf<com.wildodds.gymtracker.data.db.entity.Session?>(null) }

  pendingDelete?.let { d ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Remove this day?") },
      text = { Text("\"${d.name}\" will be removed from every week of this program, along with its logged sets. Other days are unaffected.") },
      confirmButton = {
        TextButton(onClick = { onRemove(d.dayNumber); pendingDelete = null },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
    )
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
    containerColor = c.bg) {
    Column(modifier = Modifier.fillMaxWidth().imePadding()
      .padding(horizontal = Space.screenEdge).padding(bottom = Space.x3)) {
      Text("MANAGE DAYS", style = AppType.section, color = c.textSecondary)
      Spacer(Modifier.height(Space.sm))
      Text("Rename or remove a day, or add a new one. Changes apply to every week.",
        style = AppType.body, color = c.textSecondary)
      Spacer(Modifier.height(Space.md))

      days.forEach { day ->
        var name by remember(day.dayNumber, day.name) { mutableStateOf(day.name) }
        Row(verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
          OutlinedTextField(
            value = name, onValueChange = { name = it },
            modifier = Modifier.weight(1f), singleLine = true,
            label = { Text("Day ${day.dayNumber}", style = AppType.meta) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            shape = Radii.smShape,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text)
          )
          if (name.trim().isNotEmpty() && name.trim() != day.name) {
            IconButton(onClick = { onRename(day.dayNumber, name.trim()) }) {
              Icon(Icons.Default.Check, "Save name", tint = c.accent)
            }
          }
          IconButton(onClick = { pendingDelete = day }) {
            Icon(Icons.Default.Delete, "Remove day", tint = MaterialTheme.colorScheme.error)
          }
        }
      }

      Spacer(Modifier.height(Space.md))
      AppDivider()
      Spacer(Modifier.height(Space.md))
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = newDayName, onValueChange = { newDayName = it },
          modifier = Modifier.weight(1f), singleLine = true,
          placeholder = { Text("New day name, e.g. Arm Day") },
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
          shape = Radii.smShape,
          colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text)
        )
        Spacer(Modifier.width(Space.sm))
        Button(
          onClick = { onAdd(newDayName.trim()); newDayName = "" },
          shape = Radii.smShape,
          colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)
        ) {
          Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(Space.xs))
          Text("Add")
        }
      }
    }
  }
}

// ─── Start a new block (same split) bottom sheet ──────────────────────────────

@Composable
private fun StartNewBlockSheet(
  split:    String,
  blocks:   List<ParsedProgram>,
  onStart:  (ParsedProgram) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    LazyColumn(contentPadding = PaddingValues(start = Space.screenEdge, end = Space.screenEdge, bottom = Space.x5)) {
      item {
        Text("START A NEW BLOCK", style = AppType.section, color = c.textSecondary,
          modifier = Modifier.padding(top = Space.sm, bottom = Space.xs))
        Text(
          "${blocks.size} other ${if (split.isBlank()) "" else "$split "}blocks · starting one replaces your current block",
          style = AppType.body, color = c.textSecondary,
          modifier = Modifier.padding(bottom = Space.md)
        )
      }
      items(blocks, key = { it.name }) { block ->
        GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs).clickable { onStart(block) }) {
          Column(Modifier.fillMaxWidth()) {
            Text(block.name, style = AppType.title, color = c.text)
            Spacer(Modifier.height(2.dp))
            Text(
              listOfNotNull(
                block.style.ifBlank { null },
                "${block.daysPerWeek}x / week".takeIf { block.daysPerWeek > 0 },
                "${block.totalWeeks} weeks"
              ).joinToString(" · "),
              style = AppType.label, color = c.textSecondary
            )
            if (block.description.isNotBlank()) {
              Spacer(Modifier.height(Space.sm))
              Text(block.description, style = AppType.body, color = c.textSecondary, maxLines = 3)
            }
          }
        }
      }
    }
  }
}

// ─── Default program bottom sheet ─────────────────────────────────────────────

@Composable
private fun DefaultProgramSheet(
  programs:              List<ParsedProgram>,
  userPrograms:          List<Program>,
  favourites:            Set<String>,
  onAddToLibrary:        (ParsedProgram) -> Unit,
  onSetAsActive:         (ParsedProgram) -> Unit,
  onActivateUserProgram: (Program) -> Unit,
  onToggleFavourite:     (String) -> Unit,
  onDismiss:             () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current
  var query     by remember { mutableStateOf("") }
  var dayFilter by remember { mutableStateOf<Int?>(null) }

  val defaultOrder = programs.map { it.category.ifBlank { "Other" } }.distinct()

  val expanded = remember {
    mutableStateMapOf<String, Boolean>().also { m ->
      if (favourites.isNotEmpty()) m["Favourites"] = true
      if (userPrograms.isNotEmpty()) m["Your Programs"] = true
      defaultOrder.forEach { m[it] = false }
    }
  }

  fun ParsedProgram.days(): Int = sessions.filter { it.weekNumber == 1 }.maxOfOrNull { it.dayNumber }
    ?: sessions.map { it.dayNumber }.distinct().size

  fun ParsedProgram.matchesDayFilter(): Boolean {
    if (dayFilter == null) return true
    val d = days()
    return if (dayFilter == 6) d >= 6 else d == dayFilter
  }

  val trimmed = query.trim()
  val filtersActive = trimmed.isNotEmpty() || dayFilter != null
  val matchedDefault = if (filtersActive) programs.filter {
    (trimmed.isEmpty() || it.name.contains(trimmed, ignoreCase = true) || it.category.contains(trimmed, ignoreCase = true))
      && it.matchesDayFilter()
  } else emptyList()

  val grouped = programs.filter { it.matchesDayFilter() }.groupBy { it.category.ifBlank { "Other" } }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    LazyColumn(contentPadding = PaddingValues(start = Space.screenEdge, end = Space.screenEdge, bottom = Space.x5)) {
      item {
        Text("BLOCKS", style = AppType.section, color = c.textSecondary,
          modifier = Modifier.padding(top = Space.sm, bottom = Space.xs))
        Text("${programs.size + userPrograms.size} blocks available",
          style = AppType.body, color = c.textSecondary, modifier = Modifier.padding(bottom = Space.md))
        OutlinedTextField(
          value         = query,
          onValueChange = { query = it },
          modifier      = Modifier.fillMaxWidth(),
          placeholder   = { Text("Search programs...", style = AppType.body) },
          leadingIcon   = { Icon(Icons.Default.Search, null, tint = c.textSecondary) },
          trailingIcon  = if (query.isNotEmpty()) {{
            IconButton(onClick = { query = "" }) {
              Icon(Icons.Default.Clear, "Clear search", tint = c.textSecondary)
            }
          }} else null,
          singleLine    = true,
          shape         = Radii.cardShape,
          colors        = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        Spacer(Modifier.height(Space.md))

        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = Space.lg),
          horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
          SheetDayChip("Any", dayFilter == null) { dayFilter = null }
          listOf(2, 3, 4, 5, 6).forEach { d ->
            SheetDayChip(if (d == 6) "6+ days" else "$d days", dayFilter == d) {
              dayFilter = if (dayFilter == d) null else d
            }
          }
        }
      }

      if (filtersActive) {
        if (matchedDefault.isEmpty()) {
          item {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = Space.x4),
              contentAlignment = Alignment.Center) {
              Text(if (trimmed.isNotEmpty()) "No programs match \"$trimmed\"" else "No programs match your filters",
                color = c.textSecondary, style = AppType.body)
            }
          }
        } else {
          items(matchedDefault, key = { "search_${it.name}" }) { prog ->
            DefaultProgramCard(prog, isFav = prog.name in favourites,
              onAddToLibrary = onAddToLibrary, onSetAsActive = onSetAsActive, onToggleFavourite = onToggleFavourite)
          }
        }
        return@LazyColumn
      }

      val favPrograms = programs.filter { it.name in favourites }
      if (favPrograms.isNotEmpty()) {
        item(key = "hdr_Favourites") {
          CategoryHeader("Favourites", favPrograms.size, expanded["Favourites"] ?: true) {
            expanded["Favourites"] = !(expanded["Favourites"] ?: true)
          }
        }
        if (expanded["Favourites"] != false) {
          items(favPrograms, key = { "fav_${it.name}" }) { prog ->
            DefaultProgramCard(prog, isFav = true, onAddToLibrary = onAddToLibrary, onSetAsActive = onSetAsActive, onToggleFavourite = onToggleFavourite)
          }
        }
      }

      if (userPrograms.isNotEmpty()) {
        item(key = "hdr_YourPrograms") {
          CategoryHeader("Your Programs", userPrograms.size, expanded["Your Programs"] ?: true) {
            expanded["Your Programs"] = !(expanded["Your Programs"] ?: true)
          }
        }
        if (expanded["Your Programs"] != false) {
          items(userPrograms, key = { "user_${it.id}" }) { prog ->
            GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onActivateUserProgram(prog) }) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                LetterTile(prog.name)
                Spacer(Modifier.width(Space.md))
                Column(modifier = Modifier.weight(1f)) {
                  Text(prog.name, style = AppType.title, color = c.text)
                  Text("${prog.totalWeeks} weeks · custom", color = c.textSecondary, style = AppType.label)
                }
                Icon(Icons.Default.ChevronRight, null, tint = c.textSecondary)
              }
            }
          }
        }
      }

      defaultOrder.forEach { category ->
        val categoryPrograms = grouped[category] ?: return@forEach
        val isExpanded = expanded[category] ?: false
        item(key = "hdr_$category") {
          CategoryHeader(category, categoryPrograms.size, isExpanded) { expanded[category] = !isExpanded }
        }
        if (isExpanded) {
          items(categoryPrograms, key = { it.name }) { prog ->
            DefaultProgramCard(prog, isFav = prog.name in favourites, onAddToLibrary = onAddToLibrary, onSetAsActive = onSetAsActive, onToggleFavourite = onToggleFavourite)
          }
        }
      }
    }
  }
}

@Composable
private fun SheetDayChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val c = LocalAppColors.current
  Surface(
    onClick = onClick,
    shape = Radii.pillShape,
    color = if (selected) c.accent else c.surface,
    border = if (selected) null else BorderStroke(1.dp, c.border)
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
      color = if (selected) Color.White else c.text,
      style = AppType.label
    )
  }
}

@Composable
private fun CategoryHeader(title: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
  val c = LocalAppColors.current
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = Space.lg, bottom = Space.sm),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(title.uppercase(), style = AppType.section, color = c.text)
    Spacer(Modifier.width(Space.sm))
    Text("$count", style = AppType.section, color = c.textTertiary)
    Spacer(Modifier.weight(1f))
    Icon(
      if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
      contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp)
    )
  }
}

/** Monochrome square tile with the program's initial — replaces the old image thumbnail. */
@Composable
private fun LetterTile(name: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
  val c = LocalAppColors.current
  Surface(
    shape = Radii.smShape,
    color = c.surface2,
    border = BorderStroke(1.dp, c.border),
    modifier = Modifier.size(size)
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
        style = AppType.title, color = c.text)
    }
  }
}

@Composable
private fun DefaultProgramCard(
  prog:  ParsedProgram,
  isFav: Boolean,
  onAddToLibrary: (ParsedProgram) -> Unit,
  onSetAsActive:  (ParsedProgram) -> Unit,
  onToggleFavourite: (String) -> Unit
) {
  val c = LocalAppColors.current
  val daysPerWeek = prog.sessions.filter { it.weekNumber == 1 }.maxOfOrNull { it.dayNumber }
    ?: prog.sessions.map { it.dayNumber }.distinct().size
  val subtitle = "$daysPerWeek day${if (daysPerWeek != 1) "s" else ""}/week · ${prog.totalWeeks} weeks"

  GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Space.sm)) {
        LetterTile(prog.name)
        Spacer(Modifier.width(Space.md))
        Column(modifier = Modifier.weight(1f)) {
          Text(prog.name, style = AppType.title, color = c.text)
          Text(subtitle, color = c.textSecondary, style = AppType.label)
        }
        IconButton(onClick = { onToggleFavourite(prog.name) }, modifier = Modifier.size(36.dp)) {
          Icon(
            if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = if (isFav) "Unfavourite" else "Favourite",
            tint = if (isFav) c.accent else c.textSecondary, modifier = Modifier.size(20.dp)
          )
        }
      }
      AppDivider()
      Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onAddToLibrary(prog) }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Default.BookmarkAdd, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(Space.xs))
          Text("Add to Library", color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.width(1.dp).height(32.dp).background(c.border).align(Alignment.CenterVertically))
        TextButton(onClick = { onSetAsActive(prog) }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Default.PlayArrow, null, tint = c.accent, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(Space.xs))
          Text("Set Active", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

// ─── Phase selector (text segmented) ──────────────────────────────────────────

@Composable
private fun PhaseSelector(
  phases: List<com.wildodds.gymtracker.data.db.entity.ProgramPhase>,
  currentPhase: Int,
  onSelect: (Int) -> Unit
) {
  val c = LocalAppColors.current
  val ordered = phases.sortedBy { it.phaseNumber }
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screenEdge, vertical = Space.sm)) {
    Text("PROGRAM PHASES", style = AppType.section, color = c.textSecondary)
    Spacer(Modifier.height(Space.sm))
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
      ordered.forEachIndexed { idx, phase ->
        val n = idx + 1
        val selected = n == currentPhase
        Surface(
          onClick = { onSelect(n) },
          shape = Radii.cardShape,
          color = if (selected) c.accent else c.surface,
          border = if (selected) null else BorderStroke(1.dp, c.border)
        ) {
          Column(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
            Text("Phase $n", style = AppType.label,
              color = if (selected) Color.White else c.text, fontWeight = FontWeight.SemiBold)
            Text("${phase.name.ifBlank { "Phase $n" }} · ${phase.durationWeeks}w",
              style = AppType.meta, color = if (selected) Color.White.copy(0.85f) else c.textSecondary)
          }
        }
      }
    }
  }
}

// ─── Excel options bottom sheet ───────────────────────────────────────────────
// NOTE: also used by CreateProgramScreen and LibraryScreen — keep this signature.

@Composable
internal fun ExcelOptionsSheet(
  onUpload:    () -> Unit,
  onDownloadTemplate: () -> Unit,
  onDismiss:   () -> Unit,
  onBulkUpload: () -> Unit = {},
  onDownloadBulkTemplate: () -> Unit = {}
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xxl).padding(bottom = Space.x4)
    ) {
      // Header
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier.size(48.dp).clip(Radii.smShape).background(c.surface2),
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Default.TableChart, null, tint = c.text, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column {
          Text("Excel Import", style = AppType.title, color = c.text)
          Text("Import your training program from a spreadsheet", color = c.textSecondary, style = AppType.body)
        }
      }

      Spacer(Modifier.height(Space.x3))

      ExcelOptionRow(
        icon = Icons.Default.Upload, title = "Upload Spreadsheet",
        subtitle = "Any .xlsx file - our template or your own", emphasised = true, onClick = onUpload
      )
      Spacer(Modifier.height(Space.md))
      ExcelOptionRow(
        icon = Icons.Default.Download, title = "Download Template",
        subtitle = "Get the official Wild Odds template with all features", emphasised = false, onClick = onDownloadTemplate
      )

      Spacer(Modifier.height(Space.xl))
      AppDivider()
      Spacer(Modifier.height(Space.sm))
      Text("BULK — MULTIPLE PROGRAMS + PHASES", style = AppType.section, color = c.textSecondary)
      Spacer(Modifier.height(Space.md))

      ExcelOptionRow(
        icon = Icons.Default.LibraryAdd, title = "Bulk Upload Programs",
        subtitle = "Import many programs at once, with multi-phase support", emphasised = true, onClick = onBulkUpload
      )
      Spacer(Modifier.height(Space.md))
      ExcelOptionRow(
        icon = Icons.Default.Download, title = "Download Bulk Template",
        subtitle = "Phased template — give it + screenshots to Claude to fill", emphasised = false, onClick = onDownloadBulkTemplate
      )

      Spacer(Modifier.height(Space.xxl))

      Surface(shape = Radii.smShape, color = c.surface, border = BorderStroke(1.dp, c.border)) {
        Row(modifier = Modifier.padding(Space.md), verticalAlignment = Alignment.Top) {
          Icon(Icons.Default.AutoAwesome, null, tint = c.textSecondary,
            modifier = Modifier.size(16.dp).padding(top = 1.dp))
          Spacer(Modifier.width(Space.sm))
          Text(
            "Smart detection reads any spreadsheet format - coach programs, custom templates, or our official template. Supports sets, reps, weight, RPE, %1RM, rest times, and supersets.",
            style = AppType.meta, color = c.textSecondary, lineHeight = 17.sp
          )
        }
      }
    }
  }
}

@Composable
private fun ExcelOptionRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String,
  emphasised: Boolean,
  onClick: () -> Unit
) {
  val c = LocalAppColors.current
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = Radii.cardShape,
    color = if (emphasised) c.surface2 else c.surface,
    border = BorderStroke(1.dp, c.border)
  ) {
    Row(modifier = Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier.size(44.dp).clip(Radii.smShape)
          .background(if (emphasised) c.accent else c.surface2),
        contentAlignment = Alignment.Center
      ) {
        Icon(icon, null, tint = if (emphasised) Color.White else c.text, modifier = Modifier.size(22.dp))
      }
      Spacer(Modifier.width(Space.lg))
      Column(modifier = Modifier.weight(1f)) {
        Text(title, style = AppType.title, color = c.text)
        Text(subtitle, color = c.textSecondary, style = AppType.label)
      }
      Icon(Icons.Default.ChevronRight, null, tint = c.textSecondary)
    }
  }
}

// ─── Spreadsheet preview sheet ────────────────────────────────────────────────
// NOTE: also used by CreateProgramScreen and LibraryScreen — keep this signature.

@Composable
internal fun SpreadsheetPreviewSheet(
  smart:     SmartParsedProgram,
  initName:  String,
  initWeeks: Int,
  onImport:  (name: String, weeks: Int) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current
  var name  by remember { mutableStateOf(initName) }
  var weeks by remember { mutableStateOf(initWeeks) }

  val totalExercises = smart.weekTemplate.sumOf { it.exercises.size }
  val errors   = smart.warnings.filter { it.severity == ParseWarning.Severity.ERROR }
  val warnOnly = smart.warnings.filter { it.severity == ParseWarning.Severity.WARNING }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = Space.screenEdge).padding(bottom = Space.x3)
    ) {
      Text("REVIEW IMPORT", style = AppType.section, color = c.textSecondary)
      Spacer(Modifier.height(Space.xs))
      Text(
        "${smart.layoutDetected.name.lowercase().replaceFirstChar { it.uppercase() }} layout · ${smart.weekDetectionMethod}",
        color = c.textSecondary, style = AppType.label
      )

      if (errors.isNotEmpty() || warnOnly.isNotEmpty()) {
        Spacer(Modifier.height(Space.md))
        (errors + warnOnly).forEach { w ->
          val isError = w.severity == ParseWarning.Severity.ERROR
          val fg = if (isError) MaterialTheme.colorScheme.error else c.text
          Surface(color = c.surface, border = BorderStroke(1.dp, if (isError) MaterialTheme.colorScheme.error else c.border),
            shape = Radii.smShape, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(modifier = Modifier.padding(Space.md), verticalAlignment = Alignment.Top) {
              val icon = when (w.severity) {
                ParseWarning.Severity.ERROR  -> Icons.Default.Error
                ParseWarning.Severity.WARNING -> Icons.Default.Warning
                else -> Icons.Default.Info
              }
              Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp).padding(top = 1.dp))
              Spacer(Modifier.width(Space.sm))
              Column {
                Text(w.message, color = fg, style = AppType.label)
                w.rawValue?.let { Text("Raw: \"$it\"", color = c.textTertiary, style = AppType.meta) }
              }
            }
          }
        }
      }

      Spacer(Modifier.height(Space.xl))
      Text("Program Name", style = AppType.title, color = c.text)
      Spacer(Modifier.height(Space.sm))
      OutlinedTextField(
        value = name, onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        shape  = Radii.cardShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text)
      )

      Spacer(Modifier.height(Space.xl))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()) {
        Text("Weeks", style = AppType.title, color = c.text)
        Text("Detected: ${smart.weeksDetected}", color = c.textSecondary, style = AppType.label)
      }
      Spacer(Modifier.height(Space.sm))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        (1..minOf(52, maxOf(16, smart.weeksDetected + 4))).forEach { w ->
          val selected = weeks == w
          Surface(
            onClick = { weeks = w }, modifier = Modifier.size(40.dp), shape = CircleShape,
            color = if (selected) c.accent else c.surface,
            border = if (selected) null else BorderStroke(1.dp, c.border)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text("$w", color = if (selected) Color.White else c.text, style = AppType.label)
            }
          }
        }
      }

      Spacer(Modifier.height(Space.xl))
      Text("Detected Structure", style = AppType.title, color = c.text)
      Spacer(Modifier.height(Space.xs))
      Text(
        "${smart.weekTemplate.size} training ${if (smart.weekTemplate.size == 1) "day" else "days"} · $totalExercises exercises per week",
        color = c.textSecondary, style = AppType.label
      )
      Spacer(Modifier.height(Space.sm))
      smart.weekTemplate.forEach { day ->
        GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
          Column {
            Text(day.name, style = AppType.title, color = c.text)
            Spacer(Modifier.height(Space.xs))
            day.exercises.forEach { ex ->
              Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text("• ${ex.name}", modifier = Modifier.weight(1f), color = c.text, style = AppType.body)
                val wtStr = if (ex.weight != null) {
                  val v = if (ex.weight % 1f == 0f) ex.weight.toInt().toString() else ex.weight.toString()
                  "$v${ex.weightUnit.name.lowercase().takeIf { it != "unknown" } ?: ""} "
                } else ""
                Text("${ex.sets}×${ex.reps} $wtStr".trim(), color = c.textSecondary, style = AppType.label)
              }
            }
          }
        }
      }

      Spacer(Modifier.height(Space.xl))
      Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        OutlinedButton(
          onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp),
          shape = Radii.cardShape, border = BorderStroke(1.dp, c.border),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text)
        ) { Text("Cancel") }

        Button(
          onClick = { onImport(name, weeks) },
          enabled = name.isNotBlank() && smart.weekTemplate.isNotEmpty(),
          modifier = Modifier.weight(2f).height(52.dp), shape = Radii.cardShape,
          colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White, disabledContainerColor = c.surface2)
        ) {
          Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(Space.sm))
          Text("Import ${weeks}w Program", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
      }
    }
  }
}
