@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wildodds.gymtracker.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.data.intelligence.BodyRegion
import com.wildodds.gymtracker.data.intelligence.InjuryTriage
import com.wildodds.gymtracker.data.intelligence.PainDuration
import com.wildodds.gymtracker.data.intelligence.PainQuality
import com.wildodds.gymtracker.data.intelligence.PainTiming
import com.wildodds.gymtracker.data.intelligence.RedFlag
import com.wildodds.gymtracker.data.intelligence.TriageAnswers
import com.wildodds.gymtracker.data.intelligence.TriageLevel
import com.wildodds.gymtracker.data.intelligence.TriageResult
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/**
 * Guided injury triage. Non-dismissable disclaimer at entry and on results; conservative rules
 * (see [InjuryTriage]). On a safe result the user can apply a reversible accommodation; on RED it
 * only routes to a professional and offers no auto-changes.
 */
@Composable
fun InjuryTriageSheet(
  onApply: (region: BodyRegion, avoidTags: Set<String>) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  var region by remember { mutableStateOf<BodyRegion?>(null) }
  var quality by remember { mutableStateOf<PainQuality?>(null) }
  var timing by remember { mutableStateOf(emptySet<PainTiming>()) }
  var severity by remember { mutableFloatStateOf(2f) }
  var redFlags by remember { mutableStateOf(emptySet<RedFlag>()) }
  var duration by remember { mutableStateOf(PainDuration.ACUTE) }
  var result by remember { mutableStateOf<TriageResult?>(null) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(
  Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)
  .verticalScroll(rememberScrollState())
  ) {
  Text("Injury check", style = MaterialTheme.typography.titleLarge,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))
  DisclaimerCard()
  Spacer(Modifier.height(16.dp))

  Label("Where does it hurt?")
  ChipGrid(BodyRegion.entries, region) { region = it; result = null }

  Spacer(Modifier.height(12.dp))
  Label("What does it feel like?")
  ChipGrid(PainQuality.entries, quality) { quality = it; result = null }

  Spacer(Modifier.height(12.dp))
  Label("When?")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  PainTiming.entries.forEach { t ->
  FilterChip(selected = t in timing,
  onClick = { timing = if (t in timing) timing - t else timing + t; result = null },
  label = { Text(t.display) })
  }
  }

  Spacer(Modifier.height(12.dp))
  Label("Severity: ${severity.toInt()}/10")
  Slider(value = severity, onValueChange = { severity = it; result = null }, valueRange = 0f..10f, steps = 9,
  colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))

  Spacer(Modifier.height(8.dp))
  Label("How long?")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  PainDuration.entries.forEach { d ->
  FilterChip(selected = d == duration, onClick = { duration = d; result = null }, label = { Text(d.display) })
  }
  }

  Spacer(Modifier.height(12.dp))
  Label("Any of these? (tick all that apply)")
  Column {
  RedFlag.entries.forEach { f ->
  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  modifier = Modifier.fillMaxWidth()) {
  Checkbox(checked = f in redFlags,
  onCheckedChange = { redFlags = if (f in redFlags) redFlags - f else redFlags + f; result = null })
  Text(f.display, style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onBackground)
  }
  }
  }

  Spacer(Modifier.height(16.dp))
  Button(
  onClick = {
  val r = region; val q = quality
  if (r != null && q != null) {
  result = InjuryTriage.triage(TriageAnswers(r, q, timing, severity.toInt(), redFlags, duration))
  }
  },
  enabled = region != null && quality != null,
  modifier = Modifier.fillMaxWidth().height(50.dp).testTag("triage_get_guidance"),
  shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent)
  ) { Text("Get guidance", fontWeight = FontWeight.Bold) }

  result?.let { r ->
  Spacer(Modifier.height(16.dp))
  ResultCard(r)
  Spacer(Modifier.height(8.dp))
  DisclaimerCard()  // shown again on results
  Spacer(Modifier.height(12.dp))
  if (InjuryTriage.canApply(r.level)) {
  Button(
  onClick = { onApply(region!!, r.avoidTags); onDismiss() },
  modifier = Modifier.fillMaxWidth().height(50.dp).testTag("triage_apply"),
  shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent)
  ) { Text("Apply accommodation", fontWeight = FontWeight.Bold) }
  } else {
  Text("Please see a physiotherapist or doctor before changing your training.",
  style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.error)
  }
  }
  }
  }
}

@Composable
private fun ResultCard(r: TriageResult) {
  val color = when (r.level) {
  TriageLevel.RED -> MaterialTheme.colorScheme.error
  TriageLevel.AMBER -> Color(0xFFE08A00)
  TriageLevel.GREEN -> Color(0xFF2E7D32)
  }
  Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
  Column(Modifier.padding(14.dp)) {
  Text(
  when (r.level) {
  TriageLevel.RED -> "See a professional"
  TriageLevel.AMBER -> "Train with caution"
  TriageLevel.GREEN -> "Likely a minor niggle"
  },
  fontWeight = FontWeight.Bold, color = color, fontSize = 16.sp
  )
  Spacer(Modifier.height(6.dp))
  Text(r.rationale, style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onBackground)
  }
  }
}

@Composable
private fun DisclaimerCard() {
  Surface(
  color = MaterialTheme.colorScheme.surfaceVariant,
  shape = RoundedCornerShape(10.dp),
  modifier = Modifier.fillMaxWidth().testTag("triage_disclaimer")
  ) {
  Text(InjuryTriage.DISCLAIMER, modifier = Modifier.padding(12.dp),
  style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun <T> ChipGrid(options: List<T>, selected: T?, onSelect: (T) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
  options.forEach { opt ->
  FilterChip(selected = opt == selected, onClick = { onSelect(opt) }, label = { Text(displayOf(opt)) })
  }
  }
}

private fun displayOf(v: Any?): String = when (v) {
  is BodyRegion -> v.display
  is PainQuality -> v.display
  else -> v.toString()
}

@Composable
private fun Label(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(6.dp))
}
