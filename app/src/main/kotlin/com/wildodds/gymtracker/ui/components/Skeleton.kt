package com.wildodds.gymtracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.ui.theme.LocalAppColors
import com.wildodds.gymtracker.ui.theme.Radii
import com.wildodds.gymtracker.ui.theme.Space

// ─────────────────────────────────────────────────────────────────────────────
// SKELETON LOADERS
// Grey placeholder shapes shown INSTEAD of blank screens or spinners while content
// loads, so the layout appears immediately and only the data fills in. A gentle
// opacity pulse (900 ms, ease) hints "loading"; under reduced motion it stays static.
//
// PERF — two rules these follow, because skeletons are on screen exactly when the app is
// busiest (cold start, first DB answer) and can least afford wasted frames:
//   1. ONE animation clock per skeleton group, shared through [LocalSkeletonAlpha], instead of a
//      separate infinite transition inside every individual shape.
//   2. The pulsing alpha is read in the DRAW phase (a graphicsLayer lambda), never in composition,
//      so the pulse re-draws without recomposing a single node. Reading it in composition meant a
//      five-row SkeletonList invalidated ~20 composables on every frame at 120 Hz.
// ─────────────────────────────────────────────────────────────────────────────

/** The alpha clock shared by everything inside a [SkeletonGroup]; null means "make your own". */
private val LocalSkeletonAlpha = compositionLocalOf<State<Float>?> { null }

/** Creates a fresh pulse clock (static under reduced motion). */
@Composable
private fun newSkeletonAlpha(): State<Float> {
  if (LocalReducedMotion.current) return remember { mutableFloatStateOf(0.5f) }
  val transition = rememberInfiniteTransition(label = "skeleton")
  return transition.animateFloat(
    initialValue = 0.35f,
    targetValue = 0.75f,
    animationSpec = infiniteRepeatable(
      animation = tween(900, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "skeletonAlpha"
  )
}

/** The clock this shape should pulse on: the group's if there is one, otherwise its own. */
@Composable
private fun skeletonAlpha(): State<Float> = LocalSkeletonAlpha.current ?: newSkeletonAlpha()

/**
 * Wraps a set of skeleton shapes so they share one animation clock and pulse in sync. Placeholder
 * groups built by hand (rather than via [SkeletonList]) should use this.
 */
@Composable
fun SkeletonGroup(content: @Composable () -> Unit) {
  val alpha = LocalSkeletonAlpha.current ?: newSkeletonAlpha()
  CompositionLocalProvider(LocalSkeletonAlpha provides alpha) { content() }
}

/** A single placeholder block. Prefer [SkeletonLine]/[SkeletonList] for common shapes. */
@Composable
fun SkeletonBox(
  modifier: Modifier,
  shape: Shape = Radii.smShape
) {
  val c = LocalAppColors.current
  val alpha = skeletonAlpha()
  Box(
    modifier
      .graphicsLayer { this.alpha = alpha.value }
      .clip(shape)
      .background(c.surface2)
  )
}

/** A text-line placeholder of a given width fraction and height. */
@Composable
fun SkeletonLine(
  modifier: Modifier = Modifier,
  widthFraction: Float = 1f,
  height: Dp = 14.dp
) {
  SkeletonBox(modifier.fillMaxWidth(widthFraction).height(height))
}

/** A card-shaped placeholder row: a leading dot, two lines, and a trailing chip. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
  val c = LocalAppColors.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(Radii.cardShape)
      .background(c.surface)
      .padding(Space.lg),
    verticalAlignment = Alignment.CenterVertically
  ) {
    SkeletonBox(Modifier.size(36.dp), CircleShape)
    Spacer(Modifier.width(Space.md))
    Column(Modifier.weight(1f)) {
      SkeletonLine(widthFraction = 0.55f, height = 16.dp)
      Spacer(Modifier.height(Space.sm))
      SkeletonLine(widthFraction = 0.35f, height = 12.dp)
    }
    Spacer(Modifier.width(Space.md))
    SkeletonBox(Modifier.size(width = 56.dp, height = 28.dp), Radii.pillShape)
  }
}

/** A full list of [rows] skeleton cards — the default "loading a list" placeholder. */
@Composable
fun SkeletonList(
  modifier: Modifier = Modifier,
  rows: Int = 5,
  spacing: Dp = Space.md
) {
  SkeletonGroup {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
      repeat(rows) { SkeletonCard() }
    }
  }
}
