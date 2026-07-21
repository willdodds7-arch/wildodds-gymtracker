@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.marketplace

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.creator.CreatorPublicRow
import com.wildodds.gymtracker.data.creator.CreatorRepository
import com.wildodds.gymtracker.data.creator.MarketplaceListing
import com.wildodds.gymtracker.ui.components.BadgeSize
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.components.VerifiedBadge
import com.wildodds.gymtracker.ui.creator.readable
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MarketplaceViewModel(app: Application) : AndroidViewModel(app) {
  private val repo = CreatorRepository(app)

  data class UiState(
    val isLoading: Boolean = true,
    val listings: List<MarketplaceListing> = emptyList(),
    val creators: Map<String, CreatorPublicRow> = emptyMap(),
    val ownedProgramIds: Set<Long> = emptySet(),
    val importedIds: Set<Long> = emptySet(),
    val error: String? = null,
    val info: String? = null
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      val browse = (repo.browse() as? RemoteResult.Success)?.value
      val owned = (repo.myPurchases() as? RemoteResult.Success)?.value ?: emptyList()
      _state.value = _state.value.copy(
        isLoading = false,
        listings = browse?.first ?: emptyList(),
        creators = browse?.second ?: emptyMap(),
        ownedProgramIds = owned.map { it.programId }.toSet(),
        error = if (browse == null) "Couldn't load the marketplace — check your connection" else null
      )
    }
  }

  fun import(programId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
      _state.value = when (val r = repo.importPurchased(programId)) {
        is RemoteResult.Success -> _state.value.copy(
          info = "Added to your library",
          importedIds = _state.value.importedIds + programId
        )
        is RemoteResult.Failure -> _state.value.copy(error = r.error.readable())
      }
    }
  }

  fun clearMessages() { _state.value = _state.value.copy(error = null, info = null) }
}

/**
 * Marketplace browse. Google Play compliance: programs the user hasn't bought show their price
 * as INFORMATION ONLY — there is no buy button, purchase link, or web-checkout URL anywhere in
 * the app. Owned programs (bought on the web) can be added to the library here.
 */
@Composable
fun MarketplaceScreen(navController: NavController, vm: MarketplaceViewModel = viewModel()) {
  val accent = LocalAccentColor.current
  val state by vm.state.collectAsState()
  val snackbar = remember { SnackbarHostState() }

  LaunchedEffect(Unit) { vm.refresh() }
  LaunchedEffect(state.error, state.info) {
    state.error?.let { snackbar.showSnackbar(it); vm.clearMessages() }
    state.info?.let { snackbar.showSnackbar(it); vm.clearMessages() }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = { Text("Marketplace", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
      )
    }
  ) { padding ->
    if (state.isLoading) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = accent)
      }
      return@Scaffold
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).testTag("marketplace_list"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        Text("Programs by Verified Creators. Ones you own can be added straight to your library.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (state.listings.isEmpty()) {
        item {
          Text("Nothing published yet — check back soon.",
            modifier = Modifier.padding(vertical = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      items(state.listings, key = { it.id }) { listing ->
        val creator = state.creators[listing.creatorId]
        val owned = listing.id in state.ownedProgramIds
        val imported = listing.id in state.importedIds
        GlassCard(Modifier.fillMaxWidth()) {
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(listing.title, fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onBackground)
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text("by ${creator?.username ?: "a creator"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                  if (creator?.isVerifiedCreator == true) {
                    Spacer(Modifier.width(4.dp)); VerifiedBadge(BadgeSize.SM)
                  }
                }
              }
              Text(listing.priceDisplay(), fontWeight = FontWeight.Bold, color = accent)
            }
            if (listing.description.isNotBlank()) {
              Spacer(Modifier.height(4.dp))
              Text(listing.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val specs = listOfNotNull(
              listing.daysPerWeek.takeIf { it > 0 }?.let { "$it days/week" },
              listing.totalWeeks.takeIf { it > 0 }?.let { "$it weeks" }
            ).joinToString(" · ")
            if (specs.isNotEmpty()) {
              Text(specs, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            when {
              imported -> Text("In your library ✓", color = accent,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
              owned -> OutlinedButton(
                onClick = { vm.import(listing.id) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("import_${listing.id}")
              ) { Text("Add to library", color = accent) }
              else -> Text("Not available for purchase in the app",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp)
            }
          }
        }
      }
    }
  }
}
