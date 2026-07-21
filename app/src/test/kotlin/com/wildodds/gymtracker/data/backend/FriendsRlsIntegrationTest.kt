package com.wildodds.gymtracker.data.backend

import com.wildodds.gymtracker.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.MemoryCodeVerifierCache
import io.github.jan.supabase.gotrue.MemorySessionManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Friends-migration gate (same convention as [ProfilesRlsIntegrationTest]): hits the REAL hosted
 * Supabase project with the two pre-confirmed throwaway accounts from local.properties and proves
 * the cross-user rules hold:
 *
 *  * strangers can NOT read each other's profile snapshot, friends list, or send friend_events;
 *  * redeem_friend_code() makes the friendship both ways;
 *  * friends CAN read each other's snapshot; events flow only between friends;
 *  * unfriend() severs both directions and re-closes the snapshot.
 *
 * Needs supabase/migrations/20260720000001_friends.sql applied to the live project first.
 * [Ignore]d by default — remove the annotation to run it manually.
 */
@Ignore("live-network integration test against the real Supabase project — see class doc")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FriendsRlsIntegrationTest {

  @Serializable
  private data class ProfileRow(
    val id: String,
    @SerialName("friend_code") val friendCode: String? = null,
    @SerialName("last_session_name") val lastSessionName: String? = null
  )

  @Serializable
  private data class EventRow(val id: Long, val sender: String, val recipient: String, val type: String)

  @Serializable private data class RedeemParams(val code: String)
  @Serializable private data class UnfriendParams(val other: String)
  @Serializable private data class EventInsert(
    val sender: String, val recipient: String, val type: String, val body: String
  )

  @Before
  fun setMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

  @After
  fun resetMainDispatcher() { Dispatchers.resetMain() }

  private fun newTestClient() = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY
  ) {
    install(Auth) {
      sessionManager = MemorySessionManager()
      codeVerifierCache = MemoryCodeVerifierCache()
    }
    install(Postgrest)
  }

  @Test
  fun friendshipGatesCrossUserVisibility() = runBlocking {
    val clientA = newTestClient()
    val clientB = newTestClient()
    clientA.auth.signInWith(Email) {
      email = BuildConfig.RLS_TEST_EMAIL_A
      password = BuildConfig.RLS_TEST_PASSWORD_A
    }
    clientB.auth.signInWith(Email) {
      email = BuildConfig.RLS_TEST_EMAIL_B
      password = BuildConfig.RLS_TEST_PASSWORD_B
    }
    val idA = checkNotNull(clientA.auth.currentUserOrNull()?.id)
    val idB = checkNotNull(clientB.auth.currentUserOrNull()?.id)

    // Reset: sever any friendship left over from a previous run (idempotent).
    clientA.postgrest.rpc("unfriend", UnfriendParams(idB))

    // ── Strangers ────────────────────────────────────────────────────────────
    val strangerRead = clientB.postgrest.from("profiles")
      .select(Columns.list("id,friend_code")) { filter { eq("id", idA) } }
      .decodeList<ProfileRow>()
    assertTrue("stranger must not read A's profile snapshot", strangerRead.isEmpty())

    val strangerSend = runCatching {
      clientB.postgrest.from("friend_events")
        .insert(EventInsert(sender = idB, recipient = idA, type = "motivation", body = "nope"))
    }
    assertTrue("stranger must not send friend_events to A", strangerSend.isFailure)

    // Sender identity can't be forged even between friends-to-be: sender != auth.uid() fails.
    val forged = runCatching {
      clientB.postgrest.from("friend_events")
        .insert(EventInsert(sender = idA, recipient = idB, type = "flex", body = ""))
    }
    assertTrue("nobody can insert events with a forged sender", forged.isFailure)

    // ── Befriend via the code ────────────────────────────────────────────────
    val codeA = clientA.postgrest.from("profiles")
      .select(Columns.list("id,friend_code")) { filter { eq("id", idA) } }
      .decodeList<ProfileRow>().first().friendCode
    checkNotNull(codeA) { "A has no friend_code — is the friends migration applied?" }

    clientB.postgrest.rpc("redeem_friend_code", RedeemParams(codeA))

    // Both directions exist → each can read the other's snapshot now.
    val friendRead = clientB.postgrest.from("profiles")
      .select(Columns.list("id,friend_code")) { filter { eq("id", idA) } }
      .decodeList<ProfileRow>()
    assertEquals("friend should read A's profile snapshot", 1, friendRead.size)
    val reverseRead = clientA.postgrest.from("profiles")
      .select(Columns.list("id,friend_code")) { filter { eq("id", idB) } }
      .decodeList<ProfileRow>()
    assertEquals("friendship must be mutual", 1, reverseRead.size)

    // ── Events flow between friends ──────────────────────────────────────────
    clientB.postgrest.from("friend_events")
      .insert(EventInsert(sender = idB, recipient = idA, type = "motivation", body = "gym time"))
    val inboxA = clientA.postgrest.from("friend_events")
      .select(Columns.list("id,sender,recipient,type")) {
        filter { eq("recipient", idA); eq("sender", idB); eq("type", "motivation") }
      }.decodeList<EventRow>()
    assertTrue("A should see B's motivation event", inboxA.isNotEmpty())

    // ── Unfriend severs everything again ─────────────────────────────────────
    clientA.postgrest.rpc("unfriend", UnfriendParams(idB))
    val severedRead = clientB.postgrest.from("profiles")
      .select(Columns.list("id,friend_code")) { filter { eq("id", idA) } }
      .decodeList<ProfileRow>()
    assertTrue("after unfriend, B must not read A's snapshot", severedRead.isEmpty())
    val severedSend = runCatching {
      clientB.postgrest.from("friend_events")
        .insert(EventInsert(sender = idB, recipient = idA, type = "motivation", body = "still there?"))
    }
    assertTrue("after unfriend, B must not send events to A", severedSend.isFailure)
  }
}
