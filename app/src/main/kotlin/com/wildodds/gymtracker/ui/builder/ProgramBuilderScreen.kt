@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.wildodds.gymtracker.ui.builder

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.builder.*
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.LocalAppColors
import kotlin.math.roundToInt

/**
 * The program builder (§1 of the builder brief).
 *
 * One page, no wizard. The golden path is: name it → add workouts → add exercises → set targets →
 * save, and every optional control (goal, experience, duration, scheduling, progression) stays
 * collapsed until tapped. Nothing is mandatory, everything autosaves, and an incomplete program
 * always saves with a warning rather than a block.
 */
@Composable
fun ProgramBuilderScreen(
  navController: NavController,
  vm: BuilderViewModel = viewModel()
) {
  val plan by vm.plan.collectAsStateWithLifecycle()
  val summary by vm.summary.collectAsStateWithLifecycle()
  val saveStatus by vm.saveStatus.collectAsStateWithLifecycle()
  val undo by vm.undoPrompt.collectAsStateWithLifecycle()
  val committed by vm.committed.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()

  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  val snackbar = remember { SnackbarHostState() }

  var editingWorkoutId by remember { mutableStateOf<String?>(null) }
  var showDetails by remember { mutableStateOf(false) }
  var showSummary by remember { mutableStateOf(true) }
  var showAddMenu by remember { mutableStateOf(false) }
  var showOverflow by remember { mutableStateOf(false) }
  var showTemplates by remember { mutableStateOf(false) }
  var showPasteImport by remember { mutableStateOf(false) }
  var showScheduling by remember { mutableStateOf(false) }
  var showProgression by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf<String?>(null) }
  // An import awaiting the preview — pasted or read from a file, any supported format.
  val pendingImport by vm.pendingImport.collectAsStateWithLifecycle()

  // Export writes through the platform file picker — a local file operation, nothing uploaded.
  val exportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(ProgramPlanJson.MIME)
  ) { uri ->
    uri ?: return@rememberLauncherForActivityResult
    runCatching {
      navController.context.contentResolver.openOutputStream(uri)?.use {
        it.write(vm.exportJson().toByteArray())
      }
    }
  }
  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri -> uri?.let { vm.prepareFileImport(it) } }

  LaunchedEffect(committed) {
    committed?.let {
      vm.consumeCommitted()
      navController.popBackStack()
    }
  }
  LaunchedEffect(message) {
    message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
  }
  LaunchedEffect(undo) {
    undo?.let {
      val result = snackbar.showSnackbar(it.label, actionLabel = "Undo", duration = SnackbarDuration.Short)
      if (result == SnackbarResult.ActionPerformed) vm.undo() else vm.dismissUndo()
    }
  }

  // ── Dialogs / sheets ───────────────────────────────────────────────────────

  editingWorkoutId?.let { id ->
    plan.workouts.firstOrNull { it.id == id }?.let { workout ->
      WorkoutEditorSheet(
        workout = workout,
        allWorkouts = plan.workouts,
        vm = vm,
        onDismiss = { editingWorkoutId = null }
      )
    }
  }

  if (showTemplates) {
    TemplatePickerSheet(
      onPick = { vm.loadTemplate(it); showTemplates = false },
      onDismiss = { showTemplates = false }
    )
  }

  if (showPasteImport) {
    PasteProgramSheet(
      onImport = { text -> vm.prepareTextImport(text); showPasteImport = false },
      onOpenFullImporter = {
        showPasteImport = false
        navController.navigate("import_program")
      },
      onDismiss = { showPasteImport = false }
    )
  }

  pendingImport?.let { pending ->
    ImportPreviewDialog(
      decoded = pending.result,
      hasExisting = plan.workouts.isNotEmpty(),
      // Imports INTO the program being built rather than navigating away and starting a new one,
      // so pasting a day part-way through never costs the user their draft.
      onConfirm = { replace -> vm.confirmImport(replace) },
      onDismiss = { vm.dismissImport() }
    )
  }

  if (showScheduling) {
    SchedulingSheet(plan = plan, vm = vm, onDismiss = { showScheduling = false })
  }
  if (showProgression) {
    ProgressionSheet(plan = plan, vm = vm, onDismiss = { showProgression = false })
  }

  confirmDelete?.let { id ->
    val w = plan.workouts.firstOrNull { it.id == id }
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text("Delete ${w?.name.orEmpty().ifBlank { "this workout" }}?") },
      text = { Text("Its exercises go with it. You can undo straight after.") },
      confirmButton = {
        TextButton(
          onClick = { vm.deleteWorkout(id); confirmDelete = null },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
    )
  }

  // ── Screen ─────────────────────────────────────────────────────────────────

  Scaffold(
    containerColor = c.bg,
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = {
      BuilderBottomBar(
        summary = summary,
        onSave = { vm.saveProgram(activate = false) },
        onSaveAndStart = { vm.saveProgram(activate = true) }
      )
    }
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(bottom = 24.dp)
    ) {
      item(key = "topbar") {
        Row(
          // No statusBarsPadding() here — the Scaffold's own inset padding is already applied to
          // this LazyColumn, and adding it again left a status-bar-sized gap above the back arrow.
          Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
          }
          Spacer(Modifier.weight(1f))
          SaveStatusLabel(saveStatus)
          Box {
            IconButton(onClick = { showOverflow = true }, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Default.MoreVert, "Program options", tint = c.text)
            }
            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
              DropdownMenuItem(
                text = { Text("Export as JSON") },
                leadingIcon = { Icon(Icons.Default.Upload, null) },
                onClick = { showOverflow = false; exportLauncher.launch(vm.exportFileName()) }
              )
              DropdownMenuItem(
                text = { Text("Import from file") },
                leadingIcon = { Icon(Icons.Default.Download, null) },
                onClick = { showOverflow = false; importLauncher.launch(arrayOf("*/*")) }
              )
              DropdownMenuItem(
                text = { Text("Paste a program") },
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                onClick = { showOverflow = false; showPasteImport = true }
              )
              HorizontalDivider()
              DropdownMenuItem(
                text = { Text("Start over") },
                leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                onClick = { showOverflow = false; vm.startNewProgram() }
              )
            }
          }
        }
      }

      // ── §1.1 Program header, inline ─────────────────────────────────────
      item(key = "header") {
        ProgramHeader(
          plan = plan,
          vm = vm,
          expanded = showDetails,
          onToggleExpanded = { showDetails = !showDetails }
        )
      }

      // ── §2.3 Start options, only while the program is empty ─────────────
      if (plan.workouts.isEmpty()) {
        item(key = "start") {
          StartOptions(
            onBlank = { vm.addWorkout() },
            onTemplate = { showTemplates = true },
            onPaste = { showPasteImport = true },
            onImportFile = { importLauncher.launch(arrayOf("*/*")) }
          )
        }
      }

      // ── §1.2 Program structure ──────────────────────────────────────────
      if (plan.workouts.isNotEmpty()) {
        item(key = "structure_header") {
          Text(
            "PROGRAM STRUCTURE",
            style = MaterialTheme.typography.labelMedium,
            color = c.textSecondary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
          )
        }

        workoutList(
          workouts = plan.workouts,
          onOpen = { editingWorkoutId = it },
          onMove = { id, to -> vm.moveWorkout(id, to) },
          onDuplicate = { vm.duplicateWorkout(it) },
          onDelete = { confirmDelete = it },
          onRename = { id, name -> vm.renameWorkout(id, name) },
          onAssignDay = { id, day -> vm.setWorkoutWeekday(id, day) }
        )
      }

      // Paste / upload stays available for the WHOLE life of the program, not just while it is
      // empty. Pulling a day out of a coach's message or a spreadsheet is something people do
      // part-way through building, and burying it in the overflow menu made it undiscoverable
      // the moment the first workout was added.
      // Only once the start options are gone — while the program is empty they already offer both,
      // and showing them twice on one screen is just noise.
      if (plan.workouts.isNotEmpty()) item(key = "paste_upload") {
        Row(
          Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedButton(
            onClick = { showPasteImport = true },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
          ) {
            Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Paste text", fontWeight = FontWeight.Bold)
          }
          OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
          ) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Upload file", fontWeight = FontWeight.Bold)
          }
        }
      }

      item(key = "add_workout") {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
          OutlinedButton(
            onClick = { showAddMenu = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
          ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add workout", fontWeight = FontWeight.Bold)
          }
          DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
            DropdownMenuItem(
              text = { Text("Create new workout") },
              leadingIcon = { Icon(Icons.Default.Add, null) },
              onClick = {
                showAddMenu = false
                editingWorkoutId = vm.addWorkout()
              }
            )
            DropdownMenuItem(
              text = { Text("Add rest day") },
              leadingIcon = { Icon(Icons.Default.Hotel, null) },
              onClick = { showAddMenu = false; vm.addWorkout(kind = WorkoutKind.REST) }
            )
            DropdownMenuItem(
              text = { Text("Add deload day") },
              leadingIcon = { Icon(Icons.Default.TrendingDown, null) },
              onClick = { showAddMenu = false; vm.addWorkout(kind = WorkoutKind.DELOAD) }
            )
            if (plan.workouts.any { !it.isRest }) {
              HorizontalDivider()
              plan.workouts.filter { !it.isRest }.take(6).forEach { w ->
                DropdownMenuItem(
                  text = { Text("Duplicate ${w.name.ifBlank { "workout" }}") },
                  leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                  onClick = { showAddMenu = false; vm.duplicateWorkout(w.id) }
                )
              }
            }
          }
        }
      }

      // ── §3.1 / §3.2 optional program settings, collapsed ────────────────
      if (plan.workouts.isNotEmpty()) {
        item(key = "settings_rows") {
          Column(Modifier.padding(horizontal = 20.dp)) {
            SettingRow("Scheduling", schedulingSummary(plan)) { showScheduling = true }
            SettingRow("Progression", plan.progression.method.display) { showProgression = true }
          }
        }
      }

      // ── §1.7 live summary ───────────────────────────────────────────────
      if (plan.workouts.isNotEmpty()) {
        item(key = "summary") {
          SummaryPanel(
            summary = summary,
            expanded = showSummary,
            onToggle = { showSummary = !showSummary }
          )
        }
      }
    }
  }
}

// ── Program header ───────────────────────────────────────────────────────────

@Composable
private fun ProgramHeader(
  plan: PlanProgram,
  vm: BuilderViewModel,
  expanded: Boolean,
  onToggleExpanded: () -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current

  Column(Modifier.padding(horizontal = 20.dp)) {
    // The name is the only required field, edited in place — no settings screen, no dialog.
    OutlinedTextField(
      value = plan.name,
      onValueChange = vm::setName,
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text(PlanProgram.DEFAULT_NAME) },
      label = { Text("Program name") },
      singleLine = true,
      textStyle = MaterialTheme.typography.headlineSmall,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
      value = plan.description,
      onValueChange = vm::setDescription,
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("Description (optional)") },
      maxLines = 3,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
    )

    Spacer(Modifier.height(8.dp))

    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .clickable { onToggleExpanded() },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        headerSummaryLine(plan),
        style = MaterialTheme.typography.bodyMedium,
        color = c.textSecondary,
        modifier = Modifier.weight(1f)
      )
      Icon(
        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        if (expanded) "Hide program details" else "Show program details",
        tint = c.textSecondary
      )
    }

    AnimatedVisibility(visible = expanded) {
      Column {
        LabelledChips(
          label = "Goal",
          options = ProgramGoal.entries.map { it.display },
          selectedIndex = ProgramGoal.entries.indexOf(plan.goal),
          onSelect = { vm.setGoal(ProgramGoal.entries[it]) }
        )
        LabelledChips(
          label = "Experience",
          options = ExperienceLevel.entries.map { it.display },
          selectedIndex = ExperienceLevel.entries.indexOf(plan.experience),
          onSelect = { vm.setExperience(ExperienceLevel.entries[it]) }
        )
        LabelledChips(
          label = "Length",
          options = listOf("Ongoing", "4 weeks", "8 weeks", "12 weeks", "16 weeks"),
          selectedIndex = when (plan.durationWeeks) {
            0 -> 0; 4 -> 1; 8 -> 2; 12 -> 3; 16 -> 4; else -> -1
          },
          onSelect = { vm.setDurationWeeks(listOf(0, 4, 8, 12, 16)[it]) }
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Track RPE", color = c.text, modifier = Modifier.weight(1f))
          Switch(checked = plan.trackRpe, onCheckedChange = vm::setTrackRpe)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Track % of 1RM", color = c.text, modifier = Modifier.weight(1f))
          Switch(checked = plan.trackOneRm, onCheckedChange = vm::setTrackOneRm)
        }
      }
    }
  }
}

/** "5 training days · Ongoing" — the rendered summary the brief asks for. */
private fun headerSummaryLine(plan: PlanProgram): String = buildString {
  val days = ProgramMath.trainingDaysPerWeek(plan)
  append(if (days == 0) "No training days yet" else "$days training day${if (days == 1) "" else "s"}")
  append(" · ")
  append(if (plan.isOngoing) "Ongoing" else "${plan.durationWeeks} week${if (plan.durationWeeks != 1) "s" else ""}")
  append(" · ")
  append(plan.goal.display)
}

private fun schedulingSummary(plan: PlanProgram): String {
  val assigned = plan.workouts.count { it.weekday > 0 }
  return if (assigned == 0) "Sequential" else "$assigned assigned to weekdays"
}

// ── Structure list ───────────────────────────────────────────────────────────

/**
 * The workout list with long-press drag reorder.
 *
 * Drag is implemented with plain `pointerInput` rather than a reorder library (§2: no new heavy
 * dependencies). Cards are a uniform height, so the target index is just the drag offset divided by
 * that height. Every drag action is also available from the overflow menu ("Move up"/"Move down"),
 * which is the accessibility path required by §9 — reordering never depends on a gesture.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.workoutList(
  workouts: List<PlanWorkout>,
  onOpen: (String) -> Unit,
  onMove: (String, Int) -> Unit,
  onDuplicate: (String) -> Unit,
  onDelete: (String) -> Unit,
  onRename: (String, String) -> Unit,
  onAssignDay: (String, Int) -> Unit
) {
  itemsIndexed(workouts, key = { _, w -> w.id }) { index, workout ->
    WorkoutCard(
      workout = workout,
      index = index,
      lastIndex = workouts.lastIndex,
      onOpen = { onOpen(workout.id) },
      onMoveBy = { delta -> onMove(workout.id, index + delta) },
      onDuplicate = { onDuplicate(workout.id) },
      onDelete = { onDelete(workout.id) },
      onRename = { onRename(workout.id, it) },
      onAssignDay = { onAssignDay(workout.id, it) }
    )
  }
}

private val WEEKDAYS = listOf("—", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
private fun WorkoutCard(
  workout: PlanWorkout,
  index: Int,
  lastIndex: Int,
  onOpen: () -> Unit,
  onMoveBy: (Int) -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
  onRename: (String) -> Unit,
  onAssignDay: (Int) -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  val haptics = LocalHapticFeedback.current
  var menuOpen by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf(false) }
  var dayMenu by remember { mutableStateOf(false) }
  var dragOffset by remember { mutableFloatStateOf(0f) }

  if (renaming) {
    var draft by remember { mutableStateOf(workout.name) }
    AlertDialog(
      onDismissRequest = { renaming = false },
      title = { Text("Rename workout") },
      text = {
        OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
      },
      confirmButton = {
        TextButton(onClick = { onRename(draft); renaming = false }) { Text("Rename") }
      },
      dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
    )
  }

  val minutes = ProgramMath.estimateWorkoutMinutes(workout)

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 4.dp)
      // Reading the offset in the layer lambda keeps the drag in the draw phase.
      .graphicsLayer { translationY = dragOffset }
      .clip(RoundedCornerShape(12.dp))
      .background(if (workout.isRest) c.surface2 else c.surface)
      .clickable(enabled = !workout.isRest) { onOpen() }
      .heightIn(min = 64.dp)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      Icons.Default.DragIndicator,
      contentDescription = "Reorder ${workout.name}. Use the menu to move up or down.",
      tint = c.textTertiary,
      modifier = Modifier
        .size(24.dp)
        .pointerInput(workout.id, index, lastIndex) {
          detectDragGesturesAfterLongPressCompat(
            onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onDrag = { dragOffset += it },
            onEnd = {
              val steps = (dragOffset / ROW_HEIGHT_PX).roundToInt()
              dragOffset = 0f
              if (steps != 0) onMoveBy(steps)
            }
          )
        }
    )
    Spacer(Modifier.width(10.dp))

    Text(
      if (workout.weekday > 0) WEEKDAYS[workout.weekday] else "${index + 1}",
      style = MaterialTheme.typography.labelMedium,
      color = c.textSecondary,
      modifier = Modifier.width(36.dp)
    )

    Column(Modifier.weight(1f)) {
      Text(
        workout.name.ifBlank { "Untitled" },
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = c.text
      )
      Text(
        when {
          workout.isRest -> "Rest"
          workout.exercises.isEmpty() -> "No exercises yet"
          else -> buildString {
            append("${workout.exerciseCount} exercise${if (workout.exerciseCount == 1) "" else "s"}")
            if (minutes > 0) append(" · ~${minutes} min")
            val muscles = PlanMapper.muscleSummary(workout)
            if (muscles.isNotBlank()) append(" · $muscles")
          }
        },
        style = MaterialTheme.typography.bodySmall,
        color = c.textSecondary
      )
    }

    // Complete / empty indicator.
    if (!workout.isRest) {
      Icon(
        if (workout.isEmpty) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
        contentDescription = if (workout.isEmpty) "Empty" else "Has exercises",
        tint = if (workout.isEmpty) c.textTertiary else accent,
        modifier = Modifier.size(18.dp)
      )
    }

    Box {
      IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.MoreVert, "Options for ${workout.name}", tint = c.textSecondary)
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (!workout.isRest) {
          DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onOpen() },
            leadingIcon = { Icon(Icons.Default.Edit, null) })
        }
        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true },
          leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) })
        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() },
          leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
        DropdownMenuItem(
          text = { Text("Move up") },
          enabled = index > 0,
          onClick = { menuOpen = false; onMoveBy(-1) },
          leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }
        )
        DropdownMenuItem(
          text = { Text("Move down") },
          enabled = index < lastIndex,
          onClick = { menuOpen = false; onMoveBy(1) },
          leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }
        )
        DropdownMenuItem(
          text = { Text("Assign day") },
          onClick = { menuOpen = false; dayMenu = true },
          leadingIcon = { Icon(Icons.Default.CalendarMonth, null) }
        )
        HorizontalDivider()
        DropdownMenuItem(
          text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
          onClick = { menuOpen = false; onDelete() },
          leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
        )
      }
      DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
        WEEKDAYS.forEachIndexed { i, label ->
          DropdownMenuItem(
            text = { Text(if (i == 0) "No fixed day" else label) },
            onClick = { dayMenu = false; onAssignDay(i) }
          )
        }
      }
    }
  }
}

/** Card height in pixels, used to turn a drag distance into a number of positions. */
private const val ROW_HEIGHT_PX = 190f

/**
 * Thin wrapper over the long-press drag detector so the call site stays readable and the delta is
 * reported as a single float (vertical only — this list never reorders horizontally).
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragGesturesAfterLongPressCompat(
  onStart: () -> Unit,
  onDrag: (Float) -> Unit,
  onEnd: () -> Unit
) {
  detectDragGesturesAfterLongPress(
    onDragStart = { onStart() },
    onDragEnd = { onEnd() },
    onDragCancel = { onEnd() },
    onDrag = { change, amount -> change.consume(); onDrag(amount.y) }
  )
}

// ── Start options / summary / bottom bar ─────────────────────────────────────

@Composable
private fun StartOptions(
  onBlank: () -> Unit,
  onTemplate: () -> Unit,
  onPaste: () -> Unit,
  onImportFile: () -> Unit
) {
  val c = LocalAppColors.current
  Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
    Text("START WITH", style = MaterialTheme.typography.labelMedium,
      color = c.textSecondary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    StartOptionRow(Icons.Default.ContentPaste, "Paste a program",
      "Copy it from anywhere — parsed on your device", onPaste)
    StartOptionRow(Icons.Default.FolderOpen, "Open a file",
      "Screenshot, photo, PDF, spreadsheet or text", onImportFile)
    StartOptionRow(Icons.Default.Dashboard, "Use a template",
      "PPL, upper/lower, full body and more", onTemplate)
    StartOptionRow(Icons.Default.Add, "Start blank",
      "Build it day by day", onBlank)
  }
}

@Composable
private fun StartOptionRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  Row(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(c.surface)
      .clickable { onClick() }
      .heightIn(min = 56.dp)
      .padding(14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, color = c.text, fontWeight = FontWeight.Bold)
      Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
    Icon(Icons.Default.KeyboardArrowRight, null, tint = c.textTertiary)
  }
}

@Composable
private fun SummaryPanel(summary: ProgramSummary, expanded: Boolean, onToggle: () -> Unit) {
  val c = LocalAppColors.current
  Column(
    Modifier
      .padding(horizontal = 20.dp, vertical = 12.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(c.surface)
      .padding(14.dp)
  ) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onToggle() },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(summary.headline, color = c.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      Icon(
        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        if (expanded) "Hide summary" else "Show summary",
        tint = c.textSecondary
      )
    }

    AnimatedVisibility(visible = expanded) {
      Column {
        Spacer(Modifier.height(8.dp))
        summary.volume.take(8).forEach { (muscle, sets) ->
          Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(muscle.display, color = c.textSecondary, modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodySmall)
            Text(
              "${if (sets % 1f == 0f) sets.toInt().toString() else String.format("%.1f", sets)} sets",
              color = c.text, style = MaterialTheme.typography.bodySmall
            )
          }
        }
        if (summary.equipment.isNotEmpty()) {
          Spacer(Modifier.height(8.dp))
          Text("Equipment: ${summary.equipment.joinToString(", ")}",
            color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (summary.warnings.isNotEmpty()) {
          Spacer(Modifier.height(10.dp))
          summary.warnings.forEach {
            Text("· $it", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}

@Composable
private fun BuilderBottomBar(
  summary: ProgramSummary,
  onSave: () -> Unit,
  onSaveAndStart: () -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  Surface(color = c.surface) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
      // Warnings are informational and sit ABOVE the buttons — they never disable them.
      if (summary.warnings.isNotEmpty()) {
        Text(
          summary.warnings.first() + ". You can finish configuring it later.",
          style = MaterialTheme.typography.bodySmall,
          color = c.textSecondary
        )
        Spacer(Modifier.height(10.dp))
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = onSave,
          modifier = Modifier.weight(1f).heightIn(min = 48.dp),
          shape = RoundedCornerShape(12.dp)
        ) { Text("Save program") }
        Button(
          onClick = onSaveAndStart,
          modifier = Modifier.weight(1f).heightIn(min = 48.dp),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) { Text("Save and start", fontWeight = FontWeight.Bold) }
      }
    }
  }
}

@Composable
private fun SaveStatusLabel(status: SaveStatus) {
  val c = LocalAppColors.current
  // Deliberately says "locally" and never shows a spinner — there is no server to wait for.
  val text = when (status) {
    SaveStatus.IDLE -> ""
    SaveStatus.SAVING -> "Saving…"
    SaveStatus.SAVED -> "Saved locally"
  }
  if (text.isNotEmpty()) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
  }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
  val c = LocalAppColors.current
  Row(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(c.surface)
      .clickable { onClick() }
      .heightIn(min = 48.dp)
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(title, color = c.text, modifier = Modifier.weight(1f))
    Text(value, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.width(6.dp))
    Icon(Icons.Default.KeyboardArrowRight, null, tint = c.textTertiary)
  }
}

@Composable
internal fun LabelledChips(
  label: String,
  options: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  Column(Modifier.padding(vertical = 6.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
    Spacer(Modifier.height(6.dp))
    FlowRowSimple {
      options.forEachIndexed { i, option ->
        val selected = i == selectedIndex
        Surface(
          onClick = { onSelect(i) },
          shape = RoundedCornerShape(20.dp),
          color = if (selected) accent else c.surface2,
          modifier = Modifier.padding(end = 8.dp, bottom = 8.dp).heightIn(min = 40.dp)
        ) {
          Text(
            option,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (selected) Color.White else c.text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
          )
        }
      }
    }
  }
}

/** Minimal wrapping row — avoids pulling in the experimental FlowRow for three chip rows. */
@Composable
internal fun FlowRowSimple(content: @Composable () -> Unit) {
  androidx.compose.foundation.layout.FlowRow(
    modifier = Modifier.fillMaxWidth(),
    content = { content() }
  )
}
