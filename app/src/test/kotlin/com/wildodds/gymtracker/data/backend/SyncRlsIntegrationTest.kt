@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.wildodds.gymtracker.data.backend

import com.wildodds.gymtracker.BuildConfig
import com.wildodds.gymtracker.data.sync.RemoteSyncRow
import com.wildodds.gymtracker.data.sync.SyncRow
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.MemoryCodeVerifierCache
import io.github.jan.supabase.gotrue.MemorySessionManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Phase 3 gate: "RLS cross-user write fails" for sync_rows, against the REAL Supabase project.
 * Same pattern as ProfilesRlsIntegrationTest: pre-confirmed test accounts from local.properties,
 * @Ignore'd by default (live network). Requires 20260712000001_sync_rows.sql to be applied.
 * Verified passing manually — see commit history for the date.
 */
@Ignore("live-network integration test against the real Supabase project — see class doc")
class SyncRlsIntegrationTest {

  @Serializable
  private data class PushParams(val rows: List<SyncRow>)

  @Before
  fun setMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

  @After
  fun resetMainDispatcher() { Dispatchers.resetMain() }

  private fun newClient() = createSupabaseClient(
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
  fun syncRows_isolatedPerUser_andCrossUserWritesFail() = runBlocking {
    val clientA = newClient()
    val clientB = newClient()
    clientA.auth.signInWith(Email) {
      email = BuildConfig.RLS_TEST_EMAIL_A; password = BuildConfig.RLS_TEST_PASSWORD_A
    }
    clientB.auth.signInWith(Email) {
      email = BuildConfig.RLS_TEST_EMAIL_B; password = BuildConfig.RLS_TEST_PASSWORD_B
    }
    val userAId = checkNotNull(clientA.auth.currentUserOrNull()?.id)

    // A pushes one row through the RPC.
    val stamp = System.currentTimeMillis()
    val syncId = "rls-test-$stamp"
    val row = SyncRow(
      entityType = "programs", syncId = syncId, updatedAt = stamp,
      payload = buildJsonObject { put("name", "rls probe") }
    )
    clientA.postgrest.rpc("sync_push", PushParams(listOf(row)))

    // A can read it back; B sees NOTHING (RLS: empty result, not an error).
    val aRows = clientA.postgrest.from("sync_rows")
      .select { filter { eq("sync_id", syncId) } }.decodeList<RemoteSyncRow>()
    assertEquals(1, aRows.size)
    val bRows = clientB.postgrest.from("sync_rows")
      .select { filter { eq("sync_id", syncId) } }.decodeList<RemoteSyncRow>()
    assertTrue("cross-user read must be empty", bRows.isEmpty())

    // B attempting a direct insert impersonating A's user_id must be rejected by RLS.
    @Serializable
    data class ForgedRow(
      val user_id: String, val entity_type: String, val sync_id: String,
      val updated_at: Long, val payload: JsonObject
    )
    val forged = ForgedRow(userAId, "programs", "forged-$stamp", stamp, buildJsonObject { })
    val forgeFailed = runCatching {
      clientB.postgrest.from("sync_rows").insert(forged)
    }.isFailure
    assertTrue("cross-user write must fail", forgeFailed)

    // And the forged row must not exist from A's point of view either.
    val forgedVisible = clientA.postgrest.from("sync_rows")
      .select { filter { eq("sync_id", "forged-$stamp") } }.decodeList<RemoteSyncRow>()
    assertTrue(forgedVisible.isEmpty())
  }
}
