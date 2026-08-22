@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.programimport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.importer.FlagSeverity
import com.wildodds.gymtracker.ui.components.AppDivider
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.*

/**
 * The review screen — where import accuracy is actually won.
 *
 * Everything upstream is a best reading of a document; this is where the user confirms it. So the
 * screen is built around showing its working rather than showing a result:
 *
 *  - the **source line** sits beside every parsed row, always, so what was read and what was
 *    ignored are both visible;
 *  - a low-confidence row says WHY ("62% - exercise inferred, 31% of line unparsed"), because a
 *    bare number is something users learn to ignore;
 *  - correcting a name applies to every occurrence AND learns the alias, so the same coach's next
 *    sheet needs no correction;
 *  - "jump to the next problem" and a remaining count make reviewing forty exercises tractable,
 *    and bulk-accept covers only the rows above the threshold — bulk-accepting the uncertain ones
 *    would be the same as not reviewing at all;
 *  - the import is reversible from here, and a re-import of the same file offers a choice instead
 *    of silently making a second copy.
 *
 * Nothing is saved until Start / Save, and every ambiguity is surfaced rather than resolved.
 */
@Composable
fun ImportReviewScreen(navController: NavController, vm: ImportReviewViewModel = viewModel()) {
  val s by vm.state.collectAsStateWithLifecycle()
  val c = LocalAppColors.current

  Scaffold(
    containerColor = c.bg,
    topBar = {
      TopAppBar(
        title = { Text(if (s.parsed) "Review Import" else "Import Program", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, titleContentColor = c.text, navigationIconContentColor = c.text)
      )
    }
  ) { pad ->
    if (!s.parsed) PasteStep(vm, s, c, Modifier.padding(pad))
    else ReviewStep(vm, s, c, Modifier.padding(pad)) { id ->
      // On commit, go home; the imported program is now active.
      navController.popBackStack()
    }
  }
}

@Composable
private fun PasteStep(vm: ImportReviewViewModel, s: ImportReviewState, c: AppColors, modifier: Modifier) {
  val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
  ) { uri -> uri?.let { vm.loadFromFile(it) } }

  Column(
    modifier.fillMaxSize().padding(horizontal = Space.screenEdge).verticalScroll(rememberScrollState()).imePadding(),
    verticalArrangement = Arrangement.spacedBy(Space.md)
  ) {
    Spacer(Modifier.height(Space.sm))
    Text("Paste a program, or choose a file — spreadsheet, PDF, Word, a chat export, or a photo of " +
      "a printout. Week and day headings, rest days, supersets and notation like “3x8”, “5/3/1”, " +
      "“@RPE 7-8”, “1x5+ @85% TM” are all understood. Everything runs on your device, offline.",
      style = AppType.body, color = c.textSecondary)
    OutlinedButton(
      onClick = {
        // Every MIME type the pipeline can read. `*/*` is included last because the sniffer works
        // from magic bytes anyway, and content providers are unreliable about the type they report
        // - refusing a file at the picker for a MIME type we would have ignored is a poor trade.
        filePicker.launch(arrayOf(
          "text/*", "text/csv", "text/comma-separated-values", "text/tab-separated-values",
          "text/markdown", "text/html", "application/json", "application/xml", "text/xml",
          "application/pdf", "application/rtf", "text/rtf",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.ms-excel",
          "application/vnd.oasis.opendocument.spreadsheet",
          "application/vnd.oasis.opendocument.text",
          "application/zip", "image/*", "*/*"
        ))
      },
      enabled = !s.parsing,
      modifier = Modifier.fillMaxWidth().height(50.dp), shape = Radii.cardShape,
      border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text)
    ) {
      Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(Space.sm))
      Text("Choose a file  ·  csv, xlsx, pdf, docx, photo…", fontWeight = FontWeight.SemiBold)
    }
    OutlinedTextField(
      value = s.programName, onValueChange = vm::setName,
      label = { Text("Program name (optional)") }, singleLine = true,
      modifier = Modifier.fillMaxWidth(), shape = Radii.smShape,
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text)
    )
    OutlinedTextField(
      value = s.rawInput, onValueChange = vm::setRaw,
      label = { Text("Paste program here") },
      modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
      shape = Radii.smShape,
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text)
    )
    if (s.parsing && s.progress.isNotBlank()) {
      // A 12-page PDF or an OCR pass takes real time. A named stage and a cancel button beat an
      // indeterminate spinner, which tells the user nothing and cannot be escaped.
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
        Spacer(Modifier.width(Space.sm))
        Text(s.progress, style = AppType.body, color = c.textSecondary, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::cancelParsing) { Text("Cancel") }
      }
    }
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = AppType.body) }
    Button(
      onClick = vm::parse, enabled = !s.parsing && s.rawInput.isNotBlank(),
      modifier = Modifier.fillMaxWidth().height(52.dp), shape = Radii.cardShape,
      colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = androidx.compose.ui.graphics.Color.White)
    ) { Text(if (s.parsing) "Reading…" else "Parse program", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(Space.lg))
  }
}

@Composable
private fun ReviewStep(vm: ImportReviewViewModel, s: ImportReviewState, c: AppColors, modifier: Modifier, onCommitted: (Long) -> Unit) {
  val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
  Column(modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = Space.screenEdge).imePadding(),
      verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
      Spacer(Modifier.height(Space.sm))

      OutlinedTextField(
        value = s.programName, onValueChange = vm::setName,
        label = { Text("Program name") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = Radii.smShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text)
      )

      // The same document, imported before. Offer a choice rather than silently making a copy.
      s.duplicateOf?.let { dup ->
        GlassCard(Modifier.fillMaxWidth()) {
          Column {
            Text("You've imported this file before", style = AppType.title, color = c.accent)
            Spacer(Modifier.height(Space.xs))
            Text("It became “${dup.programName}”. Replace that program, or keep both?",
              style = AppType.body, color = c.textSecondary)
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
              OutlinedButton(onClick = { vm.replaceDuplicate(onCommitted) }) { Text("Replace it") }
              TextButton(onClick = vm::dismissDuplicate) { Text("Keep both") }
            }
          }
        }
      }

      // Confidence, EXPLAINED. A bare percentage is something users learn to ignore.
      GlassCard(Modifier.fillMaxWidth()) {
        Column {
          Text(
            "${s.exerciseCount} exercises · ${s.sessions.count { it.sessionType == "TRAINING" }} sessions",
            style = AppType.title, color = c.text
          )
          Spacer(Modifier.height(2.dp))
          Text(s.confidenceExplanation.ifBlank { "${(s.overallConfidence * 100).toInt()}%" },
            style = AppType.body,
            color = if (s.overallConfidence < 0.75) c.accent else c.textSecondary)

          val remaining = vm.remainingProblems()
          if (remaining > 0) {
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
              Text("$remaining need a look", style = AppType.body, color = c.accent,
                modifier = Modifier.weight(1f))
              TextButton(onClick = { vm.acceptAllAboveThreshold() }) { Text("Accept the rest") }
            }
          }

          s.flags.take(8).forEach { f ->
            Spacer(Modifier.height(3.dp))
            Text("• ${f.message}", style = AppType.label,
              color = if (f.severity == FlagSeverity.INFO) c.textSecondary else c.accent)
            // The EVIDENCE, verbatim. A flag the user cannot check is a flag they cannot act on.
            if (f.sourceLine.isNotBlank()) {
              Text("   ${f.sourceLine.take(120)}", style = AppType.label,
                color = c.textSecondary, maxLines = 2)
            }
          }
          if (s.route.isNotBlank()) {
            Spacer(Modifier.height(Space.xs))
            Text("Read as: ${s.route}", style = AppType.label, color = c.textSecondary)
          }
        }
      }

      if (!s.multiWeek) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Run for", style = AppType.body, color = c.text)
          Spacer(Modifier.width(Space.sm))
          Stepper(s.weeksToRun, { vm.setWeeksToRun(it) }, c)
          Spacer(Modifier.width(Space.sm))
          Text("weeks", style = AppType.body, color = c.text)
        }
      } else {
        Text("Weeks are written out explicitly (${s.sessions.map { it.week }.distinct().size} weeks).",
          style = AppType.label, color = c.textSecondary)
      }

      s.sessions.forEachIndexed { si, sess ->
        SessionBlock(sess, si, vm, c)
      }
      Spacer(Modifier.height(Space.lg))
    }

    // Commit bar
    AppDivider()
    if (s.lastCommittedProgramId != null) {
      // The moment someone realises an import is wrong is the moment they are looking at it, so
      // undo lives here rather than three screens away.
      Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.screenEdge, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Saved.", style = AppType.body, color = c.textSecondary, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.undoLastCommit { } }) { Text("Undo this import") }
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(horizontal = Space.screenEdge, vertical = Space.sm),
      horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
      OutlinedButton(
        onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.commit(activate = false, onDone = onCommitted) },
        enabled = !s.committing, modifier = Modifier.weight(1f).height(50.dp), shape = Radii.cardShape
      ) { Text("Save to library") }
      Button(
        onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.commit(activate = true, onDone = onCommitted) },
        enabled = !s.committing, modifier = Modifier.weight(1f).height(50.dp), shape = Radii.cardShape,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = androidx.compose.ui.graphics.Color.White)
      ) { Text(if (s.committing) "Saving…" else "Start program", fontWeight = FontWeight.Bold) }
    }
  }
}

@Composable
private fun SessionBlock(sess: EditSession, si: Int, vm: ImportReviewViewModel, c: AppColors) {
  GlassCard(Modifier.fillMaxWidth()) {
    Column {
      Text(
        (if (sess.week > 1 || vmMultiWeek(vm)) "Week ${sess.week} · " else "") + sess.label,
        style = AppType.title, color = c.text
      )
      Spacer(Modifier.height(Space.sm))
      sess.exercises.forEachIndexed { ei, ex ->
        ExerciseRow(ex, si, ei, vm, c)
        if (ei < sess.exercises.size - 1) { Spacer(Modifier.height(Space.xs)); AppDivider(); Spacer(Modifier.height(Space.xs)) }
      }
    }
  }
}

@Composable
private fun ExerciseRow(ex: EditExercise, si: Int, ei: Int, vm: ImportReviewViewModel, c: AppColors) {
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (ex.needsReview) {
        Icon(Icons.Default.Warning, "Needs review", tint = c.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
      }
      OutlinedTextField(
        value = ex.name, onValueChange = { vm.editName(si, ei, it) },
        modifier = Modifier.weight(1f), singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = c.text),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text),
        shape = Radii.smShape
      )
      IconButton(onClick = { vm.removeExercise(si, ei) }) {
        Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
      }
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
      // sets stepper
      Text("Sets", style = AppType.label, color = c.textSecondary)
      Stepper(ex.sets, { vm.editSets(si, ei, it) }, c, small = true)
      // reps
      OutlinedTextField(
        value = ex.reps, onValueChange = { vm.editReps(si, ei, it) },
        label = { Text("Reps", fontSize = 11.sp) }, singleLine = true,
        modifier = Modifier.width(110.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = c.text),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text), shape = Radii.smShape
      )
      if (ex.pct.isNotBlank()) Chip(ex.pct, c)
      if (ex.rpe.isNotBlank()) Chip("RPE ${ex.rpe}", c)
    }
    if (ex.notes.isNotBlank()) {
      Spacer(Modifier.height(2.dp))
      Text(ex.notes, style = AppType.label, color = c.textSecondary, maxLines = 3)
    }

    // The SOURCE LINE, always, at every confidence level. This is the difference between a screen
    // that asserts and a screen that shows its working.
    if (ex.sourceLine.isNotBlank()) {
      Spacer(Modifier.height(3.dp))
      Surface(color = c.textSecondary.copy(alpha = 0.06f), shape = RoundedCornerShape(4.dp)) {
        Text(ex.sourceLine, modifier = Modifier.fillMaxWidth().padding(6.dp),
          style = AppType.label, color = c.textSecondary, maxLines = 3)
      }
    }

    // Why this row scored what it did.
    if (ex.confidenceReasons.isNotEmpty()) {
      Spacer(Modifier.height(2.dp))
      Text("${(ex.confidence * 100).toInt()}% — ${ex.confidenceReasons.joinToString(", ")}",
        style = AppType.label, color = c.accent)
    }

    // Ranked alternatives: one tap, not a search. Choosing one applies it EVERYWHERE and learns it.
    if (ex.needsReview && ex.candidates.isNotEmpty()) {
      Spacer(Modifier.height(4.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ex.candidates.take(3).forEach { candidate ->
          SuggestionChip(
            // Choosing a candidate applies it to EVERY occurrence and learns the alias, so the
            // same coach's next sheet needs no correction at all.
            onClick = { vm.applyMappingEverywhere(ex.original, candidate) },
            label = { Text(candidate, style = AppType.label) }
          )
        }
      }
    }
    if (ex.needsReview && !ex.accepted) {
      Spacer(Modifier.height(2.dp))
      TextButton(onClick = { vm.acceptRow(si, ei) }, contentPadding = PaddingValues(0.dp)) {
        Text("Looks right", style = AppType.label, color = c.textSecondary)
      }
    }
  }
}


@Composable
private fun Chip(text: String, c: AppColors) {
  Surface(color = c.accent.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
    Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      style = AppType.label, color = c.accent)
  }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit, c: AppColors, small: Boolean = false) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    val sz = if (small) 30.dp else 36.dp
    OutlinedIconButton(onClick = { onChange(value - 1) }, modifier = Modifier.size(sz)) { Text("−", color = c.text) }
    Text("$value", modifier = Modifier.widthIn(min = 24.dp).padding(horizontal = 4.dp),
      style = AppType.body, color = c.text, fontWeight = FontWeight.Bold)
    OutlinedIconButton(onClick = { onChange(value + 1) }, modifier = Modifier.size(sz)) { Text("+", color = c.text) }
  }
}

private fun vmMultiWeek(vm: ImportReviewViewModel): Boolean = vm.state.value.multiWeek
