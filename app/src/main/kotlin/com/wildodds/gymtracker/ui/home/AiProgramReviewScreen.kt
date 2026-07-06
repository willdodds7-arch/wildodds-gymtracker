@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.wildodds.gymtracker.data.ai.AiDay
import com.wildodds.gymtracker.data.ai.AiExercise
import com.wildodds.gymtracker.data.ai.AiParsedProgram
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

@Composable
fun AiProgramReviewSheet(
    initial: AiParsedProgram,
    onConfirm: (program: AiParsedProgram, name: String, weeks: Int, activate: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = LocalAccentColor.current
    val sheetState = rememberModalBottomSheetState()

    // Mutable state for full editing
    var programName by remember { mutableStateOf(initial.name) }
    var weeksText by remember { mutableStateOf(initial.totalWeeks.toString()) }
    var days by remember { mutableStateOf(initial.days.toMutableStateList()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxHeight(0.95f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    "Review Program",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Claude read your file and built this. Edit anything before importing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // Program name
                OutlinedTextField(
                    value = programName,
                    onValueChange = { programName = it },
                    label = { Text("Program Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedBorderColor = accent
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = weeksText,
                    onValueChange = { weeksText = it.filter { c -> c.isDigit() } },
                    label = { Text("Total Weeks") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedBorderColor = accent
                    )
                )
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                itemsIndexed(days, key = { i, _ -> "day_$i" }) { dayIdx, day ->
                    ReviewDayCard(
                        day = day,
                        dayIndex = dayIdx,
                        accent = accent,
                        onDayChanged = { updated -> days[dayIdx] = updated },
                        onDeleteDay = { days.removeAt(dayIdx) }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                item(key = "add_day") {
                    OutlinedButton(
                        onClick = {
                            val nextNum = (days.maxOfOrNull { it.dayNumber } ?: 0) + 1
                            days.add(
                                AiDay(
                                    dayNumber = nextNum,
                                    name = "Day $nextNum",
                                    muscleGroups = "",
                                    exercises = emptyList()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Day")
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }

            // Bottom action bar
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        val weeks = weeksText.toIntOrNull()?.coerceIn(1, 52) ?: initial.totalWeeks
                        val updated = AiParsedProgram(
                            name = programName.ifBlank { initial.name },
                            totalWeeks = weeks,
                            days = days.toList()
                        )
                        onConfirm(updated, updated.name, weeks, false)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent.copy(alpha = 0.2f),
                        contentColor = accent
                    )
                ) { Text("Save to Library") }

                Button(
                    onClick = {
                        val weeks = weeksText.toIntOrNull()?.coerceIn(1, 52) ?: initial.totalWeeks
                        val updated = AiParsedProgram(
                            name = programName.ifBlank { initial.name },
                            totalWeeks = weeks,
                            days = days.toList()
                        )
                        onConfirm(updated, updated.name, weeks, true)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Start Now") }
            }
        }
    }
}

@Composable
private fun ReviewDayCard(
    day: AiDay,
    dayIndex: Int,
    accent: Color,
    onDayChanged: (AiDay) -> Unit,
    onDeleteDay: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var editingName by remember { mutableStateOf(false) }
    var nameText by remember(day.name) { mutableStateOf(day.name) }
    var muscleText by remember(day.muscleGroups) { mutableStateOf(day.muscleGroups) }
    val exercises = remember(day.exercises) { day.exercises.toMutableStateList() }

    LaunchedEffect(exercises.toList()) {
        onDayChanged(day.copy(exercises = exercises.toList()))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Day header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accent.copy(alpha = 0.15f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "${day.dayNumber}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = accent
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                if (editingName) {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            focusedBorderColor = accent
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                onDayChanged(day.copy(name = nameText, muscleGroups = muscleText))
                                editingName = false
                            }) {
                                Icon(Icons.Default.Check, null, tint = accent)
                            }
                        }
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(
                            day.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (day.muscleGroups.isNotBlank()) {
                            Text(
                                day.muscleGroups,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { editingName = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDeleteDay, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, null,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp))
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    exercises.forEachIndexed { exIdx, ex ->
                        ReviewExerciseRow(
                            exercise = ex,
                            accent = accent,
                            onChanged = { updated -> exercises[exIdx] = updated },
                            onDelete = { exercises.removeAt(exIdx) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    TextButton(
                        onClick = {
                            exercises.add(AiExercise("New Exercise", 3, "10-12", ""))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Exercise", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewExerciseRow(
    exercise: AiExercise,
    accent: Color,
    onChanged: (AiExercise) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var nameText by remember(exercise.name) { mutableStateOf(exercise.name) }
    var setsText by remember(exercise.sets) { mutableStateOf(exercise.sets.toString()) }
    var repsText by remember(exercise.repsTarget) { mutableStateOf(exercise.repsTarget) }
    var notesText by remember(exercise.notes) { mutableStateOf(exercise.notes) }

    if (editing) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(10.dp)
        ) {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text("Exercise") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = accent
                )
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = setsText,
                    onValueChange = { setsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Sets") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedBorderColor = accent
                    )
                )
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { repsText = it },
                    label = { Text("Reps") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedBorderColor = accent
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        onChanged(
                            AiExercise(
                                nameText.trim().ifEmpty { exercise.name },
                                setsText.toIntOrNull() ?: exercise.sets,
                                repsText.trim(),
                                notesText.trim()
                            )
                        )
                        editing = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Save") }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { editing = true }
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    exercise.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "${exercise.sets} sets × ${exercise.repsTarget.ifBlank { "—" }} reps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { editing = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, null,
                    tint = accent.copy(alpha = 0.5f),
                    modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                    modifier = Modifier.size(15.dp))
            }
        }
    }
}
