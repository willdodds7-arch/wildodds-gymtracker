package com.wildodds.gymtracker.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * One motion spec for every NavHost, matching the app's restrained-motion convention (see
 * Primitives.appTween — fast ease-out, no spring): pushes slide in gently from the right over a
 * fade, pops mirror it. 260 ms keeps it visible but never sluggish.
 */
object NavTransitions {
  private const val DURATION = 260

  val enter: EnterTransition =
    slideInHorizontally(tween(DURATION)) { it / 5 } + fadeIn(tween(DURATION))
  val exit: ExitTransition =
    slideOutHorizontally(tween(DURATION)) { -it / 8 } + fadeOut(tween(DURATION))
  val popEnter: EnterTransition =
    slideInHorizontally(tween(DURATION)) { -it / 8 } + fadeIn(tween(DURATION))
  val popExit: ExitTransition =
    slideOutHorizontally(tween(DURATION)) { it / 5 } + fadeOut(tween(DURATION))
}
