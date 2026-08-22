@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildodds.gymtracker.ui.builder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.data.builder.*
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.LocalAppColors

/**
 * Editing one workout (§2.2).
 *
 * Everything that matters — set count, reps, load, rest, set type — is visible on the card without
 * opening a secondary editor. "Configure" reveals the rest (tempo, RPE, per-set notes) and stays
 * collapsed for the beginner who never needs it.
 */
@Composable
fun WorkoutEditorSheet(
  workout: PlanWorkout,
  allWorkouts: List<PlanWorkout>,
  vm: BuilderViewModel,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current

  var showPicker by remember { mutableStateOf(false) }
  var confirmDeleteExercise by remember { mutableStateOf<String?>(null) }

  if (showPicker) {
    ExercisePickerSheet(
      alreadyInWorkout = workout.exercises.map { it.name }.toSet(),
      recents = allWorkouts.flatMap { it.exercises }.map { it.name }.distinct().take(20),
      onAdd = { names -> vm.addExercises(workout.id, names) },
      onDismiss = { showPicker = false }
    )
  }

  confirmDeleteExercise?.let { exId ->
    val ex = workout.exercises.firstOrNull { it.id == exId }
    AlertDialog(
      onDismissRequest = { confirmDeleteExercise = null },
      title = { Text("Remove ${ex?.name.orEmpty().ifBlank { "this exercise" }}?") },
      text = { Text("You can undo straight after.") },
      confirmButton = {
        TextButton(
          onClick = { vm.deleteExercise(workout.id, exId); confirmDeleteExercise = null },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { confirmDeleteExercise = null }) { Text("Cancel") } }
    )
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(Modifier.fillMaxWidth().heightIn(max = 700.dp)) {

      OutlinedTextField(
        value = workout.name,
        onValueChange = { vm.renameWorkout(workout.id, it) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        label = { Text("Workout name") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
      )

      Spacer(Modifier.height(6.dp))
      Text(
        "~${ProgramMath.estimateWorkoutMinutes(workout)} min · " +
          "${workout.exerciseCount} exercise${if (workout.exerciseCount == 1) "" else "s"}",
        style = MaterialTheme.typography.bodySmall,
        color = c.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp)
      )

      LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
        items(count = workout.exercises.size, key = { workout.exercises[it].id }) { i ->
          val ex = workout.exercises[i]
          ExerciseCard(
            exercise = ex,
            index = i,
            lastIndex = workout.exercises.lastIndex,
            workout = workout,
            allWorkouts = allWorkouts,
            vm = vm,
            onDelete = { confirmDeleteExercise = ex.id }
          )
        }

        item(key = "add_exercise") {
          Box(Modifier.padding(20.dp)) {
            Button(
              onClick = { showPicker = true },
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
              Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text("Add exercises", fontWeight = FontWeight.Bold)
            }
          }
        }
      }

      Surface(color = c.surface) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
          Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent)
          ) { Text("Done", fontWeight = FontWeight.Bold) }
        }
      }
    }
  }
}

@Composable
private fun ExerciseCard(
  exercise: PlanExercise,
  index: Int,
  lastIndex: Int,
  workout: PlanWorkout,
  allWorkouts: List<PlanWorkout>,
  vm: BuilderViewModel,
  onDelete: () -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  var configureOpen by remember { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }
  var notesOpen by remember { mutableStateOf(exercise.notes.isNotBlank()) }
  var moveMenu by remember { mutableStateOf(false) }
  var altOpen by remember { mutableStateOf(false) }

  val group = exercise.supersetGroupId?.let { id -> workout.groups.firstOrNull { it.id == id } }

  if (altOpen) {
    AlternativesDialog(
      exercise = exercise,
      onSave = { vm.setAlternatives(workout.id, exercise.id, it); altOpen = false },
      onDismiss = { altOpen = false }
    )
  }

  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 6.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(c.surface)
      .padding(12.dp)
  ) {
    if (group != null) {
      Text(
        "SUPERSET ${group.label} · ${group.rounds} rounds · ${group.restAfterRoundSeconds}s after round",
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        fontWeight = FontWeight.Bold
      )
      Spacer(Modifier.height(6.dp))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          exercise.name.ifBlank { "Untitled exercise" },
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Bold,
          color = c.text
        )
        Text(
          buildString {
            val info = com.wildodds.gymtracker.data.ExerciseLibrary.lookup(exercise.name)
            append(info?.primary?.firstOrNull()?.display ?: "Not in library")
            EquipmentGuessAccess.of(exercise.name)?.let { append(" · $it") }
            if (exercise.isOptional) append(" · optional")
          },
          style = MaterialTheme.typography.bodySmall,
          color = c.textSecondary
        )
      }
      Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Default.MoreVert, "Options for ${exercise.name}", tint = c.textSecondary)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(text = { Text("Edit prescription") },
            onClick = { menuOpen = false; configureOpen = true },
            leadingIcon = { Icon(Icons.Default.Tune, null) })
          DropdownMenuItem(text = { Text("Duplicate") },
            onClick = { menuOpen = false; vm.duplicateExercise(workout.id, exercise.id) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
          DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0,
            onClick = { menuOpen = false; vm.moveExercise(workout.id, exercise.id, -1) },
            leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) })
          DropdownMenuItem(text = { Text("Move down") }, enabled = index < lastIndex,
            onClick = { menuOpen = false; vm.moveExercise(workout.id, exercise.id, 1) },
            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) })
          DropdownMenuItem(text = { Text("Add alternative") },
            onClick = { menuOpen = false; altOpen = true },
            leadingIcon = { Icon(Icons.Default.SwapHoriz, null) })
          if (group == null) {
            DropdownMenuItem(
              text = { Text("Add to superset") },
              onClick = { menuOpen = false; vm.createGroupWith(workout.id, listOf(exercise.id)) },
              leadingIcon = { Icon(Icons.Default.Link, null) }
            )
          } else {
            DropdownMenuItem(
              text = { Text("Remove from superset") },
              onClick = { menuOpen = false; vm.ungroup(workout.id, group.id) },
              leadingIcon = { Icon(Icons.Default.LinkOff, null) }
            )
          }
          DropdownMenuItem(
            text = { Text(if (exercise.isOptional) "Make required" else "Mark optional") },
            onClick = {
              menuOpen = false
              vm.setExerciseOptional(workout.id, exercise.id, !exercise.isOptional)
            },
            leadingIcon = { Icon(Icons.Default.Flag, null) }
          )
          if (allWorkouts.size > 1) {
            DropdownMenuItem(text = { Text("Move to another workout") },
              onClick = { menuOpen = false; moveMenu = true },
              leadingIcon = { Icon(Icons.Default.ExitToApp, null) })
          }
          HorizontalDivider()
          DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { menuOpen = false; onDelete() },
            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
          )
        }
        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
          allWorkouts.filter { it.id != workout.id && !it.isRest }.forEach { target ->
            DropdownMenuItem(
              text = { Text(target.name.ifBlank { "Untitled" }) },
              onClick = {
                moveMenu = false
                vm.moveExerciseToWorkout(workout.id, exercise.id, target.id)
              }
            )
          }
        }
      }
    }

    Spacer(Modifier.height(8.dp))

    // ── EVOLVING SETS ────────────────────────────────────────────────────────
    // Shape first, numbers second. Choosing "Warm-up + working" or "Top set + back-off" writes the
    // whole ladder at once, so a real prescription is two taps rather than eight rows of typing.
    ShapeRow(
      shape = SetShape.detect(exercise.sets),
      workingSets = exercise.workingSetCount,
      topLoad = exercise.topLoad,
      onShape = { vm.applyShape(workout.id, exercise.id, it) },
      onWorkingSets = { vm.changeWorkingSets(workout.id, exercise.id, it) },
      onTopLoad = { vm.setTopLoad(workout.id, exercise.id, it) }
    )

    Spacer(Modifier.height(8.dp))

    // Set rows — visible without opening anything.
    exercise.sets.forEachIndexed { i, set ->
      SetRow(
        set = set,
        number = i + 1,
        topLoad = exercise.topLoad,
        showAdvanced = configureOpen,
        onChange = { transform -> vm.updateSet(workout.id, exercise.id, set.id, transform) },
        onRemove = { vm.removeSet(workout.id, exercise.id, set.id) },
        onApplyToAll = { vm.applySetToAll(workout.id, exercise.id, set.id) }
      )
    }

    if (exercise.sets.isEmpty()) {
      Text("No sets yet — pick a preset below.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
      Spacer(Modifier.height(6.dp))
      SetPreset.entries.forEach { preset ->
        AssistChip(
          onClick = { vm.applyPreset(workout.id, exercise.id, preset) },
          label = { Text(preset.label) },
          modifier = Modifier.padding(end = 6.dp)
        )
      }
    }

    Spacer(Modifier.height(6.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { vm.addSet(workout.id, exercise.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Add set", color = accent)
      }
      TextButton(onClick = { configureOpen = !configureOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (configureOpen) "Hide" else "Configure", color = c.textSecondary)
      }
      TextButton(onClick = { notesOpen = !notesOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Notes", color = c.textSecondary)
      }
    }

    AnimatedVisibility(visible = configureOpen) {
      Column {
        Text("Bulk actions", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        FlowRowSimple {
          AssistChip(onClick = { vm.duplicateLastSet(workout.id, exercise.id) },
            label = { Text("Duplicate last set") }, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp))
          AssistChip(onClick = { vm.convertRepsToRange(workout.id, exercise.id) },
            label = { Text("Reps → range") }, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp))
          listOf(60, 90, 120, 180).forEach { s ->
            AssistChip(onClick = { vm.setRestForAll(workout.id, exercise.id, s) },
              label = { Text("Rest ${s}s") }, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp))
          }
        }
        Spacer(Modifier.height(4.dp))
        Text("Presets", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        FlowRowSimple {
          SetPreset.entries.forEach { preset ->
            AssistChip(
              onClick = { vm.applyPreset(workout.id, exercise.id, preset) },
              label = { Text(preset.label) },
              modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
            )
          }
        }
        if (group != null) {
          Spacer(Modifier.height(6.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rounds", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            IconButton(
              onClick = { vm.updateGroup(workout.id, group.id) { it.copy(rounds = (it.rounds - 1).coerceAtLeast(1)) } },
              modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Default.Remove, "One fewer round", tint = c.text) }
            Text("${group.rounds}", color = c.text, fontWeight = FontWeight.Bold)
            IconButton(
              onClick = { vm.updateGroup(workout.id, group.id) { it.copy(rounds = it.rounds + 1) } },
              modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Default.Add, "One more round", tint = c.text) }
          }
        }
      }
    }

    AnimatedVisibility(visible = notesOpen) {
      OutlinedTextField(
        value = exercise.notes,
        onValueChange = { vm.setExerciseNotes(workout.id, exercise.id, it) },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        placeholder = { Text("Notes") },
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
      )
    }

    if (exercise.alternatives.isNotEmpty()) {
      Spacer(Modifier.height(6.dp))
      Text("Alternatives: ${exercise.alternatives.joinToString(", ")}",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
  }
}

@Composable
private fun SetRow(
  set: PlanSet,
  number: Int,
  topLoad: Float?,
  showAdvanced: Boolean,
  onChange: ((PlanSet) -> PlanSet) -> Unit,
  onRemove: () -> Unit,
  onApplyToAll: () -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  var typeMenu by remember { mutableStateOf(false) }

  Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Set type — the pill that separates a warm-up from a working set.
    Box {
      Surface(
        onClick = { typeMenu = true },
        shape = RoundedCornerShape(8.dp),
        color = if (set.type == SetType.WARMUP) c.surface2 else accent.copy(alpha = 0.12f),
        modifier = Modifier.width(84.dp).heightIn(min = 40.dp)
      ) {
        Text(
          set.type.display,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
          style = MaterialTheme.typography.labelSmall,
          color = if (set.type == SetType.WARMUP) c.textSecondary else accent
        )
      }
      DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
        SetType.entries.forEach { t ->
          DropdownMenuItem(
            text = { Text(t.display) },
            onClick = { typeMenu = false; onChange { it.copy(type = t) } }
          )
        }
      }
    }

    Spacer(Modifier.width(6.dp))
    if (set.loadPercent != null) {
      // A ramped row shows what its percentage works out to, so the prescriber sees real weights
      // rather than doing arithmetic in their head. Read-only: the ladder is driven by the top set.
      ResolvedLoadCell(percent = set.loadPercent, topLoad = topLoad, modifier = Modifier.weight(1f))
    } else {
      CompactField(set.load, "Load", Modifier.weight(1f)) { v -> onChange { it.copy(load = v) } }
    }
    Spacer(Modifier.width(6.dp))
    CompactField(set.reps, "Reps", Modifier.weight(1f)) { v -> onChange { it.copy(reps = v) } }
    Spacer(Modifier.width(6.dp))
    CompactField(
      set.restSeconds.toString(), "Rest", Modifier.width(64.dp), numeric = true
    ) { v -> onChange { it.copy(restSeconds = v.toIntOrNull()?.coerceIn(0, 600) ?: it.restSeconds) } }

    IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
      Icon(Icons.Default.Close, "Remove set $number", tint = c.textTertiary, modifier = Modifier.size(16.dp))
    }
  }

  if (showAdvanced) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Spacer(Modifier.width(90.dp))
      CompactField(set.rpe, "RPE", Modifier.weight(1f)) { v -> onChange { it.copy(rpe = v) } }
      Spacer(Modifier.width(6.dp))
      CompactField(set.tempo, "Tempo", Modifier.weight(1f)) { v -> onChange { it.copy(tempo = v) } }
      Spacer(Modifier.width(6.dp))
      TextButton(onClick = onApplyToAll, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Apply to all", style = MaterialTheme.typography.labelSmall, color = accent)
      }
    }
  }
}

@Composable
private fun CompactField(
  value: String,
  label: String,
  modifier: Modifier = Modifier,
  numeric: Boolean = false,
  onChange: (String) -> Unit
) {
  val accent = LocalAccentColor.current
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = modifier.heightIn(min = 48.dp),
    placeholder = { Text(label, style = MaterialTheme.typography.labelSmall) },
    singleLine = true,
    textStyle = MaterialTheme.typography.bodySmall,
    shape = RoundedCornerShape(8.dp),
    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
    keyboardOptions = KeyboardOptions(
      keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
      imeAction = ImeAction.Next
    )
  )
}

/** §3.3 — manually-chosen swaps, kept as plain names so anything can be an alternative. */
@Composable
private fun AlternativesDialog(
  exercise: PlanExercise,
  onSave: (List<String>) -> Unit,
  onDismiss: () -> Unit
) {
  var draft by remember { mutableStateOf(exercise.alternatives.joinToString("\n")) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Alternatives for ${exercise.name.ifBlank { "this exercise" }}") },
    text = {
      Column {
        Text(
          "One per line. These are offered as swaps during the workout — the prescription stays " +
            "the same when you switch.",
          style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          modifier = Modifier.fillMaxWidth(),
          placeholder = { Text("Dumbbell Bench Press\nChest Press Machine\nPush-up") },
          minLines = 3
        )
      }
    },
    confirmButton = {
      TextButton(onClick = {
        onSave(draft.lines().map { it.trim() }.filter { it.isNotBlank() })
      }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// EVOLVING SETS — prescribing controls
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The three decisions that define an evolving prescription, on one row: what SHAPE the sets take,
 * how MANY working sets, and the WEIGHT everything else is a proportion of.
 *
 * Ordering is deliberate — pick the shape and the rows appear already filled in, then adjust the
 * count, then (optionally) type one weight and the whole ladder resolves to real numbers.
 */
@Composable
private fun ShapeRow(
  shape: SetShape,
  workingSets: Int,
  topLoad: Float?,
  onShape: (SetShape) -> Unit,
  onWorkingSets: (Int) -> Unit,
  onTopLoad: (String) -> Unit
) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  var shapeMenu by remember { mutableStateOf(false) }

  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box {
        Surface(
          onClick = { shapeMenu = true },
          shape = RoundedCornerShape(8.dp),
          color = c.surface2,
          modifier = Modifier.heightIn(min = 40.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
          ) {
            Text(shape.display, style = MaterialTheme.typography.labelMedium, color = c.text)
            Icon(Icons.Default.ArrowDropDown, "Change set shape", tint = c.textSecondary,
              modifier = Modifier.size(18.dp))
          }
        }
        DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
          SetShape.entries.forEach { option ->
            DropdownMenuItem(
              text = {
                Column {
                  Text(option.display, fontWeight =
                    if (option == shape) FontWeight.Bold else FontWeight.Normal)
                  Text(option.explanation, style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary)
                }
              },
              onClick = { shapeMenu = false; onShape(option) }
            )
          }
        }
      }

      Spacer(Modifier.weight(1f))

      // Working-set count. Warm-ups and drops are added by the shape, so this only ever counts
      // the sets that are actually the work.
      IconButton(onClick = { onWorkingSets(-1) }, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.Remove, "One fewer working set", tint = c.text, modifier = Modifier.size(16.dp))
      }
      Text("$workingSets", style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold, color = c.text)
      IconButton(onClick = { onWorkingSets(1) }, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.Add, "One more working set", tint = c.text, modifier = Modifier.size(16.dp))
      }
      Text("sets", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }

    if (shape != SetShape.STRAIGHT) {
      Spacer(Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Working weight", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
          value = topLoad?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "",
          onValueChange = onTopLoad,
          modifier = Modifier.width(96.dp).heightIn(min = 48.dp),
          placeholder = { Text("kg", style = MaterialTheme.typography.labelSmall) },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodySmall,
          shape = RoundedCornerShape(8.dp),
          colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
        )
        Spacer(Modifier.width(8.dp))
        Text(
          if (topLoad == null) "Optional — leave blank to prescribe by percentage"
          else "Every set scales from this",
          style = MaterialTheme.typography.labelSmall,
          color = c.textTertiary
        )
      }
    }
  }
}

/**
 * What a percentage row works out to. Shows the resolved weight once there is a working weight to
 * scale from, and the bare percentage until then — never a made-up number.
 */
@Composable
private fun ResolvedLoadCell(percent: Int, topLoad: Float?, modifier: Modifier = Modifier) {
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  val resolved = SetShape.resolveLoad(topLoad, percent)

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = c.surface2,
    modifier = modifier.heightIn(min = 48.dp)
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        resolved?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "$percent%",
        style = MaterialTheme.typography.bodySmall,
        color = if (resolved != null) c.text else accent
      )
      if (resolved != null) {
        Text("$percent%", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
      }
    }
  }
}
