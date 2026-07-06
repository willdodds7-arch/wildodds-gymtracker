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

/** Uppercase, tracked, secondary-coloured section label with generous top margin. */
@Composable
fun SectionHeader(
  text: String,
  modifier: Modifier = Modifier,
  topMargin: Dp = Space.x3
) {
  val c = LocalAppColors.current
  Text(
    text = text.uppercase(),
    style = AppType.section,
    color = c.textSecondary,
    modifier = modifier.padding(top = topMargin, bottom = Space.md)
  )
}

/** Big UPPERCASE day/section title with a small subtitle beneath. */
@Composable
fun DayHeader(
  title: String,
  subtitle: String? = null,
  modifier: Modifier = Modifier,
  trailing: @Composable (() -> Unit)? = null
) {
  val c = LocalAppColors.current
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(title.uppercase(), style = AppType.display, color = c.text)
      if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(Space.xs))
        Text(subtitle, style = AppType.label, color = c.textSecondary)
      }
    }
    if (trailing != null) {
      Spacer(Modifier.width(Space.md))
      trailing()
    }
  }
}

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

/**
 * Generic list row: title + optional subtitle and trailing meta, with an optional
 * leading slot (e.g. a [MonoCheckbox]). Tappable. Pair with [AppDivider] between rows.
 */
@Composable
fun ListRow(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  meta: String? = null,
  leading: @Composable (() -> Unit)? = null,
  trailing: @Composable (() -> Unit)? = null,
  titleStrikethrough: Boolean = false,
  dim: Boolean = false,
  onClick: (() -> Unit)? = null
) {
  val c = LocalAppColors.current
  val titleColor = if (dim) c.textSecondary else c.text
  val rowMod = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
  Row(
    modifier = rowMod
      .fillMaxWidth()
      .padding(vertical = Space.lg),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (leading != null) {
      leading()
      Spacer(Modifier.width(Space.lg))
    }
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = AppType.title.copy(
          textDecoration =
            if (titleStrikethrough) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
        ),
        color = titleColor,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = AppType.body, color = c.textSecondary)
      }
    }
    if (meta != null) {
      Spacer(Modifier.width(Space.md))
      Text(meta, style = AppType.label, color = c.textSecondary)
    }
    if (trailing != null) {
      Spacer(Modifier.width(Space.md))
      trailing()
    }
  }
}

/** Flat surface tile: surface bg, hairline border, radius, comfortable padding. */
@Composable
fun AppTile(
  modifier: Modifier = Modifier,
  padding: Dp = Space.lg,
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit
) {
  val c = LocalAppColors.current
  Surface(
    modifier = modifier,
    shape = Radii.cardShape,
    color = c.surface,
    contentColor = c.text,
    border = BorderStroke(1.dp, c.border),
    onClick = onClick ?: {},
    enabled = onClick != null
  ) {
    Column(Modifier.padding(padding), content = content)
  }
}

/** Display-xl number/word with a small secondary label underneath. */
@Composable
fun BigStat(
  value: String,
  label: String,
  modifier: Modifier = Modifier
) {
  val c = LocalAppColors.current
  Column(modifier) {
    Text(value, style = AppType.displayXl, color = c.text)
    Spacer(Modifier.height(Space.xs))
    Text(label.uppercase(), style = AppType.section, color = c.textSecondary)
  }
}

/** Circular, hairline-bordered icon button. >=44dp tap target. Icon inherits text colour. */
@Composable
fun MonoIconButton(
  icon: ImageVector,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onClick: () -> Unit
) {
  val c = LocalAppColors.current
  val border = if (selected) c.accent else c.border
  val tint = if (selected) c.accent else c.text
  Surface(
    onClick = onClick,
    shape = Radii.pillShape,
    color = c.bg,
    contentColor = tint,
    border = BorderStroke(1.dp, border),
    modifier = modifier.size(44.dp)
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
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

/** Text-only segmented control: active = text colour + thin underline, inactive = tertiary. */
@Composable
fun SegmentedControl(
  options: List<String>,
  selectedIndex: Int,
  modifier: Modifier = Modifier,
  onSelect: (Int) -> Unit
) {
  val c = LocalAppColors.current
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
    options.forEachIndexed { i, label ->
      val active = i == selectedIndex
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onSelect(i) }
      ) {
        Text(
          label,
          style = AppType.label.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
          color = if (active) c.text else c.textTertiary,
          modifier = Modifier.padding(vertical = Space.sm)
        )
        Box(
          Modifier
            .height(2.dp)
            .width(20.dp)
            .background(if (active) c.text else androidx.compose.ui.graphics.Color.Transparent)
        )
      }
    }
  }
}
