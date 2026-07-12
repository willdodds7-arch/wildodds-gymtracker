package com.wildodds.gymtracker.data.account

import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.backend.SupabaseModule
import com.wildodds.gymtracker.data.backend.runRemote
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.sync.BackupManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The account-lifecycle operations the UI depends on, as an interface so the delete SEQUENCE
 * (re-auth must succeed before the irreversible call) is unit-testable with a fake — the real
 * impl talks to Supabase + Room and can't run on a plain JVM.
 */
interface AccountOps {
  suspend fun buildExport(exportedAt: Long): AccountExport
  suspend fun reauthenticate(password: String): RemoteResult<Unit>
  suspend fun deleteAccount(): RemoteResult<Unit>
  suspend fun wipeLocalData()
  suspend fun signOut(): RemoteResult<Unit>
}

/**
 * Account lifecycle (Phase 5): data export, re-auth, and server-side deletion.
 *
 * Deletion goes through the `delete-account` Edge Function (service role) rather than client
 * calls, so it can remove the auth.users row — which the client can't. The FK cascades
 * (profiles / sync_rows / analytics_events all `on delete cascade` from auth.users) then wipe
 * every owned row; the function also deletes them explicitly first for defence-in-depth.
 */
class AccountRepository(
  private val app: android.content.Context,
  // Lazy so merely constructing the repository (e.g. when a ViewModel is created during a Compose
  // test) doesn't build the Supabase client — that needs Dispatchers.Main and real session storage.
  clientProvider: () -> SupabaseClient = { SupabaseModule.client }
) : AccountOps {
  private val client: SupabaseClient by lazy(clientProvider)
  private val db get() = AppDatabase.getInstance(app)

  @Serializable
  private data class ProfileRow(val username: String? = null)

  /** Assemble the full data export from the local training snapshot + account/profile info. */
  override suspend fun buildExport(exportedAt: Long): AccountExport = withContext(Dispatchers.IO) {
    val snapshot = BackupManager(db).snapshot(exportedAt)
    val userId = client.auth.currentUserOrNull()?.id
    val email = client.auth.currentUserOrNull()?.email
    val username = runCatching {
      userId?.let {
        client.postgrest.from("profiles").select { filter { eq("id", it) } }
          .decodeList<ProfileRow>().firstOrNull()?.username
      }
    }.getOrNull()
    val consent = ThemePreferences(app).analyticsConsent.first()

    AccountExport(
      exportedAt = exportedAt,
      account = AccountExport.Account(userId, email, username),
      training = snapshot,
      analyticsNote = AccountExport.AnalyticsNote(analyticsConsent = consent)
    )
  }

  /** Fresh re-authentication before a destructive action. Password path (Google users re-auth
   *  via the Google button on the delete screen). Returns success only if the credentials verify. */
  override suspend fun reauthenticate(password: String): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    val email = client.auth.currentUserOrNull()?.email
      ?: return@withContext RemoteResult.Failure(
        com.wildodds.gymtracker.data.backend.RemoteError.Unauthorized("No email on the current session")
      )
    runRemote { client.auth.signInWith(Email) { this.email = email; this.password = password } }
  }

  /**
   * Invoke the rate-limited `delete-account` Edge Function. Immediate and irreversible — no grace
   * period. On success the caller signs out; whether local Room data is also wiped is the user's
   * choice ([wipeLocalData]).
   */
  override suspend fun deleteAccount(): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      val response: HttpResponse = client.functions.invoke("delete-account")
      check(response.status.isSuccess()) { "delete-account returned ${response.status}" }
      Unit
    }
  }

  override suspend fun wipeLocalData() = withContext(Dispatchers.IO) {
    // Restore an empty snapshot = wipe every training table in one transaction.
    BackupManager(db).restore(com.wildodds.gymtracker.data.sync.TrainingSnapshot(exportedAt = 0))
    // Reset onboarding/consent so a future sign-in starts clean.
    ThemePreferences(app).setOnboardingComplete(false)
    ThemePreferences(app).setAnalyticsConsent("unset")
  }

  override suspend fun signOut(): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote { client.auth.signOut(io.github.jan.supabase.gotrue.SignOutScope.LOCAL) }
  }
}
