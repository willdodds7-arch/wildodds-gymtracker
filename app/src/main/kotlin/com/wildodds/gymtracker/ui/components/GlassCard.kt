package com.wildodds.gymtracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.ui.theme.LocalAppColors

/**
 * Flat surface card. (Name kept for back-compat with ~15 call sites; the old
 * translucent "glass" + shadow look is gone — the redesign differentiates layers
 * with surface colour and a 1px hairline, never elevation.)
 */
@Composable
fun GlassCard(
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 12.dp,
  content: @Composable BoxScope.() -> Unit
) {
  val c = LocalAppColors.current
  val shape = RoundedCornerShape(cornerRadius)

  Box(
    modifier = modifier
      .clip(shape)
      .background(c.surface)
      .border(width = 1.dp, color = c.border, shape = shape)
      .padding(16.dp),
    content = content
  )
}
