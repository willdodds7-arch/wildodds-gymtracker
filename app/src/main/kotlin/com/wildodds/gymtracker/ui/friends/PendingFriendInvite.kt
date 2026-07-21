package com.wildodds.gymtracker.ui.friends

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A friend code parsed from an invite deep link (wildodds://friend?code=… or the public
 * add-friend page). MainActivity writes it; MainPagerScreen observes it and offers the
 * add-friend dialog from wherever the user currently is. Null = nothing pending.
 */
object PendingFriendInvite {
  val code = MutableStateFlow<String?>(null)
}
