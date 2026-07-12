@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 4 gate: "clients cannot read events (RLS test)" for analytics_events, against the REAL
 * project. Proves a client CAN insert its own rows but CANNOT select any (no SELECT policy exists,
 * so RLS denies reads to everyone). @Ignore'd by default (live network); requires
 * 20260713000001_analytics.sql applied. Verified passing manually — see commit history.
 */
@Ignore("live-network integration test against the real Supabase project — see class doc")
@RunWith(RobolectricTestRunner::class)
class AnalyticsRlsIntegrationTest {

  @Serializable
  private data class EventRow(
    @SerialName("session_id") val session_id: String,
    @SerialName("event_name") val event_name: String,
    val screen: String?,
    val properties: JsonObject,
    @SerialName("app_version") val app_version: String,
    @SerialName("os_version") val os_version: String,
    @SerialName("device_class") val device_class: String
  )

  @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @After fun resetMain() { Dispatchers.resetMain() }

  private fun newClient() = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL, supabaseKey = BuildConfig.SUPABASE_ANON_KEY
  ) {
    install(Auth) { sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache() }
    install(Postgrest)
  }

  @Test
  fun client_canInsertOwnEvents_butCannotReadAny() = runBlocking {
    val client = newClient()
    client.auth.signInWith(Email) {
      email = BuildConfig.RLS_TEST_EMAIL_A; password = BuildConfig.RLS_TEST_PASSWORD_A
    }

    // Insert succeeds (insert-own policy).
    val row = EventRow(
      session_id = "it-${System.currentTimeMillis()}", event_name = "screen_view", screen = "home",
      properties = buildJsonObject { }, app_version = "2.0.0", os_version = "34", device_class = "phone"
    )
    val inserted = runCatching { client.postgrest.from("analytics_events").insert(row) }.isSuccess
    assertTrue("insert-own must succeed", inserted)

    // Select returns nothing — no SELECT policy means RLS denies reads entirely (empty, not error).
    val rows = client.postgrest.from("analytics_events")
      .select().decodeList<EventRow>()
    assertTrue("client must not be able to read analytics back", rows.isEmpty())
  }
}
