@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.creator.CreatorGate
import com.wildodds.gymtracker.data.creator.PricingConfig
import com.wildodds.gymtracker.ui.components.BadgeSize
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.components.VerifiedBadge
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/**
 * Creator hub: Verified Creator status, publish/manage marketplace listings, earnings.
 *
 * Google Play compliance: this screen INFORMS about the tier but contains NO purchase link,
 * button, or URL — Verified Creator can't be bought in the app (external checkout links from
 * inside a Play-distributed app violate Google Play's Payments policy). The web page handles
 * purchase; the app only reflects the resulting entitlement.
 */
@Composable
fun CreatorHubScreen(navController: NavController, vm: CreatorHubViewModel = viewModel()) {
  val accent = LocalAccentColor.current
  val state by vm.state.collectAsState()
  val snackbar = remember { SnackbarHostState() }
  var publishTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }

  LaunchedEffect(Unit) { vm.refresh() }
  LaunchedEffect(state.error, state.info) {
    state.error?.let { snackbar.showSnackbar(it); vm.clearMessages() }
    state.info?.let { snackbar.showSnackbar(it); vm.clearMessages() }
  }

  publishTarget?.let { (localId, name) ->
    PublishDialog(
      programName = name,
      onPublish = { priceCents, desc -> vm.publish(localId, priceCents, desc); publishTarget = null },
      onDismiss = { publishTarget = null }
    )
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Creator hub", fontWeight = FontWeight.Bold)
            if (state.status.isVerified) {
              Spacer(Modifier.width(8.dp)); VerifiedBadge(BadgeSize.MD)
            }
          }
        },
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
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item { StatusCard(state) }

      if (state.status.isVerified) {
        item {
          Text("YOUR LISTINGS", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp)
        }
        if (state.listings.isEmpty() && state.localCandidates.isEmpty()) {
          item {
            Text("Build a program in the app first — then publish it here.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        items(state.listings, key = { "listing_${it.id}" }) { listing ->
          GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(listing.title, fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onBackground)
                Text("${listing.priceDisplay()} · ${listing.status}",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (listing.status == "published") accent
                  else MaterialTheme.colorScheme.onSurfaceVariant)
              }
              if (listing.status == "published") {
                TextButton(onClick = { vm.unpublish(listing.id) }, enabled = !state.busy,
                  modifier = Modifier.testTag("unpublish_${listing.id}")) {
                  Text("Take down", color = MaterialTheme.colorScheme.error)
                }
              } else {
                TextButton(onClick = { vm.republish(listing.id) }, enabled = !state.busy,
                  modifier = Modifier.testTag("republish_${listing.id}")) {
                  Text("Publish", color = accent)
                }
              }
            }
          }
        }
        items(state.localCandidates, key = { "local_${it.first}" }) { (id, name) ->
          GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text("Not listed yet", style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              OutlinedButton(
                onClick = { publishTarget = id to name },
                enabled = !state.busy && CreatorGate.canPublish(
                  state.status.subStatus, state.status.connectComplete, state.status.agreementAccepted
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("publish_$id")
              ) { Text("Publish…", color = accent) }
            }
          }
        }

        item {
          Text("EARNINGS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
        }
        item { EarningsCard(state) }
      }
    }
  }
}

@Composable
private fun StatusCard(state: CreatorHubViewModel.UiState) {
  val accent = LocalAccentColor.current
  GlassCard(Modifier.fillMaxWidth()) {
    Column {
      if (!state.status.signedIn) {
        Text("Sign in to use creator features", fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground)
        return@Column
      }
      if (state.status.isVerified) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          VerifiedBadge(BadgeSize.LG)
          Spacer(Modifier.width(10.dp))
          Text("You're a Verified Creator", fontWeight = FontWeight.Bold, fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(6.dp))
        val fee = PricingConfig.PLATFORM_FEE_PERCENT
        Text("You keep ${100 - fee}% of every sale — the app takes a $fee% fee. Stripe pays " +
          "your share straight to your account.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CreatorGate.blockedReason(
          state.status.subStatus, state.status.connectComplete, state.status.agreementAccepted
        )?.let { reason ->
          Spacer(Modifier.height(6.dp))
          Text("To publish: $reason — finish this on the Wild Odds website.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.status.cancelAtPeriodEnd) {
          Spacer(Modifier.height(6.dp))
          Text("Subscription cancelled — verified until the end of the paid period.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        Text("Verified Creator", fontWeight = FontWeight.Bold, fontSize = 17.sp,
          color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text("A blue verified badge next to your name, plus the ability to sell your training " +
          "programs in the marketplace for ${PricingConfig.dailyCopy()} " +
          "(${PricingConfig.disclosure()})",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        // Play policy: no purchase path, no link. Same pattern as Netflix's old "can't sign up
        // in the app" notice.
        Text("Verified Creator can't be purchased in the app.",
          style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
          color = accent)
      }
    }
  }
}

@Composable
private fun EarningsCard(state: CreatorHubViewModel.UiState) {
  val accent = LocalAccentColor.current
  val e = state.earnings
  GlassCard(Modifier.fillMaxWidth()) {
    Column {
      if (e == null || e.salesCount == 0) {
        Text("No sales yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium)
        return@Column
      }
      fun money(c: Int) = "A$" + "%.2f".format(c / 100.0)
      Row {
        Text("Sales", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${e.salesCount}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
      }
      Row {
        Text("Gross", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(money(e.grossCents), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
      }
      Row {
        Text("Platform fee (${PricingConfig.PLATFORM_FEE_PERCENT}%)", Modifier.weight(1f),
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("−${money(e.platformFeeCents)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Row {
        Text("Your earnings (90%)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground)
        Text(money(e.netCents), fontWeight = FontWeight.Bold, color = accent,
          modifier = Modifier.testTag("earnings_net"))
      }
      if (e.refundedCount > 0) {
        Text("${e.refundedCount} refunded", style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Spacer(Modifier.height(4.dp))
      Text("Card-processing fees come out of your share; Stripe pays out to your connected " +
        "account on its normal schedule.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
  }
}

/** Price + description for a new listing. Price entered in whole dollars (A$1–A$500). */
@Composable
private fun PublishDialog(
  programName: String,
  onPublish: (priceCents: Int, description: String) -> Unit,
  onDismiss: () -> Unit
) {
  var priceText by remember { mutableStateOf("10") }
  var description by remember { mutableStateOf("") }
  val priceCents = priceText.trim().toDoubleOrNull()?.let { (it * 100).toInt() }
  val valid = priceCents != null && priceCents in 100..50000
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Publish \"$programName\"") },
    text = {
      Column {
        OutlinedTextField(
          value = priceText, onValueChange = { priceText = it },
          label = { Text("Price (A$, 1–500)") }, singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("publish_price")
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = description, onValueChange = { description = it },
          label = { Text("Short description") }, minLines = 2,
          modifier = Modifier.fillMaxWidth().testTag("publish_description")
        )
        Spacer(Modifier.height(8.dp))
        priceCents?.takeIf { valid }?.let {
          val fee = com.wildodds.gymtracker.data.creator.FeeMath.platformFeeCents(it)
          Text("Buyers pay A$${"%.2f".format(it / 100.0)} — you keep " +
            "A$${"%.2f".format((it - fee) / 100.0)} (10% fee: A$${"%.2f".format(fee / 100.0)})",
            style = MaterialTheme.typography.labelSmall)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onPublish(priceCents!!, description.trim()) }, enabled = valid,
        modifier = Modifier.testTag("publish_confirm")) { Text("Publish") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}
