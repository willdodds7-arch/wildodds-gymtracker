@file:OptIn(
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
  androidx.compose.material3.ExperimentalMaterial3Api::class
)
package com.wildodds.gymtracker.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.ui.graphics.vector.ImageVector
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.*

@Composable
fun CreateProgramScreen(
  navController: NavController,
  vm: CreateProgramViewModel = viewModel(),
  homeVm: com.wildodds.gymtracker.ui.home.HomeViewModel = viewModel()
) {
  val state  by vm.state.collectAsStateWithLifecycle()
  val hasDraft by vm.hasDraft.collectAsStateWithLifecycle()
  val toast  by vm.toast.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(toast) { toast?.let { snackbarHostState.showSnackbar(it); vm.clearToast() } }

  // ── Excel import (moved here from the Library page) ───────────────────────────
  val importState by homeVm.importState.collectAsStateWithLifecycle()
  val templateState by homeVm.templateDownloadState.collectAsStateWithLifecycle()
  var showExcelOptions by remember { mutableStateOf(false) }
  var importError by remember { mutableStateOf<String?>(null) }

  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
  uri?.let { homeVm.importAnyFile(it) }
  }
  val templateSaver = rememberLauncherForActivityResult(
  ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  ) { uri -> uri?.let { homeVm.saveTemplateToUri(it) } }
  val bulkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
  uri?.let { homeVm.importBulkXlsx(it) }
  }
  val bulkTemplateSaver = rememberLauncherForActivityResult(
  ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  ) { uri -> uri?.let { homeVm.saveBulkTemplateToUri(it) } }

  LaunchedEffect(importState) {
  when (val s = importState) {
  is com.wildodds.gymtracker.ui.home.ImportState.Success -> {
  snackbarHostState.showSnackbar("Program loaded — ${s.sessionCount} sessions ready")
  homeVm.clearImportState(); navController.popBackStack()
  }
  is com.wildodds.gymtracker.ui.home.ImportState.BulkSuccess -> {
  snackbarHostState.showSnackbar("Added ${s.programCount} program${if (s.programCount != 1) "s" else ""} to your library")
  homeVm.clearImportState(); navController.popBackStack()
  }
  is com.wildodds.gymtracker.ui.home.ImportState.Error -> { importError = s.message; homeVm.clearImportState() }
  else -> {}
  }
  }
  LaunchedEffect(templateState) {
  when (val s = templateState) {
  is com.wildodds.gymtracker.ui.home.HomeViewModel.TemplateDownloadState.Success -> {
  snackbarHostState.showSnackbar("Template saved"); homeVm.clearTemplateDownloadState()
  }
  is com.wildodds.gymtracker.ui.home.HomeViewModel.TemplateDownloadState.Error -> {
  importError = s.message; homeVm.clearTemplateDownloadState()
  }
  else -> {}
  }
  }

  importError?.let { msg ->
  AlertDialog(onDismissRequest = { importError = null },
  title = { Text("Import Error") }, text = { Text(msg) },
  confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } })
  }

  val previewState = importState as? com.wildodds.gymtracker.ui.home.ImportState.Preview
  if (previewState != null) {
  com.wildodds.gymtracker.ui.home.SpreadsheetPreviewSheet(
  smart  = previewState.smart,
  initName  = previewState.editName,
  initWeeks = previewState.editWeeks,
  onImport  = { name, weeks -> homeVm.confirmSmartImport(name, weeks, previewState.smart) },
  onDismiss = { homeVm.clearImportState() }
  )
  }
  val aiReviewState = importState as? com.wildodds.gymtracker.ui.home.ImportState.AiReview
  if (aiReviewState != null) {
  com.wildodds.gymtracker.ui.home.AiProgramReviewSheet(
  initial  = aiReviewState.program,
  onConfirm = { prog, name, weeks, activate -> homeVm.confirmAiImport(prog, name, weeks, activate) },
  onDismiss = { homeVm.clearImportState() }
  )
  }
  if (showExcelOptions) {
  com.wildodds.gymtracker.ui.home.ExcelOptionsSheet(
  onUpload  = { showExcelOptions = false; filePicker.launch("*/*") },
  onDownloadTemplate = { showExcelOptions = false; templateSaver.launch("WildOdds_GymTracker_Template.xlsx") },
  onDismiss = { showExcelOptions = false },
  onBulkUpload = { showExcelOptions = false; bulkPicker.launch("*/*") },
  onDownloadBulkTemplate = { showExcelOptions = false; bulkTemplateSaver.launch("WildOdds_BulkTemplate.xlsx") }
  )
  }

  // Offer to resume draft on first compose
  var draftDialogDismissed by remember { mutableStateOf(false) }
  if (hasDraft && !draftDialogDismissed) {
  AlertDialog(
  onDismissRequest = { draftDialogDismissed = true },
  title  = { Text("Resume Draft?") },
  text  = { Text("You have an unsaved program in progress. Would you like to continue where you left off?") },
  confirmButton = {
  Button(onClick  = { vm.restoreDraft(); draftDialogDismissed = true },
  colors = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)) { Text("Resume") }
  },
  dismissButton = {
  TextButton(onClick = { vm.clearDraft(); draftDialogDismissed = true }) { Text("Start Fresh") }
  }
  )
  }

  state.saveError?.let { error ->
  AlertDialog(onDismissRequest = vm::clearSaveError,
  title = { Text("Error") }, text = { Text(error) },
  confirmButton = { TextButton(onClick = vm::clearSaveError) { Text("OK") } })
  }

  Box(Modifier.fillMaxSize()) {
  AnimatedContent(
  targetState = state.step,
  transitionSpec = {
  if (targetState > initialState)
  slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
  else
  slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
  },
  label = "step"
  ) { step ->
  when (step) {
  1 -> SetupStep(state = state, vm = vm, onBack = { navController.popBackStack() },
  onImportExcel = { if (importState !is com.wildodds.gymtracker.ui.home.ImportState.Loading) showExcelOptions = true },
  importing = importState is com.wildodds.gymtracker.ui.home.ImportState.Loading)
  2 -> BuilderStep(state = state, vm = vm, onSaved = { navController.popBackStack() })
  }
  }
  SnackbarHost(snackbarHostState,
  modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
  }
}

// ─── Step 1 : Setup ───────────────────────────────────────────────────────────

@Composable
private fun SetupStep(
  state: CreateProgramState,
  vm: CreateProgramViewModel,
  onBack: () -> Unit,
  onImportExcel: () -> Unit = {},
  importing: Boolean = false
) {
  val focusManager = LocalFocusManager.current

  Column(
  modifier = Modifier
  .fillMaxSize()
  .background(MaterialTheme.colorScheme.background)
  .imePadding()
  .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
  focusManager.clearFocus()
  }
  ) {
  // Top bar
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
  verticalAlignment = Alignment.CenterVertically) {
  IconButton(onClick = onBack) {
  Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
  }
  Text("Create Program", fontWeight = FontWeight.Bold, fontSize = 20.sp,
  color = MaterialTheme.colorScheme.onBackground)
  }

  Column(
  modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
  ) {
  // ── Import from Excel (alternative to building by hand) ───────────
  GlassCard(modifier = Modifier.fillMaxWidth().clickable(enabled = !importing) { onImportExcel() }) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  if (importing) {
  CircularProgressIndicator(modifier = Modifier.size(32.dp), color = LocalAccentColor.current, strokeWidth = 3.dp)
  } else {
  Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
  .background(LocalAccentColor.current.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
  Icon(Icons.Default.TableChart, null, tint = LocalAccentColor.current, modifier = Modifier.size(20.dp))
  }
  }
  Spacer(Modifier.width(12.dp))
  Column(modifier = Modifier.weight(1f)) {
  Text("Import from Excel", fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
  Text("Upload an .xlsx or download a template",
  color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
  }
  Icon(Icons.Default.ChevronRight, null, tint = LocalAccentColor.current.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
  }
  }

  Spacer(Modifier.height(20.dp))

  // ── Program type chooser ──────────────────────────────────────────
  Text("Program Type", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))
  ProgramTypeCard(
  selected  = state.programType == ProgramType.FULL,
  icon  = Icons.Default.CalendarMonth,
  title  = "Plan Full Week",
  subtitle  = "Design every exercise before you start",
  onClick  = { vm.setProgramType(ProgramType.FULL) }
  )
  Spacer(Modifier.height(8.dp))
  ProgramTypeCard(
  selected  = state.programType == ProgramType.FLEXIBLE,
  icon  = Icons.Default.AddCircle,
  title  = "Build As You Go",
  subtitle  = "Add exercises inside each session - ideal for intuitive training",
  onClick  = { vm.setProgramType(ProgramType.FLEXIBLE) }
  )

  Spacer(Modifier.height(24.dp))

  // ── Program name ──────────────────────────────────────────────────
  Text("Program Name", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(6.dp))
  OutlinedTextField(
  value = state.programName, onValueChange = vm::setProgramName,
  modifier = Modifier.fillMaxWidth(),
  placeholder = { Text("e.g. My Push Pull Legs") },
  singleLine = true,
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
  shape = RoundedCornerShape(12.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )

  Spacer(Modifier.height(24.dp))

  // ── Coach & tags (browse metadata) ────────────────────────────────
  Text("Coach & details", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(6.dp))
  OutlinedTextField(
  value = state.coach, onValueChange = vm::setCoach,
  modifier = Modifier.fillMaxWidth(), placeholder = { Text("Coach name") }, singleLine = true,
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
  shape = RoundedCornerShape(12.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )
  Spacer(Modifier.height(8.dp))
  OutlinedTextField(
  value = state.coachBio, onValueChange = vm::setCoachBio,
  modifier = Modifier.fillMaxWidth(), placeholder = { Text("About the coach (optional)") },
  minLines = 2, maxLines = 4,
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
  shape = RoundedCornerShape(12.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )
  Spacer(Modifier.height(8.dp))
  OutlinedTextField(
  value = state.description, onValueChange = vm::setDescription,
  modifier = Modifier.fillMaxWidth(), placeholder = { Text("Program description") },
  minLines = 2, maxLines = 4,
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
  shape = RoundedCornerShape(12.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )

  Spacer(Modifier.height(24.dp))

  // ── Days per week ─────────────────────────────────────────────────
  Text("Days Per Week", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  (1..7).forEach { day ->
  val selected = state.daysPerWeek == day
  Surface(onClick = { vm.setDaysPerWeek(day) }, modifier = Modifier.size(44.dp),
  shape = CircleShape,
  color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.surfaceVariant,
  border = if (!selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null) {
  Box(contentAlignment = Alignment.Center) {
  Text("$day", fontWeight = FontWeight.Bold,
  color = if (selected) Color.White else MaterialTheme.colorScheme.onBackground)
  }
  }
  }
  }

  Spacer(Modifier.height(24.dp))

  // ── Number of weeks ───────────────────────────────────────────────
  Text("Number of Weeks", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
  (1..16).forEach { week ->
  val selected = state.numberOfWeeks == week
  Surface(onClick = { vm.setNumberOfWeeks(week) }, modifier = Modifier.size(44.dp),
  shape = CircleShape,
  color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.surfaceVariant,
  border = if (!selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null) {
  Box(contentAlignment = Alignment.Center) {
  Text("$week", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
  color = if (selected) Color.White else MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
  }
  }
  }
  }

  Spacer(Modifier.height(24.dp))

  // ── Summary chip ──────────────────────────────────────────────────
  if (state.programName.isNotBlank()) {
  Surface(color = LocalAccentColor.current.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp),
  modifier = Modifier.fillMaxWidth()) {
  Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
  verticalAlignment = Alignment.CenterVertically) {
  Icon(Icons.Default.FitnessCenter, null, tint = LocalAccentColor.current, modifier = Modifier.size(20.dp))
  Text("\"${state.programName}\"  ·  ${state.daysPerWeek} days/week  ·  ${state.numberOfWeeks} weeks",
  color = LocalAccentColor.current, fontWeight = FontWeight.Medium, fontSize = 13.sp)
  }
  }
  Spacer(Modifier.height(24.dp))
  }

  // ── Tracking options ──────────────────────────────────────────────
  Text("Tracking Options", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(8.dp))
  GlassCard(modifier = Modifier.fillMaxWidth()) {
  Column {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
  verticalAlignment = Alignment.CenterVertically) {
  Column(modifier = Modifier.weight(1f)) {
  Text("Track RPE", fontWeight = FontWeight.Medium,
  color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
  Text("Rate of perceived exertion per set (1-10)",
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Switch(checked = state.trackRpe, onCheckedChange = vm::setTrackRpe,
  colors = SwitchDefaults.colors(checkedThumbColor = Color.White,
  checkedTrackColor = LocalAccentColor.current))
  }
  }
  }
  Text("%1RM logging can be turned on per-session from the session menu (⋮).",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.padding(top = 6.dp))

  Spacer(Modifier.height(12.dp))
  // Save draft button
  OutlinedButton(
  onClick  = { vm.saveDraft() },
  modifier  = Modifier.fillMaxWidth(),
  shape  = RoundedCornerShape(12.dp),
  colors  = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
  border  = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
  ) {
  Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(6.dp))
  Text("Save Draft", fontSize = 14.sp)
  }

  Spacer(Modifier.height(100.dp))
  }

  // Bottom action buttons
  Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
  if (state.isSaving) {
  Box(modifier = Modifier.fillMaxWidth().padding(20.dp).height(52.dp), contentAlignment = Alignment.Center) {
  CircularProgressIndicator(color = LocalAccentColor.current)
  }
  } else if (state.programType == ProgramType.FLEXIBLE) {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
  horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  OutlinedButton(
  onClick  = { vm.addFlexibleToLibrary(onBack) },
  enabled  = state.programName.isNotBlank(),
  modifier = Modifier.weight(1f).height(52.dp),
  shape  = RoundedCornerShape(14.dp),
  colors  = ButtonDefaults.outlinedButtonColors(contentColor = LocalAccentColor.current),
  border  = androidx.compose.foundation.BorderStroke(1.5.dp, LocalAccentColor.current)
  ) {
  Icon(Icons.Default.LibraryAdd, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(6.dp))
  Text("Add to Library", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
  }
  Button(
  onClick  = { vm.startFlexibleNow(onBack) },
  enabled  = state.programName.isNotBlank(),
  modifier = Modifier.weight(1f).height(52.dp),
  shape  = RoundedCornerShape(14.dp),
  colors  = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current,
  disabledContainerColor = MaterialTheme.colorScheme.outline)
  ) {
  Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(6.dp))
  Text("Start Now", fontWeight = FontWeight.Bold, fontSize = 14.sp)
  }
  }
  } else {
  Button(
  onClick  = { vm.proceedToBuilder() },
  enabled  = state.programName.isNotBlank(),
  modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).height(52.dp),
  shape  = RoundedCornerShape(14.dp),
  colors  = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current,
  disabledContainerColor = MaterialTheme.colorScheme.outline)
  ) {
  Text("Build Exercises →", fontWeight = FontWeight.Bold, fontSize = 16.sp)
  }
  }
  }
  }
}

// ─── Step 2 : Builder ─────────────────────────────────────────────────────────

@Composable
private fun BuilderStep(state: CreateProgramState, vm: CreateProgramViewModel, onSaved: () -> Unit) {
  var showConfirmDialog by remember { mutableStateOf(false) }
  var confirmActivate  by remember { mutableStateOf(true) }

  // Confirmation dialog - shown before saving
  if (showConfirmDialog) {
  val displayName  = state.programName.ifBlank { "My Program" }
  val totalExercises = state.days.sumOf { it.exercises.count { ex -> ex.name.isNotBlank() } }
  AlertDialog(
  onDismissRequest = { showConfirmDialog = false },
  title = { Text(if (confirmActivate) "Start Program?" else "Add to Library?") },
  text  = {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
  Text("\"$displayName\"", fontWeight = FontWeight.Bold, fontSize = 16.sp)
  Text("${state.daysPerWeek} days/week  ·  ${state.numberOfWeeks} weeks")
  if (totalExercises > 0)
  Text("$totalExercises exercise${if (totalExercises != 1) "s" else ""} defined")
  else
  Text("No exercises yet - you can add them later in each session.",
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
  if (confirmActivate)
  Text("This will become your active program.",
  color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
  }
  },
  confirmButton = {
  Button(
  onClick = {
  showConfirmDialog = false
  if (confirmActivate) vm.startNow(onSaved) else vm.addToLibrary(onSaved)
  },
  colors = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)
  ) { Text(if (confirmActivate) "Start Now" else "Add to Library") }
  },
  dismissButton = {
  TextButton(onClick = { showConfirmDialog = false }) { Text("Keep Editing") }
  }
  )
  }

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
  verticalAlignment = Alignment.CenterVertically) {
  IconButton(onClick = vm::goBackToSetup) {
  Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
  }
  Column(modifier = Modifier.weight(1f)) {
  Text(state.programName.ifBlank { "My Program" }, fontWeight = FontWeight.Bold, fontSize = 18.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Text("Design week 1 - repeated for ${state.numberOfWeeks} weeks",
  color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  }
  }

  LazyColumn(
  contentPadding = PaddingValues(bottom = 100.dp),
  verticalArrangement = Arrangement.spacedBy(12.dp),
  modifier = Modifier.weight(1f)
  ) {
  itemsIndexed(state.days, key = { _, day -> day.dayNumber }) { dayIndex, day ->
  DayCard(day = day, dayIndex = dayIndex, vm = vm)
  }
  }

  Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
  if (state.isSaving) {
  Box(modifier = Modifier.fillMaxWidth().padding(20.dp).height(52.dp), contentAlignment = Alignment.Center) {
  CircularProgressIndicator(color = LocalAccentColor.current)
  }
  } else {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
  horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  OutlinedButton(
  onClick  = { confirmActivate = false; showConfirmDialog = true },
  modifier = Modifier.weight(1f).height(52.dp),
  shape  = RoundedCornerShape(14.dp),
  colors  = ButtonDefaults.outlinedButtonColors(contentColor = LocalAccentColor.current),
  border  = androidx.compose.foundation.BorderStroke(1.5.dp, LocalAccentColor.current)
  ) {
  Icon(Icons.Default.LibraryAdd, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(6.dp))
  Text("Add to Library", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
  }
  Button(
  onClick  = { confirmActivate = true; showConfirmDialog = true },
  modifier = Modifier.weight(1f).height(52.dp),
  shape  = RoundedCornerShape(14.dp),
  colors  = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current)
  ) {
  Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(6.dp))
  Text("Start Now", fontWeight = FontWeight.Bold, fontSize = 14.sp)
  }
  }
  }
  }
  }
}

// ─── Day card ─────────────────────────────────────────────────────────────────

@Composable
private fun DayCard(day: DayTemplate, dayIndex: Int, vm: CreateProgramViewModel) {
  var isEditingDayName by remember { mutableStateOf(false) }
  var dayNameField by remember(day.dayNumber) { mutableStateOf(day.name) }
  val nameFocus = remember { FocusRequester() }

  GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
  Column {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
  Surface(color = LocalAccentColor.current, shape = CircleShape, modifier = Modifier.size(32.dp)) {
  Box(contentAlignment = Alignment.Center) {
  Text("${day.dayNumber}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
  }
  }
  Spacer(Modifier.width(10.dp))
  if (isEditingDayName) {
  LaunchedEffect(Unit) { nameFocus.requestFocus() }
  OutlinedTextField(value = dayNameField, onValueChange = { dayNameField = it },
  modifier = Modifier.weight(1f).focusRequester(nameFocus), singleLine = true,
  textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground),
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current))
  IconButton(onClick = { vm.updateDayName(dayIndex, dayNameField); isEditingDayName = false }) {
  Icon(Icons.Default.Check, "Save", tint = LocalAccentColor.current)
  }
  } else {
  Text(day.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
  color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
  IconButton(onClick = { dayNameField = day.name; isEditingDayName = true }) {
  Icon(Icons.Default.Edit, "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.size(18.dp))
  }
  }
  }

  if (day.exercises.isNotEmpty()) {
  Spacer(Modifier.height(12.dp))
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
  }

  day.exercises.forEachIndexed { exIdx, exercise ->
  Spacer(Modifier.height(12.dp))
  ExerciseBuilderRow(
  exercise = exercise, number = exIdx + 1, dayIndex = dayIndex, vm = vm,
  isFirst  = exIdx == 0, isLast = exIdx == day.exercises.size - 1
  )
  if (exIdx < day.exercises.size - 1) {
  Spacer(Modifier.height(8.dp))
  HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp),
  color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
  }
  }

  Spacer(Modifier.height(12.dp))
  TextButton(onClick = { vm.addExercise(dayIndex) }, modifier = Modifier.fillMaxWidth()) {
  Icon(Icons.Default.Add, null, tint = LocalAccentColor.current, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(4.dp))
  Text("Add Exercise", color = LocalAccentColor.current, fontWeight = FontWeight.Medium)
  }

  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
  SessionLibraryRow(dayIndex = dayIndex, vm = vm)
  }
  }
}

// ─── Session library: load / save a day ────────────────────────────────────────

@Composable
private fun SessionLibraryRow(dayIndex: Int, vm: CreateProgramViewModel) {
  val accent = LocalAccentColor.current
  val sessions by vm.librarySessions.collectAsStateWithLifecycle()
  var menuOpen by remember { mutableStateOf(false) }

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
  Box(modifier = Modifier.weight(1f)) {
  TextButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
  Icon(Icons.Default.Bookmarks, null, tint = accent, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(4.dp))
  Text("Load from Library", color = accent, fontSize = 12.sp)
  }
  DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
  if (sessions.isEmpty()) {
  DropdownMenuItem(text = { Text("No saved sessions yet", fontSize = 13.sp) },
  onClick = { menuOpen = false }, enabled = false)
  } else {
  sessions.forEach { s ->
  DropdownMenuItem(
  text = { Text(s.name) },
  onClick = { vm.loadSessionIntoDay(dayIndex, s.id); menuOpen = false }
  )
  }
  }
  }
  }
  TextButton(onClick = { vm.saveDayAsSession(dayIndex) }, modifier = Modifier.weight(1f)) {
  Icon(Icons.Default.BookmarkAdd, null, tint = accent, modifier = Modifier.size(16.dp))
  Spacer(Modifier.width(4.dp))
  Text("Save as Session", color = accent, fontSize = 12.sp)
  }
  }
}

// ─── Exercise builder row ─────────────────────────────────────────────────────

@Composable
private fun ExerciseBuilderRow(
  exercise: ExerciseTemplate, number: Int, dayIndex: Int, vm: CreateProgramViewModel,
  isFirst: Boolean = true, isLast: Boolean = true
) {
  var showNotes by remember(exercise.id) { mutableStateOf(exercise.notes.isNotBlank()) }
  val accent = LocalAccentColor.current

  Column(modifier = Modifier.fillMaxWidth()) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Text("$number.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
  modifier = Modifier.width(22.dp))
  OutlinedTextField(value = exercise.name, onValueChange = { vm.updateExerciseName(dayIndex, exercise.id, it) },
  modifier = Modifier.weight(1f),
  placeholder = { Text("Exercise name", fontSize = 13.sp) },
  singleLine = true,
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
  textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current,
  unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
  shape = RoundedCornerShape(8.dp))
  Spacer(Modifier.width(4.dp))
  // Up / down reorder buttons
  Column(modifier = Modifier.size(28.dp, 56.dp)) {
  IconButton(
  onClick  = { vm.moveExercise(dayIndex, exercise.id, -1) },
  enabled  = !isFirst,
  modifier = Modifier.size(28.dp)
  ) {
  Icon(Icons.Default.KeyboardArrowUp, "Move up",
  tint  = if (!isFirst) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.25f),
  modifier = Modifier.size(16.dp))
  }
  IconButton(
  onClick  = { vm.moveExercise(dayIndex, exercise.id, +1) },
  enabled  = !isLast,
  modifier = Modifier.size(28.dp)
  ) {
  Icon(Icons.Default.KeyboardArrowDown, "Move down",
  tint  = if (!isLast) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.25f),
  modifier = Modifier.size(16.dp))
  }
  }
  IconButton(onClick = { vm.deleteExercise(dayIndex, exercise.id) }, modifier = Modifier.size(36.dp)) {
  Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
  modifier = Modifier.size(18.dp))
  }
  }

  Spacer(Modifier.height(6.dp))

  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 22.dp)) {
  Text("Sets", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
  Spacer(Modifier.width(6.dp))
  Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  IconButton(onClick = { vm.updateExerciseSets(dayIndex, exercise.id, -1) }, modifier = Modifier.size(32.dp)) {
  Icon(Icons.Default.Remove, "−", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(16.dp))
  }
  Text("${exercise.sets}", fontWeight = FontWeight.Bold, fontSize = 15.sp,
  color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.widthIn(min = 24.dp),
  textAlign = TextAlign.Center)
  IconButton(onClick = { vm.updateExerciseSets(dayIndex, exercise.id, +1) }, modifier = Modifier.size(32.dp)) {
  Icon(Icons.Default.Add, "+", tint = LocalAccentColor.current, modifier = Modifier.size(16.dp))
  }
  }
  }
  Spacer(Modifier.width(12.dp))
  Text("Reps", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
  Spacer(Modifier.width(6.dp))
  OutlinedTextField(value = exercise.repsTarget, onValueChange = { vm.updateExerciseReps(dayIndex, exercise.id, it) },
  modifier = Modifier.width(80.dp), singleLine = true,
  placeholder = { Text("8-12", fontSize = 12.sp) },
  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
  textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current,
  unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
  shape = RoundedCornerShape(8.dp))
  Spacer(Modifier.weight(1f))
  TextButton(onClick = { showNotes = !showNotes },
  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
  Text(if (showNotes) "− Notes" else "+ Notes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
  }
  }

  AnimatedVisibility(visible = showNotes) {
  Spacer(Modifier.height(6.dp))
  OutlinedTextField(value = exercise.notes, onValueChange = { vm.updateExerciseNotes(dayIndex, exercise.id, it) },
  modifier = Modifier.fillMaxWidth().padding(start = 22.dp),
  placeholder = { Text("Notes (optional)", fontSize = 12.sp) },
  singleLine = true,
  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
  textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current,
  unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
  shape = RoundedCornerShape(8.dp))
  }
  }
}

// ─── Program type chooser card ─────────────────────────────────────────────────

@Composable
private fun ProgramTypeCard(
  selected: Boolean,
  icon:  ImageVector,
  title:  String,
  subtitle: String,
  onClick:  () -> Unit
) {
  val borderColor = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
  val bgColor  = if (selected) LocalAccentColor.current.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

  Surface(
  onClick  = onClick,
  modifier  = Modifier.fillMaxWidth(),
  shape  = RoundedCornerShape(14.dp),
  color  = bgColor,
  border  = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
  ) {
  Row(
  modifier  = Modifier.padding(14.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Surface(
  color  = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
  shape  = CircleShape,
  modifier = Modifier.size(40.dp)
  ) {
  Box(contentAlignment = Alignment.Center) {
  Icon(icon, null,
  tint  = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.size(20.dp))
  }
  }
  Spacer(Modifier.width(14.dp))
  Column(modifier = Modifier.weight(1f)) {
  Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
  color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onBackground)
  Text(subtitle, style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (selected) {
  Icon(Icons.Default.CheckCircle, null, tint = LocalAccentColor.current, modifier = Modifier.size(20.dp))
  }
  }
  }
}
