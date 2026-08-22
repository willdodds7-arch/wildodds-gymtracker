package com.wildodds.gymtracker.ui.cap3

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.data.cap3.Cap3SetType
import com.wildodds.gymtracker.data.cap3.Cap3Units

/**
 * The sheet's four set states, as badges. The colours follow the source spreadsheet's legend so a
 * user coming from the sheet reads the same thing: beige fixed, yellow AMRAP, green minimum-then-
 * optional, red optional.
 */
data class Cap3SetStyle(val label: String, val tint: Color)

fun Cap3SetType.style(): Cap3SetStyle = when (this) {
  // Do exactly the listed reps.
  Cap3SetType.FIXED -> Cap3SetStyle("Fixed", Color(0xFFB0A88F))
  // As many reps as possible — this is what drives progression.
  Cap3SetType.AMRAP -> Cap3SetStyle("AMRAP", Color(0xFFE0B33A))
  // Hit at least the listed reps; go beyond if you can.
  Cap3SetType.MIN_THEN_OPTIONAL_AMRAP -> Cap3SetStyle("Min +", Color(0xFF4CAF7D))
  // Only if you're feeling good — not required.
  Cap3SetType.OPTIONAL -> Cap3SetStyle("Optional", Color(0xFFD3564B))
}

@Composable
fun Cap3SetBadge(type: Cap3SetType, modifier: Modifier = Modifier) {
  val style = type.style()
  Text(
    text = style.label,
    color = style.tint,
    fontWeight = FontWeight.SemiBold,
    style = MaterialTheme.typography.labelSmall,
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .background(style.tint.copy(alpha = 0.14f))
      .border(1.dp, style.tint.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  )
}

/** Trims the trailing ".0" so 65.0 reads as "65" but 62.5 keeps its half. */
fun formatWeight(value: Double): String =
  if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

fun formatWeight(value: Double, units: Cap3Units): String = "${formatWeight(value)} ${units.label}"
