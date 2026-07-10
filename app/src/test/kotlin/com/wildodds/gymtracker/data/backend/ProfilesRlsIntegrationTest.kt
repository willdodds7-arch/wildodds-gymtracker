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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * Phase 1 gate: "RLS cross-user read test fails as it should." This hits the REAL hosted Supabase
 * project (there's no local Docker/`supabase start` stack available in this environment) and proves
 * user B cannot read user A's `profiles` row.
 *
 * Uses two pre-created, pre-confirmed throwaway accounts (Auth > Users > Add user > Auto Confirm)
 * rather than signing up fresh ones here — this project's transactional email isn't reliable yet
 * (built-in sender is capped at 2/hour; a custom SMTP relay was wired in but confirmation sends are
 * still failing), and that's an orthogonal problem to "do the RLS policies work." Credentials come
 * from local.properties (see RLS_TEST_EMAIL_A/B in BuildConfig) — never hardcoded, never committed.
 *
 * [Ignore]d by default so it doesn't run on every local `./gradlew test` or CI push (needs the two
 * BuildConfig-supplied test accounts to exist). Verified passing manually on 2026-07-11.
 */
@Ignore("live-network integration test against the real Supabase project — see class doc")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfilesRlsIntegrationTest {

  @Serializable
  private data class ProfileRow(val id: String, val username: String? = null)

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
  fun userBCannotReadUserAsProfile() = runBlocking {
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

    val userAId = clientA.auth.currentUserOrNull()?.id
    checkNotNull(userAId) { "sign-in for user A did not return a session/user — check RLS_TEST_EMAIL_A/PASSWORD_A in local.properties." }

    // The insert-on-signup trigger should have already created A's profiles row.
    val ownRead = clientA.postgrest.from("profiles")
      .select(Columns.list("id,username")) { filter { eq("id", userAId) } }
      .decodeList<ProfileRow>()
    assertEquals("user A should see their own profile row", 1, ownRead.size)

    // User B querying the same row by id must see nothing — RLS, not a 403, just an empty result.
    val crossRead = clientB.postgrest.from("profiles")
      .select(Columns.list("id,username")) { filter { eq("id", userAId) } }
      .decodeList<ProfileRow>()
    assertTrue("cross-user read must return zero rows, RLS should have blocked it", crossRead.isEmpty())
  }
}
