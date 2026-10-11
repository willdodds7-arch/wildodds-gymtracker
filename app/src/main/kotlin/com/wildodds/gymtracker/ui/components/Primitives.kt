package com.wildodds.gymtracker.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.ui.theme.AppType
import com.wildodds.gymtracker.ui.theme.LocalAppColors
import com.wildodds.gymtracker.ui.theme.Radii
import com.wildodds.gymtracker.ui.theme.Space

// ─────────────────────────────────────────────────────────────────────────────
// SHARED PRIMITIVES — the monochrome design system, built once and reused.
// Motion: fast, restrained (180ms ease-out, no spring). See [appTween].
// ─────────────────────────────────────────────────────────────────────────────

/** Standard redesign transition: 180ms ease-out, no bounce. */
fun <T> appTween() = tween<T>(durationMillis = 180)

/**
 * Square checkbox. Empty → 1px hairline border. Checked → red accent fill + white
 * check. This is the only place the accent shows up by default.
 */
@Composable
fun MonoCheckbox(
  checked: Boolean,
  modifier: Modifier = Modifier,
  size: Dp = 24.dp,
  onClick: (() -> Unit)? = null
) {
  val c = LocalAppColors.current
  val shape = RoundedCornerShape(6.dp)
  val base = Modifier
    .size(size)
    .clip(shape)
    .then(
      if (checked) Modifier.background(c.accent)
      else Modifier.border(1.dp, c.border, shape).background(c.bg)
    )
  val clickable =
    if (onClick != null) base.clickable(role = Role.Checkbox, onClick = onClick) else base
  Box(modifier.then(clickable), contentAlignment = Alignment.Center) {
    if (checked) {
      Icon(
        Icons.Default.Check,
        contentDescription = null,
        tint = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier.size(size * 0.66f)
      )
    }
  }
}

/** 1px hairline divider in the border colour. */
@Composable
fun AppDivider(modifier: Modifier = Modifier) {
  val c = LocalAppColors.current
  Box(
    modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(c.border)
  )
}
