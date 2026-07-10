package com.wildodds.gymtracker.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.wildodds.gymtracker.BuildConfig
import kotlinx.coroutines.launch

/**
 * "Continue with Google" via Credential Manager. Renders nothing until GOOGLE_WEB_CLIENT_ID is
 * configured (google.webClientId in local.properties / CI secret), so email/password ships and
 * works while the Google Cloud OAuth client is still pending — see docs/backend.md for setup.
 */
@Composable
fun GoogleSignInButton(onIdToken: (String) -> Unit) {
  if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return

  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  Spacer(Modifier.height(12.dp))
  OutlinedButton(
    onClick = {
      scope.launch {
        // Cancellation (user dismisses the account picker) and no-credential states are
        // non-errors — just do nothing and leave them on the auth screen.
        runCatching {
          val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build()
          val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
          val result = CredentialManager.create(context).getCredential(context, request)
          GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        }.onSuccess(onIdToken)
      }
    },
    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth_google"),
    shape = RoundedCornerShape(12.dp)
  ) {
    Text("Continue with Google", fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onBackground)
  }
}
