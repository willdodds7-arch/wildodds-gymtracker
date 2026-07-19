package com.wildodds.gymtracker.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.swappedToLift
import com.wildodds.gymtracker.data.profile.MainLift
import com.wildodds.gymtracker.data.profile.PctPrefill
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import kotlinx.coroutines.launch

/** A default-catalogue program the user asked to start/add, awaiting the pre-start checks. */
data class PendingProgramStart(val program: ParsedProgram, val activate: Boolean)

/**
 * Pre-start gate for %1RM-driven catalogue programs. In order:
 *  1. Lift-swappable programs (Russian Squat Routine): pick the lift to run the cycle on — the
 *     main-lift exercises are renamed so prefill + the 1RM check follow the choice.
 *  2. If the program prescribes percentages of 1RMs the user hasn't set, prompt for them (kg)
 *     right here — they save to Profile and the program then starts with real weights prefilled.
 * When nothing is missing, the start proceeds immediately with no extra taps.
 */
@Composable
fun ProgramStartGate(
  pending: PendingProgramStart?,
  onDismiss: () -> Unit,
  onProceed: (ParsedProgram, Boolean) -> Unit
) {
  if (pending == null) return
  val context = LocalContext.current
  val prefs = remember { ThemePreferences(context) }
  val scope = androidx.compose.runtime.rememberCoroutineScope()

  // Step 1 — lift choice (only for swappable programs).
  var chosenProgram by remember(pending) {
    mutableStateOf(if (pending.program.liftSwappable) null else pending.program)
  }
  if (chosenProgram == null) {
    LiftPickerDialog(
      programName = pending.program.name,
      onPick = { chosenProgram = pending.program.swappedToLift(it) },
      onDismiss = onDismiss
    )
    return
  }
  val program = chosenProgram!!

  // Step 2 — missing 1RMs.
  val oneRms by remember {
    kotlinx.coroutines.flow.combine(
      prefs.oneRmSquat, prefs.oneRmBench, prefs.oneRmDeadlift, prefs.oneRmOhp
    ) { s, b, d, o ->
      mapOf(MainLift.SQUAT to s, MainLift.BENCH to b, MainLift.DEADLIFT to d, MainLift.OHP to o)
    }
  }.collectAsState(initial = null)

  val rms = oneRms ?: return // still loading DataStore — the dialog appears a frame later
  val required = PctPrefill.requiredLifts(
    program.sessions.flatMap { s -> s.exercises.map { it.name to it.pct1rmTarget } }
  )
  val missing = required.filter { (rms[it] ?: 0f) <= 0f }

  if (missing.isEmpty()) {
    LaunchedEffect(program) { onProceed(program, pending.activate) }
    return
  }

  OneRmPromptDialog(
    programName = program.name,
    missing = missing,
    onSave = { entered ->
      scope.launch {
        entered.forEach { (lift, kg) -> prefs.setOneRm(lift, kg) }
        onProceed(program, pending.activate)
      }
    },
    onDismiss = onDismiss
  )
}

@Composable
private fun LiftPickerDialog(
  programName: String,
  onPick: (MainLift) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Run it on which lift?") },
    text = {
      Column {
        Text(
          "$programName is built for the squat, but the whole cycle works on any big lift. " +
            "Every session's percentages will follow the lift you pick.",
          style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(14.dp))
        MainLift.entries.forEach { lift ->
          OutlinedButton(
            onClick = { onPick(lift) },
            modifier = Modifier.fillMaxWidth().testTag("liftpick_${lift.key}")
          ) {
            Text(
              if (lift == MainLift.SQUAT) "${lift.label}  (as written)" else lift.label,
              fontWeight = FontWeight.SemiBold,
              color = if (lift == MainLift.SQUAT) accent else MaterialTheme.colorScheme.onBackground
            )
          }
          Spacer(Modifier.height(8.dp))
        }
        Text(
          "Deadlift version: only if you recover well — 18 heavy pulling sessions in 6 weeks is a lot.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
private fun OneRmPromptDialog(
  programName: String,
  missing: List<MainLift>,
  onSave: (Map<MainLift, Float>) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  val values = remember { mutableStateOf(missing.associateWith { "" }) }
  val parsed = missing.associateWith { values.value[it]?.replace(',', '.')?.toFloatOrNull() }
  val allValid = parsed.values.all { it != null && it > 0f }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("First: your one-rep maxes") },
    text = {
      Column {
        Text(
          "$programName prescribes its weights as percentages of your 1RM. Enter your current " +
            "(or best-guess) max for ${if (missing.size == 1) "this lift" else "these lifts"} and " +
            "every session will prefill with your working weights. You can update them anytime in Profile.",
          style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        missing.forEach { lift ->
          OutlinedTextField(
            value = values.value[lift] ?: "",
            onValueChange = { v -> values.value = values.value + (lift to v.filter { it.isDigit() || it == '.' || it == ',' }) },
            label = { Text("${lift.label} 1RM (kg)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("onerm_${lift.key}")
          )
          Spacer(Modifier.height(8.dp))
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(parsed.mapValues { it.value!! }) },
        enabled = allValid,
        colors = ButtonDefaults.buttonColors(containerColor = accent),
        modifier = Modifier.testTag("onerm_save")
      ) { Text("Save & start", fontWeight = FontWeight.Bold) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}
