package com.wildodds.gymtracker.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.BuildConfig
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.retention.NudgeSettingsStore
import com.wildodds.gymtracker.data.retention.ReengagementNotifier
import com.wildodds.gymtracker.data.retention.ReengagementScheduler
import com.wildodds.gymtracker.data.sync.BackupManager
import com.wildodds.gymtracker.data.sync.TrainingExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.colorFromHsl
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Settings is rendered entirely from [SettingsRegistry]: a pinned search field on top, then
 * quiet, collapsible group headers with one row per registered entry. Typing filters every
 * group at once. Adding a future feature means registering one [SettingsEntry] — this screen
 * does not need to change.
 */
@Composable
fun SettingsScreen(
  navController: NavController? = null,
  vm: SettingsViewModel = viewModel()
) {
  val context       = LocalContext.current
  val accent        = LocalAccentColor.current
  val accentHue     by vm.accentHue.collectAsStateWithLifecycle()
  val accentSat     by vm.accentSaturation.collectAsStateWithLifecycle()
  val timerSound    by vm.timerSound.collectAsStateWithLifecycle()
  val claudeApiKey  by vm.claudeApiKey.collectAsStateWithLifecycle()
  val toggleStates  by vm.toggleStates.collectAsStateWithLifecycle()

  var showExportSheet by remember { mutableStateOf(false) }
  if (showExportSheet) {
  ExportSheet(onDismiss = { showExportSheet = false })
  }

  // Re-engagement reminders (Phase 7A): opt-in. Enabling requests POST_NOTIFICATIONS (Android 13+)
  // and schedules the periodic check; disabling cancels it immediately (instant opt-out).
  var showReminderSheet by remember { mutableStateOf(false) }
  if (showReminderSheet) { ReminderSettingsSheet(onDismiss = { showReminderSheet = false }) }
  val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  val reengagementOn = toggleStates[SettingsRegistry.REENGAGEMENT] ?: false
  LaunchedEffect(reengagementOn) {
  if (reengagementOn) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReengagementNotifier.hasPermission(context)) {
  notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
  }
  ReengagementScheduler.schedule(context)
  } else {
  ReengagementScheduler.cancel(context)
  }
  }

  // Wearable (Health Connect) connect row state. The permission request uses Health Connect's own
  // contract; on return — granted or not — we re-read the status. We also re-check on resume,
  // since the user can grant/revoke inside the Health Connect app while we're backgrounded.
  val wearableStatus by vm.wearableStatus.collectAsStateWithLifecycle()
  val requestWearablePerms = rememberLauncherForActivityResult(
  PermissionController.createRequestPermissionResultContract()
  ) { vm.refreshWearableStatus() }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
  val observer = LifecycleEventObserver { _, event ->
  if (event == Lifecycle.Event.ON_RESUME) vm.refreshWearableStatus()
  }
  lifecycleOwner.lifecycle.addObserver(observer)
  onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  var query           by rememberSaveable { mutableStateOf("") }
  val expandedState   = remember { mutableStateMapOf<String, Boolean>() }
  var showClearDialog by remember { mutableStateOf(false) }

  // Account (Phase 2) + sync (Phase 3)
  val analyticsConsent by vm.analyticsConsent.collectAsStateWithLifecycle()
  val syncState by vm.syncState.collectAsStateWithLifecycle()
  val signedInEmail by vm.signedInEmail.collectAsStateWithLifecycle()
  // Refresh when returning here (e.g. straight after signing in via the account_auth flow).
  LaunchedEffect(Unit) { vm.refreshAccountState() }
  var showAccountInfo by remember { mutableStateOf(false) }
  if (showAccountInfo) {
  AlertDialog(
  onDismissRequest = { showAccountInfo = false },
  title = { Text("Account") },
  text = { Text("Signed in as ${signedInEmail ?: "…"}.\n\nYour training data syncs to this account. Sign out or delete the account from the rows below; export a copy anytime.") },
  confirmButton = { TextButton(onClick = { showAccountInfo = false }) { Text("OK") } }
  )
  }

  // Account lifecycle (Phase 5): export via SAF create-document.
  val accountVm: com.wildodds.gymtracker.ui.account.AccountViewModel = viewModel()
  val exportState by accountVm.export.collectAsStateWithLifecycle()
  val exportLauncher = rememberLauncherForActivityResult(
  ActivityResultContracts.CreateDocument("application/zip")
  ) { uri -> if (uri != null) accountVm.exportTo(uri) }
  LaunchedEffect(exportState.done, exportState.error) {
  if (exportState.done) { Toast.makeText(context, "Data exported", Toast.LENGTH_SHORT).show(); accountVm.resetExport() }
  exportState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); accountVm.resetExport() }
  }
  var showSignOutDialog by remember { mutableStateOf(false) }
  if (showSignOutDialog) {
  AlertDialog(
  onDismissRequest = { showSignOutDialog = false },
  title  = { Text("Sign out?") },
  text  = { Text("You'll be signed out on this device. Your training data stays on the phone, and everything already synced stays in your account.") },
  confirmButton = {
  TextButton(
  onClick = { showSignOutDialog = false; vm.signOut() },
  colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
  ) { Text("Sign out") }
  },
  dismissButton = { TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") } }
  )
  }

  if (showClearDialog) {
  AlertDialog(
  onDismissRequest = { showClearDialog = false },
  title  = { Text("Clear All Data") },
  text  = { Text("This will permanently delete all programs, sessions, and workout logs. This cannot be undone.") },
  confirmButton = {
  TextButton(
  onClick = { showClearDialog = false; vm.clearAllData {} },
  colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
  ) { Text("Delete Everything") }
  },
  dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
  )
  }

  val groups    = SettingsRegistry.grouped(query)
  val searching = query.isNotBlank()

  Column(
  modifier = Modifier
  .fillMaxSize()
  .background(MaterialTheme.colorScheme.background)
  .statusBarsPadding()
  .padding(horizontal = 20.dp)
  ) {
  Spacer(Modifier.height(20.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
  // Settings is reached by pushing this route (from Profile's gear icon), not a bottom
  // tab, so it needs its own way back.
  if (navController != null) {
  IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.size(32.dp)) {
  Icon(
  Icons.AutoMirrored.Filled.ArrowBack,
  contentDescription = "Back",
  tint = MaterialTheme.colorScheme.onBackground
  )
  }
  Spacer(Modifier.width(8.dp))
  }
  Text("Settings", fontWeight = FontWeight.Bold, fontSize = 22.sp,
  color = MaterialTheme.colorScheme.onBackground)
  }
  Spacer(Modifier.height(16.dp))

  // Pinned search field — stays put while the list below scrolls.
  SearchField(query = query, onQueryChange = { query = it }, accent = accent)
  Spacer(Modifier.height(12.dp))

  if (groups.isEmpty()) {
  Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
  Text(
  "No settings match “$query”",
  style = MaterialTheme.typography.bodyMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant
  )
  }
  } else {
  LazyColumn(
  modifier = Modifier.fillMaxWidth().weight(1f),
  contentPadding = PaddingValues(bottom = 100.dp)
  ) {
  groups.forEach { (group, groupEntries) ->
  val isExpanded = searching || (expandedState[group] ?: true)
  item(key = "header_$group") {
  GroupHeader(
  title = group,
  expanded = isExpanded,
  // While searching the list is force-expanded; hide the collapse affordance.
  collapsible = !searching,
  onClick = { expandedState[group] = !(expandedState[group] ?: true) }
  )
  }
  if (isExpanded) {
  items(groupEntries, key = { it.key }) { entry ->
  // Account-scoped rows would be dead controls with no session — hide rather than disable.
  val needsAccount = entry.control == SettingControl.SIGN_OUT ||
  entry.control == SettingControl.DELETE_ACCOUNT ||
  entry.control == SettingControl.SYNC_NOW
  if (needsAccount && signedInEmail == null) return@items
  when (entry.control) {
  SettingControl.TOGGLE -> ToggleRow(
  title    = entry.title,
  summary  = entry.summary,
  checked  = toggleStates[entry.key] ?: entry.default,
  accent   = accent,
  onToggle = { vm.setFlag(entry.key, it) },
  testTag  = "toggle_${entry.key}"
  )
  SettingControl.ACCENT -> AccentSection(
  hue = accentHue, sat = accentSat, accent = accent,
  onHue = { vm.setAccentHue(it) },
  onSat = { vm.setAccentSaturation(it) },
  onReset = { vm.resetAccentColor() }
  )
  SettingControl.TIMER_SOUND -> TimerSoundRow(
  title = entry.title, selected = timerSound, accent = accent,
  onSelect = { vm.setTimerSound(it) }
  )
  SettingControl.API_KEY -> ApiKeyRow(
  title = entry.title, summary = entry.summary,
  value = claudeApiKey, accent = accent,
  onChange = { vm.setClaudeApiKey(it.trim()) }
  )
  SettingControl.DOWNLOAD_TEMPLATE -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = accent, onClick = { downloadTemplate(context) }
  )
  SettingControl.CLEAR_DATA -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = MaterialTheme.colorScheme.error, onClick = { showClearDialog = true }
  )
  SettingControl.WEARABLE_CONNECT -> WearableConnectRow(
  title = entry.title, summary = entry.summary,
  status = wearableStatus, accent = accent,
  onConnect = {
  runCatching { requestWearablePerms.launch(vm.wearablePermissions) }
  .onFailure { Toast.makeText(context, "Couldn't open Health Connect", Toast.LENGTH_SHORT).show() }
  },
  onInstall = { openHealthConnectInStore(context) },
  onManage  = { openHealthConnectApp(context) }
  )
  SettingControl.HABITS -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = accent, onClick = { navController?.navigate("habits") }
  )
  SettingControl.EXPORT_DATA -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = accent, onClick = { showExportSheet = true }
  )
  SettingControl.REMINDER_SETTINGS -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = accent, onClick = { showReminderSheet = true }
  )
  SettingControl.ACCOUNT -> ActionRow(
  title = if (signedInEmail == null) "Sign in / Create account" else entry.title,
  summary = signedInEmail?.let { "Signed in as $it" }
  ?: "Optional — adds cross-device sync. Your data stays on this phone either way.",
  tint = accent,
  onClick = {
  if (signedInEmail == null) navController?.navigate("account_auth") else showAccountInfo = true
  }
  )
  SettingControl.SHARE_USAGE_STATS -> ToggleRow(
  title    = entry.title,
  summary  = entry.summary,
  checked  = analyticsConsent == "granted",
  accent   = accent,
  onToggle = { vm.setAnalyticsConsent(it) },
  testTag  = "toggle_${entry.key}"
  )
  SettingControl.SYNC_NOW -> ActionRow(
  title = when (syncState.phase) {
  com.wildodds.gymtracker.data.sync.SyncPhase.RUNNING -> "Syncing…"
  else -> entry.title
  },
  summary = when {
  syncState.phase == com.wildodds.gymtracker.data.sync.SyncPhase.FAILED ->
  "Last attempt failed — tap to retry"
  syncState.lastSyncAt > 0L ->
  "Last synced ${android.text.format.DateUtils.getRelativeTimeSpanString(syncState.lastSyncAt)}"
  else -> "Never synced yet"
  },
  tint = accent, onClick = { vm.syncNow() }
  )
  SettingControl.EXPORT_ACCOUNT -> ActionRow(
  title = if (exportState.inProgress) "Exporting…" else entry.title,
  summary = entry.summary,
  tint = accent,
  onClick = { if (!exportState.inProgress) exportLauncher.launch("wildodds-export.zip") }
  )
  SettingControl.SIGN_OUT -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = MaterialTheme.colorScheme.error, onClick = { showSignOutDialog = true }
  )
  SettingControl.DELETE_ACCOUNT -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = MaterialTheme.colorScheme.error, onClick = { navController?.navigate("delete_account") }
  )
  SettingControl.LEGAL_DOC -> ActionRow(
  title = entry.title, summary = entry.summary,
  tint = accent, onClick = { navController?.navigate("legal/${entry.key}") }
  )
  }
  }
  }
  }
  item(key = "footer") { Footer() }
  }
  }
  }
}

// ── Search ──────────────────────────────────────────────────────────────────────

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, accent: Color) {
  OutlinedTextField(
  value = query,
  onValueChange = onQueryChange,
  modifier = Modifier.fillMaxWidth().testTag("settings_search"),
  singleLine = true,
  placeholder = { Text("Search settings") },
  leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
  trailingIcon = {
  if (query.isNotEmpty()) {
  IconButton(onClick = { onQueryChange("") }) {
  Icon(Icons.Default.Clear, contentDescription = "Clear search")
  }
  }
  },
  shape = RoundedCornerShape(14.dp),
  colors = OutlinedTextFieldDefaults.colors(
  unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
  focusedBorderColor = accent,
  cursorColor = accent
  )
  )
}

// ── Group header (collapsible) ───────────────────────────────────────────────────

@Composable
private fun GroupHeader(title: String, expanded: Boolean, collapsible: Boolean, onClick: () -> Unit) {
  val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
  Row(
  modifier = Modifier
  .fillMaxWidth()
  .then(if (collapsible) Modifier.clickable(onClick = onClick) else Modifier)
  .padding(top = 18.dp, bottom = 6.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Text(
  title.uppercase(),
  style = MaterialTheme.typography.labelMedium,
  fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  letterSpacing = 0.8.sp,
  modifier = Modifier.weight(1f)
  )
  if (collapsible) {
  Icon(
  Icons.Default.KeyboardArrowDown,
  contentDescription = if (expanded) "Collapse" else "Expand",
  tint = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.rotate(rotation)
  )
  }
  }
}

// ── Rows ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, accent: Color, onToggle: (Boolean) -> Unit, testTag: String = "") {
  Row(
  modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically
  ) {
  Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
  Text(title, color = MaterialTheme.colorScheme.onBackground,
  style = MaterialTheme.typography.bodyLarge)
  Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelSmall)
  }
  Switch(
  checked = checked,
  onCheckedChange = onToggle,
  modifier = if (testTag.isEmpty()) Modifier else Modifier.testTag(testTag),
  colors = SwitchDefaults.colors(
  checkedThumbColor = accent,
  checkedTrackColor = accent.copy(0.4f))
  )
  }
}

@Composable
private fun ActionRow(title: String, summary: String, tint: Color, onClick: () -> Unit) {
  Row(
  modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
  verticalAlignment = Alignment.CenterVertically
  ) {
  Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
  Text(title, color = tint, style = MaterialTheme.typography.bodyLarge,
  fontWeight = FontWeight.SemiBold)
  Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelSmall)
  }
  }
}

/**
 * The "Connect wearable (Health Connect)" row: a live status line, a status-appropriate action
 * button (Connect / Install / Update / Manage), and a short privacy note. Designed to stay calm —
 * one line of status, one button — with the heavier permission UI living in Health Connect itself.
 */
@Composable
private fun WearableConnectRow(
  title: String,
  summary: String,
  status: WearableConnectionStatus,
  accent: Color,
  onConnect: () -> Unit,
  onInstall: () -> Unit,
  onManage: () -> Unit
) {
  val (statusText, dotColor) = when (status) {
  WearableConnectionStatus.CONNECTED      -> "Connected" to accent
  WearableConnectionStatus.DISCONNECTED   -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
  WearableConnectionStatus.UPDATE_REQUIRED -> "Update needed" to MaterialTheme.colorScheme.error
  WearableConnectionStatus.UNAVAILABLE    -> "Health Connect not installed" to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f).padding(end = 12.dp)) {
  Text(title, color = MaterialTheme.colorScheme.onBackground,
  style = MaterialTheme.typography.bodyLarge)
  Spacer(Modifier.height(4.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
  Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
  Spacer(Modifier.width(6.dp))
  Text(statusText, style = MaterialTheme.typography.labelMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  when (status) {
  WearableConnectionStatus.DISCONNECTED -> Button(
  onClick = onConnect, shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent)
  ) { Text("Connect") }
  WearableConnectionStatus.CONNECTED -> OutlinedButton(
  onClick = onManage, shape = RoundedCornerShape(12.dp)
  ) { Text("Manage") }
  WearableConnectionStatus.UNAVAILABLE -> Button(
  onClick = onInstall, shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent)
  ) { Text("Install") }
  WearableConnectionStatus.UPDATE_REQUIRED -> Button(
  onClick = onInstall, shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(containerColor = accent)
  ) { Text("Update") }
  }
  }
  Spacer(Modifier.height(8.dp))
  Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelSmall)
  Spacer(Modifier.height(6.dp))
  Text(
  "Heart-rate data stays on this device — it's used only for your session summaries and is never uploaded.",
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
  )
  }
}

@Composable
private fun ApiKeyRow(title: String, summary: String, value: String, accent: Color, onChange: (String) -> Unit) {
  var showKey by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
  Text(summary, style = MaterialTheme.typography.bodySmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(8.dp))
  OutlinedTextField(
  value = value,
  onValueChange = onChange,
  label = { Text(title) },
  placeholder = { Text("sk-ant-api03-...") },
  singleLine = true,
  modifier = Modifier.fillMaxWidth(),
  visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
  trailingIcon = {
  IconButton(onClick = { showKey = !showKey }) {
  Icon(
  if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
  contentDescription = if (showKey) "Hide" else "Show"
  )
  }
  },
  colors = OutlinedTextFieldDefaults.colors(
  unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
  focusedBorderColor = accent
  )
  )
  if (value.isBlank()) {
  Spacer(Modifier.height(4.dp))
  Text(
  "Get a free key at console.anthropic.com",
  style = MaterialTheme.typography.labelSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
  )
  }
  }
}

@Composable
private fun TimerSoundRow(title: String, selected: String, accent: Color, onSelect: (String) -> Unit) {
  val options = listOf("none" to "No Sound", "ding" to "Ding", "ronnie" to "Ronnie Coleman")
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
  Text(title, color = MaterialTheme.colorScheme.onBackground,
  style = MaterialTheme.typography.bodyMedium)
  Spacer(Modifier.height(8.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  options.forEach { (key, label) ->
  val isSelected = selected == key
  Surface(
  onClick = { onSelect(key) },
  shape = RoundedCornerShape(20.dp),
  color = if (isSelected) accent else accent.copy(alpha = 0.1f)
  ) {
  Text(
  label,
  modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
  color = if (isSelected) Color.White else accent,
  style = MaterialTheme.typography.labelMedium,
  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
  )
  }
  }
  }
  }
}

@Composable
private fun AccentSection(
  hue: Float, sat: Float, accent: Color,
  onHue: (Float) -> Unit, onSat: (Float) -> Unit, onReset: () -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
  HueStrip()
  Spacer(Modifier.height(8.dp))

  Text("Hue  ${hue.toInt()}°", style = MaterialTheme.typography.labelMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Slider(
  value = hue, onValueChange = onHue, valueRange = 0f..359f, steps = 358,
  colors = SliderDefaults.colors(
  thumbColor = accent, activeTrackColor = accent,
  inactiveTrackColor = MaterialTheme.colorScheme.outline)
  )

  Spacer(Modifier.height(4.dp))
  Text("Saturation  ${(sat * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Slider(
  value = sat, onValueChange = onSat, valueRange = 0.3f..1f,
  colors = SliderDefaults.colors(
  thumbColor = accent, activeTrackColor = accent,
  inactiveTrackColor = MaterialTheme.colorScheme.outline)
  )

  Spacer(Modifier.height(8.dp))
  val presets = listOf(203f, 340f, 270f, 140f, 30f, 190f, 0f, 60f)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  presets.forEach { presetHue ->
  val presetColor = colorFromHsl(presetHue, sat, 0.64f)
  val isSelected = abs(hue - presetHue) < 3f
  Box(
  modifier = Modifier
  .size(30.dp)
  .clip(CircleShape)
  .background(presetColor)
  .then(if (isSelected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
  .clickable { onHue(presetHue) }
  )
  }
  }

  Spacer(Modifier.height(12.dp))
  OutlinedButton(
  onClick = onReset,
  modifier = Modifier.fillMaxWidth(),
  shape = RoundedCornerShape(12.dp)
  ) { Text("Reset to Default Colour") }
  }
}

@Composable
private fun HueStrip() {
  val rainbowBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
  colors = (0..12).map { colorFromHsl(it * 30f, 0.75f, 0.60f) }
  )
  Box(
  modifier = Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp))
  .background(rainbowBrush)
  )
}

@Composable
private fun Footer() {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
  Text("Wild Odds Gym Tracker", color = MaterialTheme.colorScheme.onSurfaceVariant,
  fontWeight = FontWeight.SemiBold)
  Text("v${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelMedium)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(onDismiss: () -> Unit) {
  val accent = LocalAccentColor.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }

  fun export(format: ExportFormat) {
  if (busy) return
  scope.launch {
  busy = true
  runCatching { shareTrainingExport(context, format) }
  .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show() }
  busy = false
  onDismiss()
  }
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
  Text("Export data", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(6.dp))
  Text("Share your training log. Exports are created on demand and stay yours.",
  style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(16.dp))
  Button(onClick = { export(ExportFormat.CSV) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
  shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) {
  Text("Export as CSV")
  }
  Spacer(Modifier.height(6.dp))
  OutlinedButton(onClick = { export(ExportFormat.JSON) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
  shape = RoundedCornerShape(12.dp)) { Text("Export as JSON") }
  Spacer(Modifier.height(6.dp))
  OutlinedButton(onClick = { export(ExportFormat.SUMMARY) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
  shape = RoundedCornerShape(12.dp)) { Text("Share summary") }
  }
  }
}

private enum class ExportFormat(val fileName: String, val mime: String) {
  CSV("wildodds_export.csv", "text/csv"),
  JSON("wildodds_export.json", "application/json"),
  SUMMARY("wildodds_summary.txt", "text/plain")
}

/** Build the chosen export off the main thread, write it to cache, and fire the Android share sheet. */
private suspend fun shareTrainingExport(context: Context, format: ExportFormat) {
  val backup = BackupManager(AppDatabase.getInstance(context))
  val content = withContext(Dispatchers.IO) {
  val snapshot = backup.snapshot(System.currentTimeMillis())
  when (format) {
  ExportFormat.CSV -> TrainingExporter.toCsv(snapshot)
  ExportFormat.JSON -> TrainingExporter.toJson(snapshot)
  ExportFormat.SUMMARY -> TrainingExporter.summaryText(snapshot)
  }
  }
  val file = withContext(Dispatchers.IO) {
  File(context.cacheDir, format.fileName).apply { writeText(content) }
  }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  val share = Intent(Intent.ACTION_SEND).apply {
  type = format.mime
  putExtra(Intent.EXTRA_STREAM, uri)
  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  context.startActivity(Intent.createChooser(share, "Export training data"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettingsSheet(onDismiss: () -> Unit) {
  val accent = LocalAccentColor.current
  val context = LocalContext.current
  var interval by remember { mutableStateOf(NudgeSettingsStore.minIntervalHours(context)) }
  var quietStart by remember { mutableStateOf(NudgeSettingsStore.quietStart(context)) }
  var quietEnd by remember { mutableStateOf(NudgeSettingsStore.quietEnd(context)) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
  Text("Reminder settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground)

  Spacer(Modifier.height(16.dp))
  Text("At most one reminder every", style = MaterialTheme.typography.labelMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(8.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  listOf(24, 48, 72).forEach { h ->
  val selected = interval == h
  Surface(
  onClick = { interval = h; NudgeSettingsStore.setInterval(context, h) },
  shape = RoundedCornerShape(20.dp),
  color = if (selected) accent else accent.copy(alpha = 0.1f)
  ) {
  Text("${h}h", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  color = if (selected) Color.White else accent, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
  }
  }
  }

  Spacer(Modifier.height(20.dp))
  Text("Quiet hours (never disturb)", style = MaterialTheme.typography.labelMedium,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(8.dp))
  HourStepper("From", quietStart) { quietStart = it; NudgeSettingsStore.setQuietHours(context, quietStart, quietEnd) }
  HourStepper("To", quietEnd) { quietEnd = it; NudgeSettingsStore.setQuietHours(context, quietStart, quietEnd) }

  Spacer(Modifier.height(16.dp))
  Text("Reminders are gentle and capped. You can turn them off anytime — that stops them immediately.",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
  Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
  TextButton(onClick = { onChange((hour + 23) % 24) }) { Text("–") }
  Text("%02d:00".format(hour), style = MaterialTheme.typography.bodyLarge)
  TextButton(onClick = { onChange((hour + 1) % 24) }) { Text("+") }
  }
}

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/** Send the user to install/update Health Connect, falling back to the web Play Store, then a toast. */
private fun openHealthConnectInStore(context: Context) {
  val market = Intent(
  Intent.ACTION_VIEW,
  Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
  ).apply {
  setPackage("com.android.vending")
  putExtra("overlay", true)
  putExtra("callerId", context.packageName)
  }
  runCatching { context.startActivity(market) }.onFailure {
  runCatching {
  context.startActivity(Intent(
  Intent.ACTION_VIEW,
  Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE")))
  }.onFailure {
  Toast.makeText(context, "Play Store unavailable", Toast.LENGTH_SHORT).show()
  }
  }
}

/** Open the Health Connect app so the user can review or revoke access. */
private fun openHealthConnectApp(context: Context) {
  runCatching {
  context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
  }.onFailure {
  Toast.makeText(context, "Open Health Connect to manage access", Toast.LENGTH_SHORT).show()
  }
}

private fun downloadTemplate(context: Context) {
  try {
  val assetFile = context.assets.open("template.xlsx")
  val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
  val outFile = File(downloadsDir, "WildOdds_template.xlsx")
  FileOutputStream(outFile).use { out -> assetFile.copyTo(out) }
  Toast.makeText(context, "Template saved to Downloads", Toast.LENGTH_SHORT).show()
  } catch (e: Exception) {
  Toast.makeText(context, "Could not save template: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}
