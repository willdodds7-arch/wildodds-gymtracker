package com.wildodds.gymtracker.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

// ─────────────────────────────────────────────────────────────────────────────
// SHARED MOTION SYSTEM
// One place for durations, easing, and reduced-motion handling so every screen
// animates the same way. Rules of thumb (matching the design brief):
//   • microinteractions  ~120 ms      (button press, focus, small fades)
//   • larger transitions ~200 ms      (screen/dialog enter, list changes)
//   • animate opacity + transform only; ease-out curves, never linear
//   • honour the OS "remove animations" setting — collapse to instant
// ─────────────────────────────────────────────────────────────────────────────

object AppMotion {
  // Tuned for snap. The earlier 200/320 pair sat at the slow end of the ranges above, which read
  // as lag on a 120 Hz panel — a 320 ms card entrance is ~38 frames of the user waiting on the UI
  // rather than the other way round. These are short enough to feel instant but long enough to
  // still read as motion rather than a hard cut.

  /** Microinteractions: presses, focus rings, tiny fades. */
  const val MICRO = 120

  /** Larger transitions: screen/dialog enter, list item changes. */
  const val TRANSITION = 200

  /** Standard ease-out for entering elements. */
  val EaseOut = LinearOutSlowInEasing

  /** Standard curve for elements that both enter and settle. */
  val Standard = FastOutSlowInEasing

  /** Gentle, non-bouncy spring for presses and toggles. Stiff enough to settle under a finger. */
  fun <T> gentleSpring(): AnimationSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
}

/**
 * True when the user has asked the OS to minimise motion (Settings → Accessibility →
 * Remove animations, i.e. ANIMATOR_DURATION_SCALE = 0). Provided down the tree so any
 * animation helper can collapse to an instant change. Defaults to false.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the current OS animation scale; 0 means the user wants no motion. */
@Composable
fun rememberReducedMotion(): Boolean {
  val context = LocalContext.current
  return remember {
    runCatching {
      Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
  }
}

/**
 * A tween that respects reduced-motion: returns [snap] (instant) when motion is off,
 * otherwise an ease-out tween of [durationMillis].
 */
@Composable
fun <T> appTween(durationMillis: Int = AppMotion.MICRO): AnimationSpec<T> =
  if (LocalReducedMotion.current) snap() else tween(durationMillis, easing = AppMotion.EaseOut)

/**
 * Standard "appear" wrapper: a subtle fade + slight rise + slight scale-in, used for
 * content that arrives after first render (cards, sections, loaded results). Collapses to
 * an instant show under reduced motion. Keep [visible] hoisted so callers can drive it
 * from a `remember { mutableStateOf(false) }` flipped in a LaunchedEffect, or just pass
 * `true` for a one-shot entrance on composition.
 */
@Composable
fun AppAppear(
  visible: Boolean,
  modifier: Modifier = Modifier,
  durationMillis: Int = AppMotion.TRANSITION,
  content: @Composable () -> Unit
) {
  if (LocalReducedMotion.current) {
    if (visible) content()
    return
  }
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = fadeIn(tween(durationMillis, easing = AppMotion.EaseOut)) +
      slideInVertically(tween(durationMillis, easing = AppMotion.EaseOut)) { it / 12 } +
      scaleIn(tween(durationMillis, easing = AppMotion.EaseOut), initialScale = 0.98f),
    exit = fadeOut(tween(AppMotion.MICRO))
  ) { content() }
}

/**
 * One-shot entrance: fades/rises in once when it first enters composition. Handy for the
 * top of a screen so content doesn't hard-cut in. No-op under reduced motion.
 */
@Composable
fun AppEnter(
  modifier: Modifier = Modifier,
  durationMillis: Int = AppMotion.TRANSITION,
  content: @Composable () -> Unit
) {
  var shown by remember { mutableStateOf(false) }
  androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
  AppAppear(visible = shown, modifier = modifier, durationMillis = durationMillis, content = content)
}

/**
 * Subtle spring "press" scale for tappable surfaces: dips to ~0.97 while pressed, springs
 * back on release. Pass the same [interactionSource] you give the clickable. No-op under
 * reduced motion.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
  if (LocalReducedMotion.current) return this
  val pressed by interactionSource.collectIsPressedAsState()
  val scale = animateFloatAsState(
    targetValue = if (pressed) 0.97f else 1f,
    animationSpec = AppMotion.gentleSpring(),
    label = "pressScale"
  )
  // Read the animated value inside the graphicsLayer lambda, NOT in composition. `Modifier.scale`
  // takes a plain Float, so every frame of the spring would invalidate this composable and
  // everything it wraps; deferring the read to the draw phase means the spring only re-draws.
  return this.graphicsLayer {
    val s = scale.value
    scaleX = s
    scaleY = s
  }
}
