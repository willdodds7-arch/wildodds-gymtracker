@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wildodds.gymtracker.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.data.intelligence.Equipment
import com.wildodds.gymtracker.data.intelligence.ExerciseGraph
import com.wildodds.gymtracker.data.intelligence.ExerciseGraphEngine
import com.wildodds.gymtracker.data.intelligence.ScoredExercise
import com.wildodds.gymtracker.data.intelligence.SwapConstraints
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/** Equipment shown as selectable chips (NONE is internal-only). */
private val EQUIPMENT_CHOICES = listOf(
  Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE,
  Equipment.CABLE, Equipment.BODYWEIGHT, Equipment.BANDS
)

/** ModalBottomSheet wrapper. Loads/saves the remembered equipment set. */
@Composable
fun SwapExerciseSheet(
  exerciseName: String,
  onSwap: (newName: String, applyToFuture: Boolean) -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val engine = remember { ExerciseGraph.engine(context) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
  SwapExerciseSheetContent(
  exerciseName = exerciseName,
  engine = engine,
  initialEquipment = GymEquipmentPrefs.load(context),
  onEquipmentChanged = { GymEquipmentPrefs.save(context, it) },
  onSwap = { name, future -> onSwap(name, future); onDismiss() }
  )
  }
}

/**
 * Testable sheet content (no ModalBottomSheet wrapper): equipment + pain + busy controls feed the
 * engine's substituteFor; ranked alternatives and similar movements are shown with one-line reasons.
 */
@Composable
fun SwapExerciseSheetContent(
  exerciseName: String,
  engine: ExerciseGraphEngine,
  initialEquipment: Set<Equipment>,
  onEquipmentChanged: (Set<Equipment>) -> Unit = {},
  onSwap: (newName: String, applyToFuture: Boolean) -> Unit
) {
  val accent = LocalAccentColor.current
  var equipment by remember { mutableStateOf(initialEquipment) }
  var painTags by remember { mutableStateOf(emptySet<String>()) }
  var gymBusy by remember { mutableStateOf(false) }
  var applyToFuture by remember { mutableStateOf(false) }

  val constraints = SwapConstraints.build(equipment, painTags, gymBusy)
  val alternatives = remember(exerciseName, equipment, painTags, gymBusy) {
  engine.substituteFor(exerciseName, constraints)
  }
  val similar = remember(exerciseName) { engine.findSimilar(exerciseName) }

  Column(
  Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)
  .verticalScroll(rememberScrollState())
  ) {
  Text("Swap $exerciseName", style = MaterialTheme.typography.titleLarge,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(16.dp))

  Label("Available equipment")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  EQUIPMENT_CHOICES.forEach { eq ->
  FilterChip(
  selected = eq in equipment,
  onClick = {
  equipment = if (eq in equipment) equipment - eq else equipment + eq
  onEquipmentChanged(equipment)
  },
  label = { Text(eq.display.replaceFirstChar { it.uppercase() }) }
  )
  }
  }

  Spacer(Modifier.height(12.dp))
  Label("Avoid / pain")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  SwapConstraints.PAIN_TAGS.forEach { tag ->
  FilterChip(
  selected = tag in painTags,
  onClick = { painTags = if (tag in painTags) painTags - tag else painTags + tag },
  label = { Text(tag) }
  )
  }
  }

  Spacer(Modifier.height(8.dp))
  ToggleRow("Gym is busy", "Prefer free-weight alternatives", gymBusy) { gymBusy = it }
  ToggleRow("Apply to future weeks", "Swap this exercise in later weeks too", applyToFuture) { applyToFuture = it }

  Spacer(Modifier.height(16.dp))
  Label("Alternatives")
  if (alternatives.isEmpty()) {
  Text("No alternatives match — loosen the equipment or pain filters.",
  style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  } else {
  alternatives.forEach { alt ->
  SuggestionRow(alt, accent) { onSwap(alt.node.name, applyToFuture) }
  }
  }

  if (similar.isNotEmpty()) {
  Spacer(Modifier.height(16.dp))
  Label("Similar movements")
  similar.forEach { sim ->
  SuggestionRow(sim, accent) { onSwap(sim.node.name, applyToFuture) }
  }
  }
  }
}

@Composable
private fun SuggestionRow(item: ScoredExercise, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
  Row(
  modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
  .testTag("swap_option_${item.node.name}").padding(vertical = 10.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Column(Modifier.weight(1f)) {
  Text(item.node.name, style = MaterialTheme.typography.bodyLarge,
  fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Text(item.reason, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Text("Swap", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
  }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
  horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f).padding(end = 12.dp)) {
  Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
  Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
  }
  Switch(checked = checked, onCheckedChange = onToggle,
  colors = SwitchDefaults.colors(checkedThumbColor = LocalAccentColor.current,
  checkedTrackColor = LocalAccentColor.current.copy(0.4f)))
  }
}

@Composable
private fun Label(text: String) {
  Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
  Spacer(Modifier.height(8.dp))
}
