package com.wildodds.gymtracker.ui.creator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.creator.CreatorRepository
import com.wildodds.gymtracker.data.creator.CreatorStatus
import com.wildodds.gymtracker.data.creator.EarningsSummary
import com.wildodds.gymtracker.data.creator.MarketplaceListing
import com.wildodds.gymtracker.data.creator.PurchaseRow
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Creator hub: entitlement status, listings management, earnings. */
class CreatorHubViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = CreatorRepository(app)
  private val db = AppDatabase.getInstance(app)

  data class UiState(
    val isLoading: Boolean = true,
    val status: CreatorStatus = CreatorStatus(signedIn = false),
    val listings: List<MarketplaceListing> = emptyList(),
    /** Local user-created programs not yet listed, offered for publishing. */
    val localCandidates: List<Pair<Long, String>> = emptyList(),
    val earnings: EarningsSummary? = null,
    val sales: List<PurchaseRow> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      val status = (repo.myStatus() as? RemoteResult.Success)?.value ?: CreatorStatus(signedIn = false)
      val listings = (repo.myListings() as? RemoteResult.Success)?.value ?: emptyList()
      val earningsPair = (repo.myEarnings() as? RemoteResult.Success)?.value
      val listedTitles = listings.map { it.title }.toSet()
      val locals = runCatching {
        db.programDao().getAllProgramsOnce()
          .filter { it.isUserCreated && it.name != GymRepository.ON_DEMAND_PROGRAM_NAME }
          .filter { it.name !in listedTitles }
          .map { it.id to it.name }
      }.getOrDefault(emptyList())
      _state.value = UiState(
        isLoading = false,
        status = status,
        listings = listings,
        localCandidates = locals,
        earnings = earningsPair?.first,
        sales = earningsPair?.second ?: emptyList()
      )
    }
  }

  fun publish(localProgramId: Long, priceCents: Int, description: String) {
    _state.value = _state.value.copy(busy = true)
    viewModelScope.launch(Dispatchers.IO) {
      when (val r = repo.publishLocalProgram(localProgramId, priceCents, description)) {
        is RemoteResult.Success ->
          _state.value = _state.value.copy(busy = false, info = "Published to the marketplace")
        is RemoteResult.Failure ->
          _state.value = _state.value.copy(busy = false, error = r.error.readable())
      }
      refresh()
    }
  }

  fun republish(listingId: Long) {
    // Publishing an existing listing again = publish action only (content already uploaded).
    _state.value = _state.value.copy(busy = true)
    viewModelScope.launch(Dispatchers.IO) {
      val r = repo.publishExisting(listingId)
      _state.value = when (r) {
        is RemoteResult.Success -> _state.value.copy(busy = false, info = "Published")
        is RemoteResult.Failure -> _state.value.copy(busy = false, error = r.error.readable())
      }
      refresh()
    }
  }

  fun unpublish(listingId: Long) {
    _state.value = _state.value.copy(busy = true)
    viewModelScope.launch(Dispatchers.IO) {
      val r = repo.unpublish(listingId)
      _state.value = when (r) {
        is RemoteResult.Success -> _state.value.copy(busy = false, info = "Listing taken down")
        is RemoteResult.Failure -> _state.value.copy(busy = false, error = r.error.readable())
      }
      refresh()
    }
  }

  fun clearMessages() { _state.value = _state.value.copy(error = null, info = null) }
}

internal fun com.wildodds.gymtracker.data.backend.RemoteError.readable(): String = when (this) {
  is com.wildodds.gymtracker.data.backend.RemoteError.Offline -> "You're offline — try again when connected"
  is com.wildodds.gymtracker.data.backend.RemoteError.Unauthorized -> "Sign in to your account first"
  is com.wildodds.gymtracker.data.backend.RemoteError.RateLimited -> "Too many requests — wait a moment"
  is com.wildodds.gymtracker.data.backend.RemoteError.ServerError -> message
  is com.wildodds.gymtracker.data.backend.RemoteError.Unknown -> message
}
