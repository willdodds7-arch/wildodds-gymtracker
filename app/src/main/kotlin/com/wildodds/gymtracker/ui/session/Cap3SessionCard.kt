@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.data.cap3.Cap3Emom
import com.wildodds.gymtracker.data.cap3.Cap3Engine
import com.wildodds.gymtracker.data.cap3.Cap3LiftBlock
import com.wildodds.gymtracker.data.cap3.Cap3Role
import com.wildodds.gymtracker.data.cap3.Cap3Set
import com.wildodds.gymtracker.data.cap3.Cap3SetType
import com.wildodds.gymtracker.data.cap3.Cap3Units
import com.wildodds.gymtracker.ui.cap3.Cap3SetBadge
import com.wildodds.gymtracker.ui.cap3.formatWeight
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.LocalAppColors

/**
 * A CAP3 main lift inside the normal session screen.
 *
 * Weights are priced from the user's current training max every time this renders, so a max that
 * moves mid-session is reflected straight away. Each prescribed set has a checkbox, and ticking one
 * logs it at the prescribed weight and reps — the tick *is* the log, so history and volume see an
 * ordinary set. AMRAP sets take a rep count instead, which can move the training max on its own.
 */
@Composable
fun Cap3SessionCard(
  exerciseState: ExerciseUiState,
  exerciseIndex: Int,
  block: Cap3LiftBlock,
  vm: SessionViewModel
) {
  val colors = LocalAppColors.current
  val accent = LocalAccentColor.current
  val units = block.units

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            exerciseState.exercise.name,
            fontWeight = FontWeight.Bold,
            color = colors.text,
            fontSize = 20.sp
          )
          if (block.isMajorEffort) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Bolt, "Major-effort day", tint = accent, modifier = Modifier.size(20.dp))
          }
        }
        Text(
          when (block.role) {
            Cap3Role.T1 -> if (block.isMajorEffort) "Major effort" else "Main lift"
            Cap3Role.T2 -> "Secondary lift"
            Cap3Role.EMOM -> "EMOM"
          },
          color = colors.textSecondary,
          style = MaterialTheme.typography.labelSmall
        )
      }
      Text(
        "TM ${formatWeight(block.trainingMax, units)}",
        color = colors.textSecondary,
        style = MaterialTheme.typography.labelSmall
      )
    }

    Spacer(Modifier.height(16.dp))

    when {
      block.role == Cap3Role.EMOM && block.emom != null ->
        Cap3EmomBlock(exerciseState, exerciseIndex, block.emom!!, units, vm)
      block.isMajorEffort ->
        Cap3MajorEffortBlock(exerciseState, exerciseIndex, block, units, vm)
      else ->
        Cap3SetList(exerciseState, exerciseIndex, block, units, vm)
    }

    Spacer(Modifier.height(48.dp))
  }
}

// ── The ordinary case: a list of prescribed sets ──────────────────────────────

@Composable
private fun Cap3SetList(
  exerciseState: ExerciseUiState,
  exerciseIndex: Int,
  block: Cap3LiftBlock,
  units: Cap3Units,
  vm: SessionViewModel
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    block.sets.forEachIndexed { index, set ->
      val row = exerciseState.sets.getOrNull(index)
      Cap3SetRow(
        set = set,
        units = units,
        // A set already logged keeps showing the weight it was actually done at. Without this an
        // AMRAP that raises the training max would silently re-price the sets behind it, so work
        // finished at the old weight would read as if it had been done at the new one.
        loggedWeight = row?.weightKg?.toDoubleOrNull(),
        loggedReps = row?.reps?.toIntOrNull(),
        isChecked = !row?.weightKg.isNullOrBlank() && !row?.reps.isNullOrBlank(),
        onToggle = { checked ->
          vm.toggleCap3Set(exerciseIndex, index, set.weight, set.repsTarget, checked)
        },
        onAmrapReps = { reps -> vm.logCap3Amrap(exerciseIndex, index, block, set, reps) }
      )
    }
  }
}

@Composable
private fun Cap3SetRow(
  set: Cap3Set,
  units: Cap3Units,
  loggedWeight: Double?,
  loggedReps: Int?,
  isChecked: Boolean,
  onToggle: (Boolean) -> Unit,
  onAmrapReps: (Int) -> Unit
) {
  val colors = LocalAppColors.current
  val accent = LocalAccentColor.current
  val shownWeight = if (isChecked) loggedWeight ?: set.weight else set.weight
  val movedSinceLogged = isChecked && loggedWeight != null && loggedWeight != set.weight

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(if (isChecked) accent.copy(alpha = 0.10f) else colors.surface)
      .padding(horizontal = 12.dp, vertical = 8.dp)
  ) {
    Checkbox(
      checked = isChecked,
      onCheckedChange = onToggle,
      colors = CheckboxDefaults.colors(checkedColor = accent)
    )
    Spacer(Modifier.width(4.dp))
    Column(Modifier.weight(1f)) {
      Text(set.label, color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
      Text(
        "${formatWeight(shownWeight, units)} × ${if (isChecked) (loggedReps ?: set.repsTarget).toString() else set.repsDisplay}",
        color = colors.text,
        fontSize = 16.sp,
        fontWeight = if (set.isAmrapCapable) FontWeight.Bold else FontWeight.Normal
      )
      if (movedSinceLogged) {
        Text(
          "Logged before your training max moved — now prescribes ${formatWeight(set.weight, units)}.",
          color = colors.textTertiary,
          style = MaterialTheme.typography.labelSmall
        )
      }
    }
    if (set.isAmrapCapable) {
      Cap3RepsField(
        value = loggedReps,
        placeholder = set.repsTarget,
        onCommit = onAmrapReps,
        modifier = Modifier.width(88.dp)
      )
      Spacer(Modifier.width(8.dp))
    }
    Cap3SetBadge(set.type)
  }
}

/**
 * Reps actually made on an AMRAP. Commits on the keyboard's Done action rather than per keystroke,
 * so typing "12" cannot briefly register as 1 rep and drop the training max.
 */
@Composable
private fun Cap3RepsField(
  value: Int?,
  placeholder: Int,
  onCommit: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
  OutlinedTextField(
    value = text,
    onValueChange = { text = it.filter(Char::isDigit) },
    placeholder = { Text("$placeholder+", style = MaterialTheme.typography.labelSmall) },
    label = { Text("Reps", style = MaterialTheme.typography.labelSmall) },
    keyboardOptions = KeyboardOptions(
      keyboardType = KeyboardType.Number,
      imeAction = androidx.compose.ui.text.input.ImeAction.Done
    ),
    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
      onDone = { text.toIntOrNull()?.let(onCommit) }
    ),
    singleLine = true,
    modifier = modifier
  )
}

// ── Major-effort day: pick the option you took ────────────────────────────────

@Composable
private fun Cap3MajorEffortBlock(
  exerciseState: ExerciseUiState,
  exerciseIndex: Int,
  block: Cap3LiftBlock,
  units: Cap3Units,
  vm: SessionViewModel
) {
  val colors = LocalAppColors.current
  val accent = LocalAccentColor.current
  // A logged row means that option is the one taken; otherwise nothing is chosen yet.
  val loggedIndex = exerciseState.sets.indexOfFirst { it.reps.isNotBlank() && it.weightKg.isNotBlank() }
  var selected by remember(loggedIndex) { mutableStateOf(loggedIndex.takeIf { it >= 0 }) }

  Text(
    "Warm up, then pick one option and take it for as many reps as you can. Enter what you made — " +
      "if it earns a new training max you'll be told.",
    color = colors.textSecondary,
    style = MaterialTheme.typography.bodySmall
  )
  Spacer(Modifier.height(12.dp))

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    block.sets.forEachIndexed { index, set ->
      val isSelected = selected == index
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(if (isSelected) accent.copy(alpha = 0.10f) else colors.surface)
          .clickable {
            selected = index
            // Choosing a different option clears the one previously logged, so only the option
            // actually taken ends up in history.
            block.sets.indices.filter { it != index }.forEach { other ->
              vm.toggleCap3Set(exerciseIndex, other, block.sets[other].weight,
                block.sets[other].repsTarget, checked = false)
            }
          }
          .padding(12.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          RadioButton(
            selected = isSelected,
            onClick = { selected = index },
            colors = RadioButtonDefaults.colors(selectedColor = accent)
          )
          Spacer(Modifier.width(4.dp))
          Column(Modifier.weight(1f)) {
            Text(set.label, color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(
              "${formatWeight(set.weight, units)} × ${set.repsDisplay}",
              color = colors.text,
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold
            )
          }
          Cap3SetBadge(set.type)
        }
        if (isSelected) {
          Spacer(Modifier.height(8.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Cap3RepsField(
              value = exerciseState.sets.getOrNull(index)?.reps?.toIntOrNull(),
              placeholder = set.repsTarget,
              onCommit = { reps -> vm.logCap3Amrap(exerciseIndex, index, block, set, reps) },
              modifier = Modifier.width(110.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
              "At least ${set.repsTarget}. More is better — that's what moves your max.",
              color = colors.textSecondary,
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
      if (index < block.sets.lastIndex) {
        Text(
          "-or-",
          color = colors.textTertiary,
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier.padding(start = 12.dp)
        )
      }
    }
  }
}

// ── EMOM page ─────────────────────────────────────────────────────────────────

@Composable
private fun Cap3EmomBlock(
  exerciseState: ExerciseUiState,
  exerciseIndex: Int,
  emom: Cap3Emom,
  units: Cap3Units,
  vm: SessionViewModel
) {
  val colors = LocalAppColors.current
  val accent = LocalAccentColor.current
  val logged = exerciseState.sets.mapNotNull { it.reps.toIntOrNull() }
  val volume = Cap3Engine.secondaryVolume(logged.map { emom.weight to it })
  val finished = logged.lastOrNull()?.let { it < emom.failBelowReps } == true

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(colors.surface2)
      .padding(14.dp)
  ) {
    Icon(Icons.Default.Timer, null, tint = accent, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Column {
      Text(
        formatWeight(emom.weight, units),
        color = colors.text,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
      )
      Text(
        "One minute rest between sets. Stop once a set drops below ${emom.failBelowReps} reps.",
        color = colors.textSecondary,
        style = MaterialTheme.typography.labelSmall
      )
    }
  }

  Spacer(Modifier.height(14.dp))

  exerciseState.sets.forEachIndexed { index, row ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
      Text(
        "Set ${index + 1}",
        color = colors.textSecondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.width(64.dp)
      )
      Text(
        formatWeight(emom.weight, units),
        color = colors.textSecondary,
        fontSize = 14.sp,
        modifier = Modifier.width(84.dp)
      )
      OutlinedTextField(
        value = row.reps,
        onValueChange = { value ->
          val digits = value.filter(Char::isDigit)
          vm.updateSetReps(exerciseIndex, index, digits)
          // The weight is fixed for the whole EMOM, so fill it alongside the reps.
          vm.updateSetWeightManual(exerciseIndex, index, formatWeight(emom.weight))
        },
        label = { Text("Reps", style = MaterialTheme.typography.labelSmall) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.width(120.dp)
      )
      Spacer(Modifier.weight(1f))
      TextButton(onClick = { vm.deleteSet(exerciseIndex, index) }) { Text("Remove") }
    }
  }

  Spacer(Modifier.height(8.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    Button(
      onClick = { vm.addSet(exerciseIndex) },
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Add set") }
    Spacer(Modifier.width(16.dp))
    if (logged.isNotEmpty()) {
      Column {
        Text(
          "Volume ${formatWeight(volume)} ${units.label}",
          color = colors.text,
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.labelMedium
        )
        Text(
          if (finished) "That's the set that ended it — ${logged.size} sets in."
          else "${logged.size} sets so far. Chase a volume PR.",
          color = if (finished) accent else colors.textSecondary,
          style = MaterialTheme.typography.labelSmall
        )
      }
    }
  }
}
