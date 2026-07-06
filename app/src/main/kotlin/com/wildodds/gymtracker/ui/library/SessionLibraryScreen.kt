@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.db.entity.SessionTemplate
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

@Composable
fun SessionLibraryScreen(navController: NavController, vm: SessionLibraryViewModel = viewModel()) {
  val sessions by vm.sessions.collectAsStateWithLifecycle()
  val counts by vm.exerciseCounts.collectAsStateWithLifecycle()
  val editor by vm.editor.collectAsStateWithLifecycle()
  val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
  val accent = LocalAccentColor.current
  val snackbar = remember { SnackbarHostState() }
  var deleteTarget by remember { mutableStateOf<SessionTemplate?>(null) }

  LaunchedEffect(syncMessage) {
  syncMessage?.let { snackbar.showSnackbar(it); vm.clearSyncMessage() }
  }

  editor?.let { state ->
  SessionEditorSheet(
  state = state, vm = vm,
  onDismiss = { vm.cancelEditor() }
  )
  }

  deleteTarget?.let { t ->
  AlertDialog(
  onDismissRequest = { deleteTarget = null },
  title = { Text("Delete session?") },
  text = { Text("\"${t.name}\" will be removed from your session library.") },
  confirmButton = {
  TextButton(onClick = { vm.deleteSession(t); deleteTarget = null },
  colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
  },
  dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
  )
  }

  Scaffold(
  snackbarHost = { SnackbarHost(snackbar) },
  containerColor = MaterialTheme.colorScheme.background,
  floatingActionButton = {
  ExtendedFloatingActionButton(
  onClick = { vm.startNewSession() },
  containerColor = accent, contentColor = Color.White,
  icon = { Icon(Icons.Default.Add, null) },
  text = { Text("New Session") }
  )
  }
  ) { padding ->
  LazyColumn(
  modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
  contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
  verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
  item {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  IconButton(onClick = { navController.popBackStack() }) {
  Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
  }
  Column(Modifier.weight(1f)) {
  Text("Session Library", style = MaterialTheme.typography.headlineMedium,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Text("${sessions.size} reusable session${if (sessions.size != 1) "s" else ""}",
  style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  Spacer(Modifier.height(4.dp))
  OutlinedButton(
  onClick = { vm.syncFromPrograms() },
  shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
  border = BorderStroke(1.dp, accent)
  ) {
  Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(6.dp))
  Text("Import days from my programs")
  }
  }

  if (sessions.isEmpty()) {
  item {
  Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(Icons.Default.FitnessCenter, null, tint = accent.copy(0.4f), modifier = Modifier.size(48.dp))
  Spacer(Modifier.height(12.dp))
  Text("No sessions yet", fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onBackground)
  Text("Build an \"Arm Day\" or import days from your programs.",
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
  textAlign = TextAlign.Center)
  }
  }
  }
  }

  items(sessions, key = { it.id }) { session ->
  SessionLibraryCard(
  session = session,
  exerciseCount = counts[session.id] ?: 0,
  onEdit = { vm.startEditSession(session) },
  onDelete = { deleteTarget = session }
  )
  }
  }
  }
}

@Composable
private fun SessionLibraryCard(
  session: SessionTemplate, exerciseCount: Int,
  onEdit: () -> Unit, onDelete: () -> Unit
) {
  val accent = LocalAccentColor.current
  GlassCard(modifier = Modifier.fillMaxWidth()) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(0.15f)),
  contentAlignment = Alignment.Center) {
  Icon(Icons.Default.FitnessCenter, null, tint = accent, modifier = Modifier.size(20.dp))
  }
  Spacer(Modifier.width(12.dp))
  Column(Modifier.weight(1f)) {
  Text(session.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
  color = MaterialTheme.colorScheme.onBackground)
  val sub = buildString {
  append("$exerciseCount exercise${if (exerciseCount != 1) "s" else ""}")
  if (session.muscleGroups.isNotBlank()) append(" · ${session.muscleGroups}")
  }
  Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
  if (session.source.isNotBlank() && session.source != "Custom") {
  Text("from ${session.source}", fontSize = 11.sp, color = accent.copy(0.8f), maxLines = 1)
  }
  }
  IconButton(onClick = onEdit) {
  Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
  }
  IconButton(onClick = onDelete) {
  Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error.copy(0.7f), modifier = Modifier.size(18.dp))
  }
  }
  }
}

@Composable
private fun SessionEditorSheet(
  state: SessionEditorState, vm: SessionLibraryViewModel, onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val accent = LocalAccentColor.current

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
  containerColor = MaterialTheme.colorScheme.surface) {
  Column(modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
  Text(if (state.templateId == null) "New Session" else "Edit Session",
  fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(12.dp))
  OutlinedTextField(
  value = state.name, onValueChange = vm::setEditorName,
  modifier = Modifier.fillMaxWidth(), singleLine = true,
  placeholder = { Text("Session name, e.g. Arm Day") },
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
  shape = RoundedCornerShape(12.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  Spacer(Modifier.height(14.dp))

  Column(verticalArrangement = Arrangement.spacedBy(10.dp),
  modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
  .verticalScroll(rememberScrollState())) {
  state.exercises.forEachIndexed { idx, ex ->
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Text("${idx + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant,
  fontSize = 13.sp, modifier = Modifier.width(22.dp))
  OutlinedTextField(
  value = ex.name,
  onValueChange = { v -> vm.updateExercise(ex.key) { it.copy(name = v) } },
  modifier = Modifier.weight(1f), singleLine = true,
  placeholder = { Text("Exercise", fontSize = 13.sp) },
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
  shape = RoundedCornerShape(8.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  Spacer(Modifier.width(6.dp))
  // sets stepper
  Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  IconButton(onClick = { vm.updateExercise(ex.key) { it.copy(sets = (it.sets - 1).coerceAtLeast(1)) } },
  modifier = Modifier.size(30.dp)) {
  Icon(Icons.Default.Remove, "-", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onBackground)
  }
  Text("${ex.sets}", fontWeight = FontWeight.Bold, fontSize = 14.sp,
  modifier = Modifier.widthIn(min = 18.dp), textAlign = TextAlign.Center,
  color = MaterialTheme.colorScheme.onBackground)
  IconButton(onClick = { vm.updateExercise(ex.key) { it.copy(sets = (it.sets + 1).coerceAtMost(20)) } },
  modifier = Modifier.size(30.dp)) {
  Icon(Icons.Default.Add, "+", modifier = Modifier.size(15.dp), tint = accent)
  }
  }
  }
  Spacer(Modifier.width(6.dp))
  OutlinedTextField(
  value = ex.repsTarget,
  onValueChange = { v -> vm.updateExercise(ex.key) { it.copy(repsTarget = v) } },
  modifier = Modifier.width(72.dp), singleLine = true,
  placeholder = { Text("8-12", fontSize = 12.sp) },
  shape = RoundedCornerShape(8.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
  )
  IconButton(onClick = { vm.removeExerciseRow(ex.key) }, modifier = Modifier.size(32.dp)) {
  Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.error.copy(0.7f), modifier = Modifier.size(16.dp))
  }
  }
  }
  }

  Spacer(Modifier.height(8.dp))
  TextButton(onClick = { vm.addExerciseRow() }) {
  Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(4.dp))
  Text("Add Exercise", color = accent)
  }

  Spacer(Modifier.height(12.dp))
  Button(
  onClick = { vm.saveEditor(onSaved = {}) },
  modifier = Modifier.fillMaxWidth().height(50.dp),
  shape = RoundedCornerShape(14.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent),
  enabled = state.name.isNotBlank()
  ) {
  Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(6.dp))
  Text("Save to Library", fontWeight = FontWeight.Bold)
  }
  }
  }
}
