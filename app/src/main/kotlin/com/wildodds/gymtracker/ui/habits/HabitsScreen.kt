@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.habits

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wildodds.gymtracker.data.db.entity.Habit
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.widget.HabitWidgetProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HabitsScreen(vm: HabitsViewModel = viewModel()) {
  val habits  by vm.habits.collectAsStateWithLifecycle()
  val completed  by vm.todayCompletions.collectAsStateWithLifecycle()
  val context  = LocalContext.current
  var showAddSheet by remember { mutableStateOf(false) }

  val dateLabel = remember {
  LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))
  }

  if (showAddSheet) {
  AddHabitSheet(
  onAdd  = { name, emoji -> vm.addHabit(name, emoji); showAddSheet = false },
  onDismiss = { showAddSheet = false }
  )
  }

  Scaffold(
  modifier = Modifier.statusBarsPadding(),
  containerColor = MaterialTheme.colorScheme.background,
  contentWindowInsets = WindowInsets(0),
  floatingActionButton = {
  FloatingActionButton(
  onClick  = { showAddSheet = true },
  containerColor  = LocalAccentColor.current,
  contentColor  = androidx.compose.ui.graphics.Color.White
  ) {
  Icon(Icons.Default.Add, "Add habit")
  }
  }
  ) { padding ->
  LazyColumn(
  modifier  = Modifier.fillMaxSize().padding(padding),
  contentPadding = PaddingValues(bottom = 120.dp)
  ) {
  item {
  Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
  Text("Habits", fontWeight = FontWeight.Bold, fontSize = 22.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Text(dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelMedium)
  }
  }

  // Progress summary
  if (habits.isNotEmpty()) {
  item {
  val doneCount = habits.count { it.id in completed }
  Surface(
  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
  shape  = RoundedCornerShape(14.dp),
  color  = LocalAccentColor.current.copy(alpha = 0.1f)
  ) {
  Row(
  modifier  = Modifier.padding(14.dp),
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = Arrangement.SpaceBetween
  ) {
  Text("Today's progress", fontWeight = FontWeight.SemiBold,
  color = LocalAccentColor.current, fontSize = 14.sp)
  Text("$doneCount / ${habits.size}",
  fontWeight = FontWeight.Bold, color = LocalAccentColor.current, fontSize = 14.sp)
  }
  }
  }
  }

  items(habits, key = { it.id }) { habit ->
  HabitRow(
  habit  = habit,
  isCompleted = habit.id in completed,
  onToggle  = { vm.toggleHabit(habit.id) },
  onDelete  = { vm.deleteHabit(habit) }
  )
  }

  if (habits.isEmpty()) {
  item {
  Box(modifier = Modifier.fillMaxWidth().padding(40.dp),
  contentAlignment = Alignment.Center) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(Icons.Default.CheckCircle, null, tint = LocalAccentColor.current.copy(0.4f),
  modifier = Modifier.size(48.dp))
  Spacer(Modifier.height(12.dp))
  Text("No habits yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
  fontWeight = FontWeight.Medium)
  Text("Tap + to add your first habit",
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelMedium)
  }
  }
  }
  }

  // Add widget button
  item {
  OutlinedButton(
  onClick = {
  val mgr = AppWidgetManager.getInstance(context)
  val cn  = ComponentName(context, HabitWidgetProvider::class.java)
  if (mgr.isRequestPinAppWidgetSupported) {
  mgr.requestPinAppWidget(cn, null, null)
  } else {
  Toast.makeText(context,
  "Long-press your home screen and tap Widgets to add",
  Toast.LENGTH_LONG).show()
  }
  },
  modifier = Modifier
  .fillMaxWidth()
  .padding(horizontal = 16.dp)
  .padding(top = 16.dp),
  shape  = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalAccentColor.current),
  border = androidx.compose.foundation.BorderStroke(1.dp, LocalAccentColor.current)
  ) {
  Icon(Icons.Default.Widgets, null, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(8.dp))
  Text("Add Widget to Home Screen", fontWeight = FontWeight.SemiBold)
  }
  }
  }
  }
}

@Composable
private fun HabitRow(
  habit:  Habit,
  isCompleted: Boolean,
  onToggle:  () -> Unit,
  onDelete:  () -> Unit
) {
  GlassCard(modifier = Modifier
  .fillMaxWidth()
  .padding(horizontal = 16.dp, vertical = 4.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
  Checkbox(
  checked  = isCompleted,
  onCheckedChange = { onToggle() },
  colors  = CheckboxDefaults.colors(
  checkedColor  = LocalAccentColor.current,
  uncheckedColor  = MaterialTheme.colorScheme.outline,
  checkmarkColor  = androidx.compose.ui.graphics.Color.White
  )
  )
  Spacer(Modifier.width(4.dp))
  if (habit.emoji.isNotBlank()) {
  Text(habit.emoji, fontSize = 20.sp, modifier = Modifier.padding(end = 8.dp))
  }
  Text(
  habit.name,
  modifier  = Modifier.weight(1f),
  fontWeight = if (isCompleted) FontWeight.Normal else FontWeight.SemiBold,
  color  = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
  else MaterialTheme.colorScheme.onBackground,
  fontSize  = 16.sp
  )
  IconButton(onClick = onDelete) {
  Icon(Icons.Default.Delete, "Delete",
  tint  = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
  modifier = Modifier.size(18.dp))
  }
  }
  }
}

@Composable
private fun AddHabitSheet(
  onAdd:  (name: String, emoji: String) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState()
  var name  by remember { mutableStateOf("") }
  var emoji by remember { mutableStateOf("") }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
  Column(
  modifier = Modifier
  .fillMaxWidth()
  .padding(horizontal = 20.dp)
  .padding(bottom = 32.dp)
  ) {
  Text("New Habit", fontWeight = FontWeight.Bold, fontSize = 18.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(16.dp))

  Row(verticalAlignment = Alignment.CenterVertically) {
  OutlinedTextField(
  value = emoji, onValueChange = { emoji = it.take(2) },
  modifier  = Modifier.width(72.dp),
  placeholder = { Text("😀") },
  singleLine  = true,
  label  = { Text("Emoji") },
  shape  = RoundedCornerShape(12.dp),
  colors  = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )
  Spacer(Modifier.width(12.dp))
  OutlinedTextField(
  value = name, onValueChange = { name = it },
  modifier  = Modifier.weight(1f),
  placeholder = { Text("e.g. Exercise, Read, Meditate") },
  singleLine  = true,
  label  = { Text("Habit name") },
  keyboardOptions = KeyboardOptions(
  capitalization = KeyboardCapitalization.Sentences,
  imeAction  = ImeAction.Done
  ),
  shape  = RoundedCornerShape(12.dp),
  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccentColor.current)
  )
  }

  Spacer(Modifier.height(16.dp))

  Button(
  onClick  = { onAdd(name, emoji) },
  enabled  = name.isNotBlank(),
  modifier = Modifier.fillMaxWidth().height(52.dp),
  shape  = RoundedCornerShape(14.dp),
  colors  = ButtonDefaults.buttonColors(
  containerColor  = LocalAccentColor.current,
  disabledContainerColor = MaterialTheme.colorScheme.outline
  )
  ) {
  Text("Add Habit", fontWeight = FontWeight.Bold, fontSize = 16.sp)
  }
  }
  }
}
