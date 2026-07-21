package com.wildodds.gymtracker.data.friends

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A friend's profile snapshot — exactly what the server lets friends see, nothing more. */
@Serializable
data class FriendProfile(
  val id: String,
  val username: String? = null,
  @SerialName("friend_code") val friendCode: String? = null,
  @SerialName("last_session_at") val lastSessionAt: Long? = null,
  @SerialName("last_session_name") val lastSessionName: String? = null,
  @SerialName("current_program") val currentProgram: String? = null,
  val prs: JsonElement? = null,
  val achievements: JsonElement? = null,
  @SerialName("public_programs") val publicPrograms: JsonElement? = null,
  @SerialName("notify_on_friend_session") val notifyOnFriendSession: Boolean = false
)

@Serializable
data class FriendRow(
  @SerialName("user_id") val userId: String,
  @SerialName("friend_id") val friendId: String
)

@Serializable
data class FriendEvent(
  val id: Long = 0,
  val sender: String,
  val recipient: String,
  val type: String,
  val body: String = "",
  val notified: Boolean = false,
  val seen: Boolean = false
) {
  companion object {
    const val TYPE_MOTIVATION = "motivation"
    const val TYPE_FLEX = "flex"
    const val TYPE_SESSION_START = "session_start"
    const val TYPE_PROGRAM = "program"
  }
}

/** Minimal (id, username) pair returned by the friends_of RPC. */
@Serializable
data class FriendOfFriend(val id: String, val username: String? = null)

/** Pure display helpers — unit-tested. */
object FriendsLogic {

  /** "Today" / "Yesterday" / "5 days ago" / "Not yet" for the friends-list gym recency label. */
  fun lastGymLabel(lastSessionAt: Long?, nowMs: Long): String {
    if (lastSessionAt == null || lastSessionAt <= 0) return "No sessions yet"
    val days = daysSince(lastSessionAt, nowMs)
    return when {
      days <= 0 -> "At the gym today"
      days == 1 -> "Yesterday"
      else -> "$days days ago"
    }
  }

  fun daysSince(thenMs: Long, nowMs: Long): Int =
    ((nowMs - thenMs) / 86_400_000L).toInt().coerceAtLeast(0)

  /** The motivate button unlocks once a friend hasn't trained for [MOTIVATE_AFTER_DAYS]+ days. */
  const val MOTIVATE_AFTER_DAYS = 3
  fun canMotivate(lastSessionAt: Long?, nowMs: Long): Boolean =
    lastSessionAt == null || lastSessionAt <= 0 || daysSince(lastSessionAt, nowMs) >= MOTIVATE_AFTER_DAYS

  /** The share-link carried through text/socials; the page shows the code + opens the app. */
  fun inviteLink(friendCode: String): String =
    "https://willdodds7-arch.github.io/wildodds-gymtracker/add-friend.html?code=$friendCode"

  /** Pull a friend code out of a pasted invite (full link, or the bare code). */
  fun parseInvite(text: String): String? {
    val fromUrl = Regex("[?&]code=([A-Za-z0-9]{4,16})").find(text)?.groupValues?.get(1)
    val bare = text.trim().takeIf { Regex("^[A-Za-z0-9]{4,16}$").matches(it) }
    return (fromUrl ?: bare)?.uppercase()
  }
}
