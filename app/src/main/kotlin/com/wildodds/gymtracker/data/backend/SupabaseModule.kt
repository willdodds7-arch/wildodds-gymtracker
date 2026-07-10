package com.wildodds.gymtracker.data.backend

import com.wildodds.gymtracker.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Single Supabase client for the whole app — no DI framework here (same "construct directly,
 * lazy singleton" pattern as [com.wildodds.gymtracker.data.db.AppDatabase.getInstance]).
 *
 * Only the anon/publishable key ever lives in [BuildConfig] — it's meant to be public, with every
 * table's row-level security policy doing the actual access control. The secret/service_role key
 * must never appear in app code; it belongs only in Edge Functions and CI secrets.
 */
object SupabaseModule {
  /** Custom scheme/host for auth deep links (password reset, email confirmation). Must match
   *  the MainActivity intent-filter AND the redirect URL allow-list in the Supabase dashboard
   *  (Authentication → URL Configuration → add `wildodds://auth`). */
  const val DEEPLINK_SCHEME = "wildodds"
  const val DEEPLINK_HOST = "auth"

  val client: SupabaseClient by lazy {
    createSupabaseClient(
      supabaseUrl = BuildConfig.SUPABASE_URL,
      supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
      install(Auth) {
        scheme = DEEPLINK_SCHEME
        host = DEEPLINK_HOST
      }
      install(Postgrest)
      install(Functions)
    }
  }
}
