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
  val client: SupabaseClient by lazy {
    createSupabaseClient(
      supabaseUrl = BuildConfig.SUPABASE_URL,
      supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
      install(Auth)
      install(Postgrest)
      install(Functions)
    }
  }
}
