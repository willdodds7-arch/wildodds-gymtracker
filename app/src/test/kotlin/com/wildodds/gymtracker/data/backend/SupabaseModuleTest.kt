@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.wildodds.gymtracker.data.backend

import com.wildodds.gymtracker.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.MemoryCodeVerifierCache
import io.github.jan.supabase.gotrue.MemorySessionManager
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupabaseModuleTest {

  // Auth's init kicks off a lifecycle-callback coroutine on Dispatchers.Main, which doesn't
  // exist by default on the JVM — kotlinx-coroutines-test's Main dispatcher stands in for it.
  @Before
  fun setMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

  @After
  fun resetMainDispatcher() { Dispatchers.resetMain() }

  @Test
  fun buildConfigHasNonBlankSupabaseUrlAndAnonKey() {
    // Fails loudly (rather than the client throwing an obscure error later) if local.properties
    // is missing supabase.url/supabase.anonKey, or the CI secrets they fall back to aren't set.
    assertTrue("SUPABASE_URL is blank — set supabase.url in local.properties", BuildConfig.SUPABASE_URL.isNotBlank())
    assertTrue("SUPABASE_ANON_KEY is blank — set supabase.anonKey in local.properties", BuildConfig.SUPABASE_ANON_KEY.isNotBlank())
    assertTrue("SUPABASE_URL should be an https URL", BuildConfig.SUPABASE_URL.startsWith("https://"))
    // The secret/service_role key must never end up here — publishable keys don't start with it.
    assertFalse("looks like a secret key ended up in the anon key slot", BuildConfig.SUPABASE_ANON_KEY.startsWith("sb_secret_"))
  }

  @Test
  fun clientConstructsWithoutThrowing() {
    // Mirrors SupabaseModule.client's setup exactly, except for sessionManager: Auth's real
    // default (SettingsSessionManager, backed by Android SharedPreferences) needs a fully
    // attached Context that only a real device/emulator provides — it's not what this test is
    // checking. Swapping in MemorySessionManager here proves Auth/Postgrest/Functions install
    // cleanly against BuildConfig's URL/key without depending on that runtime plumbing.
    val client = createSupabaseClient(
      supabaseUrl = BuildConfig.SUPABASE_URL,
      supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
      install(Auth) {
        sessionManager = MemorySessionManager()
        codeVerifierCache = MemoryCodeVerifierCache()
      }
      install(Postgrest)
      install(Functions)
    }
    assertTrue(client.supabaseUrl.isNotBlank())
  }
}
