package com.wildodds.gymtracker.data.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.SignOutScope
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * All account operations, on Dispatchers.IO, every remote call wrapped in [RemoteResult].
 * Session persistence + auto-refresh are supabase-kt defaults (SharedPreferences-backed
 * SettingsSessionManager, alwaysAutoRefresh) — nothing to do here for those.
 */
class AuthRepository(private val client: SupabaseClient = SupabaseModule.client) {

  /** Emits LoadingFromStorage → Authenticated / NotAuthenticated / NetworkError. AuthGate maps
   *  this (plus onboarding state) to what's on screen. */
  val sessionStatus: StateFlow<SessionStatus> get() = client.auth.sessionStatus

  val currentUserId: String? get() = client.auth.currentUserOrNull()?.id
  val currentUserEmail: String? get() = client.auth.currentUserOrNull()?.email

  suspend fun signUp(email: String, password: String): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        client.auth.signUpWith(Email) {
          this.email = email
          this.password = password
        }
        Unit
      }
    }

  suspend fun signIn(email: String, password: String): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        client.auth.signInWith(Email) {
          this.email = email
          this.password = password
        }
      }
    }

  /** Exchange a Google ID token (from Credential Manager) for a Supabase session. */
  suspend fun signInWithGoogleIdToken(googleIdToken: String): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        client.auth.signInWith(IDToken) {
          idToken = googleIdToken
          provider = Google
        }
      }
    }

  /** LOCAL scope: clears this device's session only. Room data is untouched by design —
   *  sign-out never destroys local training history. */
  suspend fun signOut(): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote { client.auth.signOut(SignOutScope.LOCAL) }
    }

  /** Sends the reset email; the link deep-links back into the app (wildodds://auth), where
   *  handleDeeplinks() establishes a recovery session and the UI asks for a new password. */
  suspend fun requestPasswordReset(email: String): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        client.auth.resetPasswordForEmail(
          email = email,
          redirectUrl = "${SupabaseModule.DEEPLINK_SCHEME}://${SupabaseModule.DEEPLINK_HOST}"
        )
      }
    }

  suspend fun updatePassword(newPassword: String): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        client.auth.updateUser { password = newPassword }
        Unit
      }
    }

  /** Writes the username onto the caller's own profiles row (RLS restricts it to exactly that). */
  suspend fun setUsername(username: String): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        val id = checkNotNull(currentUserId) { "not signed in" }
        client.postgrest.from("profiles").update({ set("username", username) }) {
          filter { eq("id", id) }
        }
        Unit
      }
    }
}
