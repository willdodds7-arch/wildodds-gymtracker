package com.wildodds.gymtracker.ui.friends

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.friends.FriendEvent
import com.wildodds.gymtracker.data.friends.FriendOfFriend
import com.wildodds.gymtracker.data.friends.FriendProfile
import com.wildodds.gymtracker.data.friends.FriendsRepository
import com.wildodds.gymtracker.ui.auth.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One VM for the friends area (list, detail, invites, events). */
class FriendsViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = FriendsRepository(app)

  data class FriendsUiState(
    val isLoading: Boolean = true,
    val signedIn: Boolean = false,
    val myCode: String? = null,
    val notifyOnFriendSession: Boolean = false,
    val friends: List<FriendProfile> = emptyList(),
    val error: String? = null,
    val info: String? = null
  )

  data class DetailUiState(
    val isLoading: Boolean = true,
    val profile: FriendProfile? = null,
    val theirFriends: List<FriendOfFriend> = emptyList()
  )

  private val _state = MutableStateFlow(FriendsUiState())
  val state: StateFlow<FriendsUiState> = _state.asStateFlow()

  private val _detail = MutableStateFlow(DetailUiState())
  val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

  /** Motivation messages awaiting a 💪 — shown on the session screen. */
  private val _pendingMotivations = MutableStateFlow<List<Pair<FriendEvent, String>>>(emptyList())
  val pendingMotivations: StateFlow<List<Pair<FriendEvent, String>>> = _pendingMotivations.asStateFlow()

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      val signedIn = runCatching { repo.myUserId != null }.getOrDefault(false)
      if (!signedIn) { _state.value = FriendsUiState(isLoading = false, signedIn = false); return@launch }
      val me = repo.myProfile()
      val friends = repo.friends()
      _state.value = FriendsUiState(
        isLoading = false,
        signedIn = true,
        myCode = (me as? RemoteResult.Success)?.value?.friendCode,
        notifyOnFriendSession = (me as? RemoteResult.Success)?.value?.notifyOnFriendSession ?: false,
        friends = (friends as? RemoteResult.Success)?.value?.sortedBy { it.username ?: "zz" } ?: emptyList(),
        error = (friends as? RemoteResult.Failure)?.error?.userMessage()
      )
    }
  }

  fun loadDetail(friendId: String) {
    _detail.value = DetailUiState()
    viewModelScope.launch(Dispatchers.IO) {
      val profile = (repo.friendProfile(friendId) as? RemoteResult.Success)?.value
      val theirs = (repo.friendsOf(friendId) as? RemoteResult.Success)?.value ?: emptyList()
      _detail.value = DetailUiState(isLoading = false, profile = profile, theirFriends = theirs)
    }
  }

  fun redeem(code: String, onDone: (Boolean) -> Unit = {}) {
    viewModelScope.launch(Dispatchers.IO) {
      when (val r = repo.redeemCode(code)) {
        is RemoteResult.Success -> { _state.value = _state.value.copy(info = "Friend added!"); refresh(); onDone(true) }
        is RemoteResult.Failure -> { _state.value = _state.value.copy(error = r.error.userMessage()); onDone(false) }
      }
    }
  }

  fun unfriend(friendId: String) {
    viewModelScope.launch(Dispatchers.IO) { repo.unfriend(friendId); refresh() }
  }

  fun sendMotivation(friendId: String, message: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val r = repo.sendEvent(friendId, FriendEvent.TYPE_MOTIVATION, message.trim())
      _state.value = when (r) {
        is RemoteResult.Success -> _state.value.copy(info = "Motivation sent 💪")
        is RemoteResult.Failure -> _state.value.copy(error = r.error.userMessage())
      }
    }
  }

  fun setNotifyOnFriendSession(enabled: Boolean) {
    _state.value = _state.value.copy(notifyOnFriendSession = enabled)
    viewModelScope.launch(Dispatchers.IO) { repo.setNotifyOnFriendSession(enabled) }
  }

  fun shareProgram(friendId: String, programId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
      val r = repo.shareProgram(friendId, programId)
      _state.value = when (r) {
        is RemoteResult.Success -> _state.value.copy(info = "Program sent")
        is RemoteResult.Failure -> _state.value.copy(error = r.error.userMessage())
      }
    }
  }

  fun clearMessages() { _state.value = _state.value.copy(error = null, info = null) }

  // ── Shared-program inbox ─────────────────────────────────────────────────────

  private val _programInbox = MutableStateFlow<List<Pair<FriendEvent, String>>>(emptyList())
  val programInbox: StateFlow<List<Pair<FriendEvent, String>>> = _programInbox.asStateFlow()

  fun loadProgramInbox() {
    viewModelScope.launch(Dispatchers.IO) {
      val events = (repo.unseenProgramShares() as? RemoteResult.Success)?.value ?: return@launch
      val names = if (events.isEmpty()) emptyMap()
      else (repo.friends() as? RemoteResult.Success)?.value
        ?.associateBy({ it.id }, { it.username ?: "A friend" }) ?: emptyMap()
      _programInbox.value = events.map { it to (names[it.sender] ?: "A friend") }
    }
  }

  fun acceptSharedProgram(event: FriendEvent) {
    _programInbox.value = _programInbox.value.filterNot { it.first.id == event.id }
    viewModelScope.launch(Dispatchers.IO) {
      _state.value = when (repo.importSharedProgram(event)) {
        is RemoteResult.Success -> _state.value.copy(info = "Program added to your library")
        is RemoteResult.Failure -> _state.value.copy(error = "Couldn't import that program")
      }
    }
  }

  fun dismissSharedProgram(event: FriendEvent) {
    _programInbox.value = _programInbox.value.filterNot { it.first.id == event.id }
    viewModelScope.launch(Dispatchers.IO) { repo.markSeen(event.id) }
  }

  // ── Session-screen integration ───────────────────────────────────────────────

  fun loadPendingMotivations() {
    viewModelScope.launch(Dispatchers.IO) {
      val events = (repo.unseenMotivations() as? RemoteResult.Success)?.value ?: return@launch
      if (events.isEmpty()) { _pendingMotivations.value = emptyList(); return@launch }
      val names = (repo.friends() as? RemoteResult.Success)?.value
        ?.associateBy({ it.id }, { it.username ?: "A friend" }) ?: emptyMap()
      _pendingMotivations.value = events.map { it to (names[it.sender] ?: "A friend") }
    }
  }

  /** 💪 acknowledge: mark seen + send the flex back (sender gets "X flexed for you"). */
  fun flexBack(event: FriendEvent) {
    _pendingMotivations.value = _pendingMotivations.value.filterNot { it.first.id == event.id }
    viewModelScope.launch(Dispatchers.IO) { repo.flexBack(event) }
  }

  // Session-start fan-out and snapshot publishing live in SessionViewModel — they're tied to the
  // moment the first set is logged / the session completes, not to this screen.
}
