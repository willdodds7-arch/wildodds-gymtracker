package com.wildodds.gymtracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.data.builder.TwelveWeekLinear
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/**
 * The whole 12-week linear progression at a glance: week, sets × reps and the suggested weight
 * (or the % change when no starting weight is set), grouped by phase. [currentWeek] (1-12)
 * highlights where the athlete is now.
 */
@Composable
fun Linear12Table(startLoad: Float?, currentWeek: Int? = null) {
  val accent = LocalAccentColor.current
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  Column(Modifier.fillMaxWidth()) {
    TwelveWeekLinear.CYCLE.groupBy { it.phase }.forEach { (phase, steps) ->
      Text(
        "PHASE $phase · ${TwelveWeekLinear.phaseFocus(phase)}",
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        color = muted, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
      )
      steps.forEach { step ->
        val here = step.week == currentWeek
        val load = TwelveWeekLinear.weekPlan(step.week - 1, startLoad).load
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (here) accent.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 3.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Week ${step.week}", modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall, color = if (here) accent else muted,
            fontWeight = if (here) FontWeight.Bold else FontWeight.Normal)
          Text("${step.sets} × ${step.reps}", modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground)
          Text(
            when {
              load != null -> formatAxisValue(load) + " kg"
              step.loadPercent == 100 -> "start weight"
              else -> "+${step.loadPercent - 100}%"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (step.loadPercent > 100) accent else muted
          )
        }
      }
    }
    Spacer(Modifier.height(6.dp))
    Text(
      "After week 12 it loops back to phase 1 at week 12's weight.",
      style = MaterialTheme.typography.labelSmall, color = muted
    )
  }
}
