package com.wildodds.gymtracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Twitter/Instagram-style verified tick sizes. */
enum class BadgeSize(val dp: Dp) { SM(14.dp), MD(18.dp), LG(24.dp) }

private val VerifiedBlue = Color(0xFF1D9BF0)

/**
 * The Verified Creator badge: filled blue circle, white check. Render next to a display name
 * ONLY while the creator's subscription is active — callers gate on the server-derived
 * `is_verified_creator` flag (or CreatorStatus.isVerified for the signed-in user), never a
 * client-asserted value. Accessibility label doubles as the "tooltip": "Verified Creator".
 */
@Composable
fun VerifiedBadge(size: BadgeSize = BadgeSize.MD, modifier: Modifier = Modifier) {
  Icon(
    imageVector = Icons.Default.Check,
    contentDescription = null,
    tint = Color.White,
    modifier = modifier
      .size(size.dp)
      .clip(CircleShape)
      .background(VerifiedBlue)
      .padding(size.dp / 6)
      .semantics { contentDescription = "Verified Creator" }
      .testTag("verified_badge")
  )
}
