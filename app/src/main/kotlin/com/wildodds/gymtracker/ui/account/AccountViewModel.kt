package com.wildodds.gymtracker.ui.account

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.account.AccountExporter
import com.wildodds.gymtracker.data.account.AccountOps
import com.wildodds.gymtracker.data.account.AccountRepository
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.ui.auth.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the two account-lifecycle flows: export-my-data (writes a JSON zip to a user-picked SAF
 * location) and delete-account (type-to-confirm → re-auth → Edge Function → sign out, with an
 * optional local wipe).
 */
class AccountViewModel @JvmOverloads constructor(
  app: Application,
  // @JvmOverloads generates the (Application)-only constructor that AndroidViewModelFactory needs,
  // while tests can still inject a fake AccountOps via the two-arg constructor.
  private val repo: AccountOps = AccountRepository(app)
) : AndroidViewModel(app) {

  data class ExportState(val inProgress: Boolean = false, val done: Boolean = false, val error: String? = null)
  data class DeleteState(
    val step: Step = Step.IDLE,
    val error: String? = null
  ) { enum class Step { IDLE, REAUTHING, DELETING, DONE } }

  private val _export = MutableStateFlow(ExportState())
  val export: StateFlow<ExportState> = _export.asStateFlow()

  private val _delete = MutableStateFlow(DeleteState())
  val delete: StateFlow<DeleteState> = _delete.asStateFlow()

  val signedInEmail: String? get() = runCatching {
    com.wildodds.gymtracker.data.backend.AuthRepository().currentUserEmail
  }.getOrNull()

  /** Confirmation phrase the user must type before delete is enabled. */
  val confirmPhrase = "DELETE"

  /** Write the export zip to the SAF-picked [target] uri. */
  fun exportTo(target: Uri) {
    viewModelScope.launch {
      _export.value = ExportState(inProgress = true)
      val result = runCatching {
        withContext(Dispatchers.IO) {
          val export = repo.buildExport(System.currentTimeMillis())
          val bytes = AccountExporter.toZipBytes(export)
          getApplication<Application>().contentResolver.openOutputStream(target)?.use { it.write(bytes) }
            ?: error("Couldn't open the chosen location for writing.")
        }
      }
      _export.value = result.fold(
        onSuccess = { ExportState(done = true) },
        onFailure = { ExportState(error = it.message ?: "Export failed.") }
      )
    }
  }

  fun resetExport() { _export.value = ExportState() }

  /**
   * The full delete sequence: re-auth with the password, then invoke the Edge Function, then sign
   * out (and optionally wipe local data). Any failure stops before the irreversible step.
   */
  fun deleteAccount(password: String, alsoWipeLocal: Boolean) {
    viewModelScope.launch {
      _delete.value = DeleteState(step = DeleteState.Step.REAUTHING)
      when (val reauth = repo.reauthenticate(password)) {
        is RemoteResult.Failure -> {
          _delete.value = DeleteState(error = "Couldn't verify it's you: ${reauth.error.userMessage()}")
          return@launch
        }
        is RemoteResult.Success -> Unit
      }

      _delete.value = DeleteState(step = DeleteState.Step.DELETING)
      when (val del = repo.deleteAccount()) {
        is RemoteResult.Failure -> {
          _delete.value = DeleteState(error = "Deletion failed: ${del.error.userMessage()}")
          return@launch
        }
        is RemoteResult.Success -> Unit
      }

      if (alsoWipeLocal) repo.wipeLocalData()
      repo.signOut()
      _delete.value = DeleteState(step = DeleteState.Step.DONE)
    }
  }

  fun clearDeleteError() { _delete.value = _delete.value.copy(error = null) }
}
