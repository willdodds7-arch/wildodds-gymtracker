@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.data.tools.PlateMath
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/**
 * Minimalist barbell plate calculator. Enter a target + bar weight and see the per-side
 * breakdown. KG only for now; the math and layout are unit-agnostic, so a lb toggle later just
 * swaps the plate set + label.
 */
@Composable
fun PlateCalculatorSheet(onDismiss: () -> Unit) {
  val accent = LocalAccentColor.current
  var targetText by rememberSaveable { mutableStateOf("100") }
  var barText by rememberSaveable { mutableStateOf(PlateMath.DEFAULT_BAR_KG.toInt().toString()) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
  Text("Plate Calculator", style = MaterialTheme.typography.titleLarge,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(20.dp))

  Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
  OutlinedTextField(
  value = targetText, onValueChange = { targetText = it.filter { c -> c.isDigit() || c == '.' } },
  label = { Text("Target (kg)") }, singleLine = true,
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
  textStyle = MaterialTheme.typography.headlineSmall,
  modifier = Modifier.weight(1f),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  OutlinedTextField(
  value = barText, onValueChange = { barText = it.filter { c -> c.isDigit() || c == '.' } },
  label = { Text("Bar (kg)") }, singleLine = true,
  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
  textStyle = MaterialTheme.typography.headlineSmall,
  modifier = Modifier.weight(1f),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  }

  Spacer(Modifier.height(20.dp))

  val target = targetText.toFloatOrNull()
  val bar = barText.toFloatOrNull() ?: PlateMath.DEFAULT_BAR_KG
  if (target == null) {
  Text("Enter a target weight.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  } else {
  val result = PlateMath.computePerSide(target, bar)
  Text("Per side", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
  Spacer(Modifier.height(10.dp))

  BarbellVisual(perSide = result.perSide)
  Spacer(Modifier.height(16.dp))

  if (result.barOnly) {
  Text("Just the bar — no plates needed.",
  style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
  } else {
  FlowRowPlates(PlateMath.groupForDisplay(result.perSide), accent)
  }

  Spacer(Modifier.height(20.dp))
  val achieved = formatKg(result.achievedWeight)
  if (result.isExact) {
  Text("= $achieved kg", style = MaterialTheme.typography.headlineSmall,
  fontWeight = FontWeight.Bold, color = accent)
  } else {
  Text("Closest loadable: $achieved kg", style = MaterialTheme.typography.titleMedium,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Text("Can't hit ${formatKg(target)} kg exactly with the standard plate set.",
  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  }
  }
}

@Composable
private fun FlowRowPlates(groups: List<Pair<Int, Float>>, accent: androidx.compose.ui.graphics.Color) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
  groups.forEach { (count, plate) ->
  Row(verticalAlignment = Alignment.CenterVertically) {
  Box(
  modifier = Modifier
  .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
  .padding(horizontal = 18.dp, vertical = 14.dp)
  ) {
  Text("$count × ${formatKg(plate)} kg", style = MaterialTheme.typography.titleMedium,
  fontWeight = FontWeight.Bold, color = accent)
  }
  }
  }
  }
}

private fun formatKg(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
