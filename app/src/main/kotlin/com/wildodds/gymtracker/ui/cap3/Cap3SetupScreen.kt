@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.cap3

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.cap3.*
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.LocalAppColors

/**
 * CAP3 setup: units, the ten lift slots, and the estimated-max calculator. Maxes save as you type;
 * the button at the bottom only builds the program, and re-running it rebuilds from the current
 * numbers.
 */
@Composable
fun Cap3SetupScreen(navController: NavController, vm: Cap3ViewModel = viewModel()) {
  val config by vm.config.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()
  val created by vm.created.collectAsStateWithLifecycle()
  val accent = LocalAccentColor.current
  val colors = LocalAppColors.current
  val snackbar = remember { SnackbarHostState() }
  var calculatorFor by remember { mutableStateOf<Cap3Slot?>(null) }

  LaunchedEffect(message) {
    message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
  }
  LaunchedEffect(created) {
    if (created) { vm.clearCreated(); navController.popBackStack() }
  }

  calculatorFor?.let { slot ->
    Cap3MaxCalculatorSheet(
      units = config.units,
      onDismiss = { calculatorFor = null },
      onUse = { estimate -> vm.setReferenceMax(slot, estimate); calculatorFor = null }
    )
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = { Text("nSuns CAP3", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
      )
    }
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        Text(
          "Pick the variation you'll run in each slot and enter a max for it. Your training max " +
            "starts at 90% of that number, and every main-lift weight in the program follows from " +
            "it. Maxes save as you type.",
          color = colors.textSecondary,
          style = MaterialTheme.typography.bodySmall
        )
      }

      item {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
          Column {
            Text("Units", fontWeight = FontWeight.SemiBold, color = colors.text, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
              "Rounds every weight to ${formatWeight(config.units.step)} ${config.units.label}.",
              color = colors.textSecondary, style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Cap3Units.entries.forEach { unit ->
                val selected = config.units == unit
                Surface(
                  onClick = { vm.setUnits(unit) },
                  shape = RoundedCornerShape(20.dp),
                  color = if (selected) accent else accent.copy(alpha = 0.1f)
                ) {
                  Text(
                    unit.label,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    color = if (selected) Color.White else accent,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium
                  )
                }
              }
            }
          }
        }
      }

      Cap3LiftCategory.entries.forEach { category ->
        item {
          Text(
            category.label,
            fontWeight = FontWeight.Bold,
            color = colors.text,
            modifier = Modifier.padding(top = 8.dp)
          )
        }
        items(Cap3Slot.entries.filter { it.category == category }, key = { it.name }) { slot ->
          Cap3LiftSlotCard(
            slot = slot,
            lift = config.lift(slot),
            units = config.units,
            onExercise = { vm.setExercise(slot, it) },
            onReferenceMax = { vm.setReferenceMax(slot, it) },
            onCalculator = { calculatorFor = slot }
          )
        }
      }

      item {
        val ready = config.isSetUp
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { vm.createProgram() },
          enabled = ready,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) { Text(if (ready) "Create program" else "Enter a max for every slot") }
        Spacer(Modifier.height(8.dp))
        Text(
          "Creates a 3-week, 6-day program and makes it active. Rebuilding later picks up any lift " +
            "changes; your training maxes are kept.",
          color = colors.textTertiary,
          style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(32.dp))
      }
    }
  }
}

@Composable
private fun Cap3LiftSlotCard(
  slot: Cap3Slot,
  lift: Cap3LiftConfig,
  units: Cap3Units,
  onExercise: (String) -> Unit,
  onReferenceMax: (Double?) -> Unit,
  onCalculator: () -> Unit
) {
  val colors = LocalAppColors.current
  val accent = LocalAccentColor.current
  var expanded by remember { mutableStateOf(false) }

  // Seeded from the stored value and thereafter owned by the field, so saving on every keystroke
  // cannot fight the user's cursor. Re-seeds only when the stored max changes underneath us (the
  // calculator writing a value, or a reset).
  var maxText by remember(slot) { mutableStateOf(storedText(lift, units)) }
  LaunchedEffect(lift.referenceMaxKg, units) {
    val stored = storedText(lift, units)
    if (maxText.trim().toDoubleOrNull() != stored.toDoubleOrNull()) maxText = stored
  }

  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column {
      Text(
        "Variation ${slot.variation}",
        color = colors.textSecondary,
        style = MaterialTheme.typography.labelSmall
      )
      Spacer(Modifier.height(6.dp))

      Box {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface2)
            .clickable { expanded = true }
            .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
          Text(lift.exercise, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
          Icon(Icons.Default.ArrowDropDown, null, tint = colors.textSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          slot.category.options.forEach { option ->
            DropdownMenuItem(
              text = { Text(option) },
              onClick = { onExercise(option); expanded = false }
            )
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = maxText,
          onValueChange = {
            maxText = it
            onReferenceMax(it.trim().toDoubleOrNull())
          },
          label = { Text("Max (${units.label})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onCalculator) {
          Icon(Icons.Default.Calculate, "Estimate from a rep set", tint = accent)
        }
      }

      if (lift.isConfigured) {
        Spacer(Modifier.height(6.dp))
        Text(
          "Training max ${formatWeight(units.fromKg(lift.trainingMaxKg), units)}",
          color = colors.textSecondary,
          style = MaterialTheme.typography.labelSmall
        )
      }
    }
  }
}

private fun storedText(lift: Cap3LiftConfig, units: Cap3Units): String =
  if (lift.referenceMaxKg > 0) formatWeight(units.fromKg(lift.referenceMaxKg)) else ""

/** The Epley estimated-max calculator, available wherever a max is asked for. */
@Composable
fun Cap3MaxCalculatorSheet(
  units: Cap3Units,
  onDismiss: () -> Unit,
  onUse: (Double) -> Unit
) {
  val colors = LocalAppColors.current
  val accent = LocalAccentColor.current
  var weight by remember { mutableStateOf("") }
  var reps by remember { mutableStateOf("") }

  val estimate = remember(weight, reps) {
    val w = weight.trim().toDoubleOrNull()
    val r = reps.trim().toIntOrNull()
    if (w != null && w > 0 && r != null && r > 0) Cap3Engine.estimatedOneRepMax(w, r) else null
  }

  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
      Text("Estimated max", fontWeight = FontWeight.Bold, color = colors.text)
      Spacer(Modifier.height(4.dp))
      Text(
        "Epley: weight × (0.0333 × reps + 1), rounded down.",
        color = colors.textSecondary,
        style = MaterialTheme.typography.labelSmall
      )
      Spacer(Modifier.height(16.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = weight, onValueChange = { weight = it },
          label = { Text("Weight (${units.label})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = reps, onValueChange = { reps = it },
          label = { Text("Reps") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true, modifier = Modifier.weight(1f)
        )
      }
      Spacer(Modifier.height(16.dp))
      Text(
        estimate?.let { formatWeight(it, units) } ?: "—",
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        color = colors.text
      )
      Spacer(Modifier.height(16.dp))
      Button(
        onClick = { estimate?.let(onUse) },
        enabled = estimate != null,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = accent)
      ) { Text("Use as max") }
    }
  }
}
