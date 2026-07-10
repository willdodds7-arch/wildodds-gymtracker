package com.wildodds.gymtracker.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.backend.AuthRepository
import com.wildodds.gymtracker.data.backend.RemoteError
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import io.github.jan.supabase.gotrue.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One ViewModel for the whole auth/onboarding area (welcome → age gate → sign in/up →
 * consent → username), following the one-VM-per-screen-area convention.
 */
class AuthViewModel(app: Application) : AndroidViewModel(app), AuthActions {

  private val repo = AuthRepository()
  private val prefs = ThemePreferences(app)

  override val sessionStatus: StateFlow<SessionStatus> get() = repo.sessionStatus

  override val onboardingComplete: StateFlow<Boolean> = prefs.onboardingComplete
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)
  override val ageGatePassed: StateFlow<Boolean> = prefs.ageGatePassed
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)
  override val ageGateBlocked: StateFlow<Boolean> = prefs.ageGateBlocked
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  private val _form = MutableStateFlow(AuthFormState())
  override val form: StateFlow<AuthFormState> = _form.asStateFlow()

  val currentUserEmail: String? get() = repo.currentUserEmail

  // ── Age gate ────────────────────────────────────────────────────────────────
  override fun recordAgeGateResult(oldEnough: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      if (oldEnough) prefs.setAgeGatePassed(true) else prefs.setAgeGateBlocked(true)
    }
  }

  // ── Email / password ────────────────────────────────────────────────────────
  override fun signUp(email: String, password: String) = runAuthOp { repo.signUp(email, password) }
  override fun signIn(email: String, password: String) = runAuthOp { repo.signIn(email, password) }
  override fun signInWithGoogleIdToken(idToken: String) = runAuthOp { repo.signInWithGoogleIdToken(idToken) }

  override fun requestPasswordReset(email: String) {
    viewModelScope.launch {
      _form.value = AuthFormState(isLoading = true)
      _form.value = when (val r = repo.requestPasswordReset(email)) {
        is RemoteResult.Success -> AuthFormState(resetEmailSent = true)
        is RemoteResult.Failure -> AuthFormState(error = r.error.userMessage())
      }
    }
  }

  override fun updatePassword(newPassword: String, onDone: () -> Unit) {
    viewModelScope.launch {
      _form.value = AuthFormState(isLoading = true)
      when (val r = repo.updatePassword(newPassword)) {
        is RemoteResult.Success -> { _form.value = AuthFormState(); onDone() }
        is RemoteResult.Failure -> _form.value = AuthFormState(error = r.error.userMessage())
      }
    }
  }

  override fun clearError() { _form.value = _form.value.copy(error = null, resetEmailSent = false) }

  // ── Post-auth setup (consent → username) ────────────────────────────────────
  override fun setAnalyticsConsent(granted: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      prefs.setAnalyticsConsent(if (granted) "granted" else "denied")
    }
  }

  /** Saves the username (best-effort — a failure here never traps the user in onboarding;
   *  they can set it again later from Settings) and marks onboarding done. */
  override fun finishOnboarding(username: String?) {
    viewModelScope.launch(Dispatchers.IO) {
      if (!username.isNullOrBlank()) repo.setUsername(username.trim())
      prefs.setOnboardingComplete(true)
    }
  }

  fun signOut() {
    viewModelScope.launch(Dispatchers.IO) {
      repo.signOut()
      // Next sign-in re-runs consent/username; local Room data is deliberately untouched.
      prefs.setOnboardingComplete(false)
    }
  }

  private fun runAuthOp(op: suspend () -> RemoteResult<Unit>) {
    viewModelScope.launch {
      _form.value = AuthFormState(isLoading = true)
      _form.value = when (val r = op()) {
        is RemoteResult.Success -> AuthFormState() // sessionStatus flips to Authenticated; gate moves on
        is RemoteResult.Failure -> AuthFormState(error = r.error.userMessage())
      }
    }
  }
}

/** Short, honest, user-facing message per error category. No copy claims the app is offline-only. */
fun RemoteError.userMessage(): String = when (this) {
  is RemoteError.Offline -> "No connection — check your internet and try again."
  is RemoteError.Unauthorized -> message.ifBlank { "Email or password is incorrect." }
  is RemoteError.RateLimited -> "Too many attempts — wait a minute and try again."
  is RemoteError.ServerError -> "Server problem — try again shortly."
  is RemoteError.Unknown -> message.ifBlank { "Something went wrong — try again." }
}
