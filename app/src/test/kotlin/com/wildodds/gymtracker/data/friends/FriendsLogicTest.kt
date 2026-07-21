package com.wildodds.gymtracker.data.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure display/parsing logic behind the friends feature. */
class FriendsLogicTest {

  private val day = 86_400_000L
  private val now = 1_700_000_000_000L

  // ── lastGymLabel ──────────────────────────────────────────────────────────────

  @Test
  fun `never trained shows no sessions yet`() {
    assertEquals("No sessions yet", FriendsLogic.lastGymLabel(null, now))
    assertEquals("No sessions yet", FriendsLogic.lastGymLabel(0L, now))
  }

  @Test
  fun `same day shows at the gym today`() {
    assertEquals("At the gym today", FriendsLogic.lastGymLabel(now - day / 2, now))
  }

  @Test
  fun `one day ago shows yesterday`() {
    assertEquals("Yesterday", FriendsLogic.lastGymLabel(now - day, now))
  }

  @Test
  fun `five days ago shows 5 days ago`() {
    assertEquals("5 days ago", FriendsLogic.lastGymLabel(now - 5 * day, now))
  }

  @Test
  fun `future timestamp clamps to today, never negative days`() {
    assertEquals("At the gym today", FriendsLogic.lastGymLabel(now + day, now))
  }

  // ── canMotivate (the 3-day rule) ─────────────────────────────────────────────

  @Test
  fun `cannot motivate a friend who trained recently`() {
    assertFalse(FriendsLogic.canMotivate(now, now))
    assertFalse(FriendsLogic.canMotivate(now - 2 * day, now))
  }

  @Test
  fun `can motivate at exactly 3 days`() {
    assertTrue(FriendsLogic.canMotivate(now - 3 * day, now))
    assertTrue(FriendsLogic.canMotivate(now - 30 * day, now))
  }

  @Test
  fun `can motivate a friend who never trained`() {
    assertTrue(FriendsLogic.canMotivate(null, now))
    assertTrue(FriendsLogic.canMotivate(0L, now))
  }

  // ── invite link round-trip ───────────────────────────────────────────────────

  @Test
  fun `invite link carries the code and parses back out`() {
    val link = FriendsLogic.inviteLink("AB12CD34")
    assertTrue(link.startsWith("https://"))
    assertEquals("AB12CD34", FriendsLogic.parseInvite(link))
  }

  @Test
  fun `parseInvite accepts a bare code, trims and uppercases`() {
    assertEquals("AB12CD34", FriendsLogic.parseInvite("  ab12cd34 "))
  }

  @Test
  fun `parseInvite accepts a link with extra query params`() {
    assertEquals("AB12CD34", FriendsLogic.parseInvite("https://x.example/p?utm=1&code=ab12cd34&y=2"))
  }

  @Test
  fun `parseInvite rejects junk`() {
    assertNull(FriendsLogic.parseInvite(""))
    assertNull(FriendsLogic.parseInvite("hello there"))
    assertNull(FriendsLogic.parseInvite("abc"))              // too short
    assertNull(FriendsLogic.parseInvite("a".repeat(17)))     // too long
    assertNull(FriendsLogic.parseInvite("https://x.example/no-code-here"))
  }

  // ── notification copy ────────────────────────────────────────────────────────

  @Test
  fun `session start notification names the friend and the session`() {
    val e = FriendEvent(sender = "s", recipient = "r", type = FriendEvent.TYPE_SESSION_START, body = "Chest and Back")
    val (title, text) = FriendNotifier.messageFor(e, "Alex")
    assertEquals("Alex just started a Chest and Back session at the gym", title)
    assertTrue(text.contains("motivation", ignoreCase = true))
  }

  @Test
  fun `session start with no name falls back gracefully`() {
    val e = FriendEvent(sender = "s", recipient = "r", type = FriendEvent.TYPE_SESSION_START)
    val (title, _) = FriendNotifier.messageFor(e, "Alex")
    assertEquals("Alex just started a training session at the gym", title)
  }

  @Test
  fun `motivation notification carries the custom message`() {
    val e = FriendEvent(sender = "s", recipient = "r", type = FriendEvent.TYPE_MOTIVATION, body = "Leg day. Go.")
    val (title, text) = FriendNotifier.messageFor(e, "Sam")
    assertEquals("Sam sent you motivation", title)
    assertTrue(text.startsWith("Leg day. Go."))
  }

  @Test
  fun `flex notification says the friend flexed`() {
    val e = FriendEvent(sender = "s", recipient = "r", type = FriendEvent.TYPE_FLEX)
    val (title, _) = FriendNotifier.messageFor(e, "Sam")
    assertEquals("Sam flexed for you 💪", title)
  }

  @Test
  fun `program share notification points at the friends screen`() {
    val e = FriendEvent(sender = "s", recipient = "r", type = FriendEvent.TYPE_PROGRAM, body = "{}")
    val (title, text) = FriendNotifier.messageFor(e, "Sam")
    assertEquals("Sam shared a program with you", title)
    assertTrue(text.contains("Friends"))
  }
}
