@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildodds.gymtracker.ui.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.builder.EquipmentGuessAccess
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.LocalAppColors

/**
 * The exercise picker (§2.1).
 *
 * Multi-select by design: adding eight exercises to a workout should be one trip, not eight. The
 * search field takes focus on open, and anything typed that matches nothing can still be added as a
 * custom exercise — the picker never becomes a dead end.
 *
 * Fully offline: names and muscle data come from the bundled [ExerciseLibrary]. There are no
 * thumbnails because the app ships no exercise imagery; the row shows muscle + equipment instead,
 * which is the information that actually helps you choose.
 */
@Composable
fun ExercisePickerSheet(
  /** Names already in the target workout, shown with an "added" marker. */
  alreadyInWorkout: Set<String>,
  recents: List<String>,
  onAdd: (List<String>) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current

  var query by remember { mutableStateOf("") }
  var muscleFilter by remember { mutableStateOf<MuscleGroup?>(null) }
  var equipmentFilter by remember { mutableStateOf<String?>(null) }
  var showRecentOnly by remember { mutableStateOf(false) }
  val selected = remember { mutableStateListOf<String>() }

  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

  // The whole catalogue, title-cased once. ~250 entries, so filtering it per keystroke is cheap —
  // but the catalogue itself is built only once.
  val catalogue = remember { ExerciseCatalogue.all() }

  val results = remember(catalogue, query, muscleFilter, equipmentFilter, showRecentOnly, recents) {
    val q = query.trim()
    catalogue.filter { entry ->
      (q.isEmpty() || entry.name.contains(q, ignoreCase = true)) &&
        (muscleFilter == null || entry.primary.contains(muscleFilter)) &&
        (equipmentFilter == null || entry.equipment == equipmentFilter) &&
        (!showRecentOnly || recents.any { it.equals(entry.name, ignoreCase = true) })
    }
  }

  /** Typed something the library doesn't know? Offer it as a custom exercise rather than nothing. */
  val customName = query.trim().takeIf { typed ->
    typed.isNotEmpty() && results.none { it.name.equals(typed, ignoreCase = true) }
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {

      Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Add exercises", style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
        if (selected.isNotEmpty()) {
          Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium, color = accent)
        }
      }

      Spacer(Modifier.height(12.dp))

      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focusRequester),
        placeholder = { Text("Search exercises") },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = accent) },
        trailingIcon = if (query.isNotEmpty()) {{
          IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Clear, "Clear search", tint = c.textSecondary)
          }
        }} else null,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
      )

      Spacer(Modifier.height(10.dp))

      // Quick filter chips. Deliberately a short, opinionated set rather than every enum value.
      Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        PickerChip("Recent", showRecentOnly, accent, enabled = recents.isNotEmpty()) {
          showRecentOnly = !showRecentOnly
        }
        listOf(
          MuscleGroup.CHEST, MuscleGroup.UPPER_BACK, MuscleGroup.QUADS,
          MuscleGroup.HAMSTRINGS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS
        ).forEach { m ->
          PickerChip(m.display, muscleFilter == m, accent) {
            muscleFilter = if (muscleFilter == m) null else m
          }
        }
        listOf("Barbell", "Dumbbell", "Machine", "Cable", "Bodyweight").forEach { e ->
          PickerChip(e, equipmentFilter == e, accent) {
            equipmentFilter = if (equipmentFilter == e) null else e
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      HorizontalDivider(color = c.border)

      LazyColumn(Modifier.weight(1f)) {
        if (customName != null) {
          item(key = "custom") {
            ListItem(
              headlineContent = { Text("Add \"$customName\"", fontWeight = FontWeight.Bold) },
              supportingContent = { Text("Create a custom exercise") },
              leadingContent = { Icon(Icons.Default.Add, null, tint = accent) },
              colors = ListItemDefaults.colors(containerColor = Color.Transparent),
              modifier = Modifier
                .heightIn(min = 56.dp)
                .clickable { if (customName !in selected) selected.add(customName) }
            )
            HorizontalDivider(color = c.border)
          }
        }

        items(results, key = { it.name }) { entry ->
          val isSelected = entry.name in selected
          val alreadyAdded = alreadyInWorkout.any { it.equals(entry.name, ignoreCase = true) }
          ListItem(
            headlineContent = {
              Text(entry.name, color = c.text,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            },
            supportingContent = {
              Text(
                buildString {
                  append(entry.primary.firstOrNull()?.display ?: "—")
                  entry.equipment?.let { append(" · $it") }
                  if (alreadyAdded) append(" · already in this workout")
                },
                color = if (alreadyAdded) accent else c.textSecondary
              )
            },
            trailingContent = {
              if (isSelected) Icon(Icons.Default.Check, "Selected", tint = accent)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
              .heightIn(min = 56.dp)
              .semantics {
                stateDescription = if (isSelected) "Selected" else "Not selected"
              }
              .clickable {
                if (isSelected) selected.remove(entry.name) else selected.add(entry.name)
              }
          )
          HorizontalDivider(color = c.border)
        }

        if (results.isEmpty() && customName == null) {
          item {
            Text(
              "No exercises match that.",
              color = c.textSecondary,
              modifier = Modifier.fillMaxWidth().padding(24.dp)
            )
          }
        }
      }

      Surface(color = c.surface) {
        Row(
          Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
          ) { Text("Cancel") }
          Button(
            onClick = { onAdd(selected.toList()); onDismiss() },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent)
          ) {
            Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun PickerChip(
  label: String,
  selected: Boolean,
  accent: Color,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  val c = LocalAppColors.current
  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(20.dp),
    color = if (selected) accent else c.surface2,
    modifier = Modifier.heightIn(min = 36.dp).semantics {
      stateDescription = if (selected) "On" else "Off"
    }
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
      color = if (selected) Color.White else if (enabled) c.text else c.textTertiary,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
    )
  }
}

/**
 * A flat, searchable view over the bundled exercise data.
 *
 * The library maps name → muscles; equipment is inferred from the name (the authoritative equipment
 * data lives in an asset the picker would have to load asynchronously, and a wrong-but-instant
 * "Barbell" tag on a row is a better trade than a stalled sheet).
 */
internal object ExerciseCatalogue {
  data class Entry(val name: String, val primary: List<MuscleGroup>, val equipment: String?)

  private val cache: List<Entry> by lazy {
    ExerciseLibrary.LIBRARY.entries
      .flatMap { (key, info) ->
        // Keys can be alternation patterns ("bench press|bench"); the first form is the canonical one.
        val canonical = key.split("|").first().trim()
        if (canonical.isEmpty()) emptyList()
        else listOf(Entry(titleCase(canonical), info.primary, EquipmentGuessAccess.of(canonical)))
      }
      .distinctBy { it.name.lowercase() }
      .sortedBy { it.name }
  }

  fun all(): List<Entry> = cache

  private fun titleCase(s: String) =
    s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
