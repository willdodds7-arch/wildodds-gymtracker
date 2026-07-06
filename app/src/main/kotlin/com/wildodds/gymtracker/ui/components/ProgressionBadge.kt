package com.wildodds.gymtracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.ui.theme.*

@Composable
fun ProgressionBadge(choice: String) {
  val (label, color) = when (choice) {
  "MORE_WEIGHT" -> "More weight this week"  to BadgeMoreWeight
  "MORE_REPS"  -> "More reps this week"  to BadgeMoreReps
  "MORE_SETS"  -> "More sets this week"  to BadgeMoreSets
  "BETTER_FORM" -> "Focus on form"  to BadgeBetterForm
  "NO_CHANGE"  -> "Same as last week"  to BadgeNoChange
  else -> return
  }
  Text(
  text  = label,
  color  = Color.White,
  fontSize  = 12.sp,
  fontWeight = FontWeight.Medium,
  modifier  = Modifier
  .background(color = color, shape = RoundedCornerShape(20.dp))
  .padding(horizontal = 12.dp, vertical = 4.dp)
  )
}
