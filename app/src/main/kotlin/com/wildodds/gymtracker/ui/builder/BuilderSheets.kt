@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildodds.gymtracker.ui.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.data.builder.*
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.theme.LocalAppColors

// ─────────────────────────────────────────────────────────────────────────────
// §2.3 Templates — bundled in the binary so they work offline on first launch
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TemplatePickerSheet(onPick: (PlanProgram) -> Unit, onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
      Text(
        "Templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        color = c.text, modifier = Modifier.padding(horizontal = 20.dp)
      )
      Text(
        "A starting structure you can change however you like.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
      )
      Spacer(Modifier.height(8.dp))
      Column(Modifier.verticalScroll(rememberScrollState())) {
        ProgramTemplates.all().forEach { template ->
          Row(
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 4.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(c.surface)
              .clickable { onPick(template) }
              .heightIn(min = 56.dp)
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(Modifier.weight(1f)) {
              Text(template.name, color = c.text, fontWeight = FontWeight.Bold)
              Text(
                "${ProgramMath.trainingDaysPerWeek(template)} days · ${template.description}",
                color = c.textSecondary, style = MaterialTheme.typography.bodySmall
              )
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = c.textTertiary)
          }
        }
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Paste a program — the prominent, fully-offline import path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Quick paste entry point. Wild Odds JSON is applied directly; anything else (a forum post, a
 * coach's text, a CSV) is handed to the full universal importer, which has the review UI for
 * resolving ambiguous exercise names.
 *
 * Everything runs on-device. Nothing typed here leaves the phone.
 */
@Composable
fun PasteProgramSheet(
  onImport: (String) -> Unit,
  onOpenFullImporter: () -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  var text by remember { mutableStateOf("") }

  val looksLikeJson = remember(text) { text.trimStart().startsWith("{") }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.ContentPaste, null, tint = accent)
        Spacer(Modifier.width(8.dp))
        Text("Paste a program", style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold, color = c.text)
      }
      Spacer(Modifier.height(4.dp))
      Text(
        "Paste it from anywhere — a forum post, a coach's message, a spreadsheet, or a Wild Odds " +
          "JSON export. It's read on your device; nothing is uploaded.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary
      )

      Spacer(Modifier.height(12.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        placeholder = { Text("Day 1 - Push\nBench Press 3x8\nOverhead Press 3x10\n…") },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
      )

      Spacer(Modifier.height(12.dp))

      Button(
        onClick = { if (looksLikeJson) onImport(text) else onOpenFullImporter() },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent)
      ) {
        Text(if (looksLikeJson) "Import program" else "Review and import", fontWeight = FontWeight.Bold)
      }

      if (!looksLikeJson) {
        Spacer(Modifier.height(6.dp))
        Text(
          "Plain text and CSV go through a review step so you can fix any exercise it wasn't sure about.",
          style = MaterialTheme.typography.labelSmall, color = c.textTertiary
        )
      }
    }
  }
}

/** §3.5 — import validates and previews before writing anything. */
@Composable
fun ImportPreviewDialog(
  raw: String,
  hasExisting: Boolean,
  onConfirm: (replace: Boolean) -> Unit,
  onDismiss: () -> Unit
) {
  val decoded = remember(raw) { ProgramPlanJson.decode(raw) }

  when (decoded) {
    is ProgramPlanJson.Result.Invalid -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Can't import that") },
      text = { Text(decoded.reason) },
      confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )

    is ProgramPlanJson.Result.Ok -> {
      val p = decoded.program
      val summary = remember(p) { ProgramMath.summarise(p) }
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import \"${p.name}\"?") },
        text = {
          Column {
            Text(summary.headline)
            Spacer(Modifier.height(8.dp))
            p.workouts.take(8).forEach {
              Text(
                "· ${it.name.ifBlank { "Untitled" }} — " +
                  if (it.isRest) "rest" else "${it.exerciseCount} exercises",
                style = MaterialTheme.typography.bodySmall
              )
            }
            if (p.workouts.size > 8) {
              Text("· and ${p.workouts.size - 8} more", style = MaterialTheme.typography.bodySmall)
            }
            decoded.notes.forEach {
              Spacer(Modifier.height(6.dp))
              Text(it, style = MaterialTheme.typography.labelSmall)
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { onConfirm(true) }) {
            Text(if (hasExisting) "Replace" else "Import")
          }
        },
        dismissButton = {
          Row {
            if (hasExisting) {
              TextButton(onClick = { onConfirm(false) }) { Text("Append") }
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
          }
        }
      )
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// §3.1 Scheduling — optional, collapsed, never a forced calendar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SchedulingSheet(plan: PlanProgram, vm: BuilderViewModel, onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      Text("Scheduling", style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold, color = c.text)
      Text(
        "Assign weekdays if you train on fixed days, or leave them unset and just run the " +
          "workouts in order.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary
      )
      Spacer(Modifier.height(12.dp))

      Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
        plan.workouts.forEach { w ->
          Column(Modifier.padding(vertical = 6.dp)) {
            Text(w.name.ifBlank { "Untitled" }, color = c.text, fontWeight = FontWeight.Bold)
            LabelledChips(
              label = "",
              options = listOf("Any", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
              selectedIndex = w.weekday,
              onSelect = { vm.setWorkoutWeekday(w.id, it) }
            )
          }
        }

        LabelledChips(
          label = "Deload every",
          options = listOf("Never", "4 weeks", "5 weeks", "6 weeks", "8 weeks"),
          selectedIndex = when (plan.progression.deloadEveryWeeks) {
            0 -> 0; 4 -> 1; 5 -> 2; 6 -> 3; 8 -> 4; else -> 0
          },
          onSelect = {
            vm.setProgression(
              plan.progression.copy(deloadEveryWeeks = listOf(0, 4, 5, 6, 8)[it])
            )
          }
        )
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// §3.2 Progression — always explained, always proposal-only
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProgressionSheet(plan: PlanProgram, vm: BuilderViewModel, onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val c = LocalAppColors.current
  val accent = LocalAccentColor.current
  val s = plan.progression

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
        .verticalScroll(rememberScrollState())
    ) {
      Text("Progression", style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold, color = c.text)
      Spacer(Modifier.height(8.dp))

      ProgressionMethod.entries.forEach { method ->
        Row(
          Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable { vm.setProgression(s.copy(method = method)) },
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = s.method == method,
            onClick = { vm.setProgression(s.copy(method = method)) }
          )
          Text(method.display, color = c.text)
        }
      }

      Spacer(Modifier.height(8.dp))
      // Plain-language explanation of whatever is selected — never assume the jargon is known.
      Surface(shape = RoundedCornerShape(10.dp), color = c.surface2) {
        Text(
          s.method.explain(s),
          modifier = Modifier.padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = c.textSecondary
        )
      }

      if (s.method != ProgressionMethod.MANUAL) {
        LabelledChips(
          label = "Load increment",
          options = listOf("1 kg", "2.5 kg", "5 kg"),
          selectedIndex = when (s.loadIncrement) { 1f -> 0; 2.5f -> 1; 5f -> 2; else -> 1 },
          onSelect = { vm.setProgression(s.copy(loadIncrement = listOf(1f, 2.5f, 5f)[it])) }
        )
        LabelledChips(
          label = "Rep range",
          options = listOf("5–8", "6–10", "8–12", "10–15"),
          selectedIndex = when (s.repRangeLow to s.repRangeHigh) {
            5 to 8 -> 0; 6 to 10 -> 1; 8 to 12 -> 2; 10 to 15 -> 3; else -> 2
          },
          onSelect = {
            val (lo, hi) = listOf(5 to 8, 6 to 10, 8 to 12, 10 to 15)[it]
            vm.setProgression(s.copy(repRangeLow = lo, repRangeHigh = hi))
          }
        )

        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Suggest changes", color = c.text)
            Text(
              "Suggestions only — nothing changes until you accept it.",
              style = MaterialTheme.typography.labelSmall, color = c.textSecondary
            )
          }
          Switch(
            checked = s.autoRecommend,
            onCheckedChange = { vm.setProgression(s.copy(autoRecommend = it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = accent)
          )
        }
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bundled templates
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Starting structures, defined in code so they are part of the binary and available offline on the
 * very first launch after install — no asset load, no download.
 *
 * They intentionally carry exercises but leave loads blank: a template should give you the shape of
 * the week, not pretend to know what you lift.
 */
internal object ProgramTemplates {

  fun all(): List<PlanProgram> = listOf(
    pushPullLegs(), upperLower(), fullBody(), broSplit(), fiveByFive(), beginnerThreeDay(), homeWorkout()
  )

  private fun sets(n: Int, reps: String, rest: Int = 90) =
    List(n) { PlanSet(type = SetType.WORKING, reps = reps, restSeconds = rest) }

  private fun ex(name: String, n: Int, reps: String, rest: Int = 90) =
    PlanExercise(name = name, sets = sets(n, reps, rest))

  /**
   * A heavy compound with warm-up sets ramping into the working sets — the shape people actually
   * use for their first lift of the day. Templates lead with these so evolving sets are visible
   * from the first program a user opens rather than being a setting they have to go find.
   */
  private fun ramped(name: String, n: Int, reps: String, rest: Int = 150) =
    PlanExercise(name = name, sets = SetShape.WARMUP_RAMP.build(n, reps, rest))

  private fun topSet(name: String, n: Int, reps: String, rest: Int = 180) =
    PlanExercise(name = name, sets = SetShape.TOP_SET_BACKOFF.build(n, reps, rest))

  private fun workout(name: String, vararg exercises: PlanExercise) =
    PlanWorkout(name = name, exercises = exercises.toList())

  private fun rest() = PlanWorkout(name = "Rest", kind = WorkoutKind.REST)

  // Run twice through, which is what "PPL" normally means and what the description promises —
  // the first version listed one rotation and then claimed to be a 6-day split.
  private fun pushPullLegs() = PlanProgram(
    name = "Push Pull Legs",
    description = "Hypertrophy-focused 6-day split",
    goal = ProgramGoal.HYPERTROPHY,
    workouts = listOf(
      workout("Push A",
        ramped("Barbell Bench Press", 4, "6-8"),
        ex("Overhead Press", 3, "8-10"),
        ex("Incline Dumbbell Press", 3, "10-12"),
        ex("Lateral Raise", 3, "12-15", 60),
        ex("Rope Pushdown", 3, "10-12", 60)
      ),
      workout("Pull A",
        ramped("Deadlift", 3, "5", 180),
        ex("Pull-up", 3, "8-10"),
        ex("Barbell Row", 3, "8-10"),
        ex("Face Pull", 3, "12-15", 60),
        ex("Barbell Curl", 3, "10-12", 60)
      ),
      workout("Legs A",
        ramped("Back Squat", 4, "6-8", 180),
        ex("Romanian Deadlift", 3, "8-10"),
        ex("Leg Press", 3, "10-12"),
        ex("Leg Curl", 3, "12-15", 60),
        ex("Calf Raise", 4, "12-15", 60)
      ),
      workout("Push B",
        ex("Incline Dumbbell Press", 4, "8-10"),
        ex("Dumbbell Shoulder Press", 3, "10-12"),
        ex("Cable Fly", 3, "12-15", 60),
        ex("Lateral Raise", 3, "12-15", 60),
        ex("Overhead Tricep Extension", 3, "10-12", 60)
      ),
      workout("Pull B",
        ex("Barbell Row", 4, "8-10"),
        ex("Lat Pulldown", 3, "10-12"),
        ex("Cable Row", 3, "10-12"),
        ex("Rear Delt Fly", 3, "15", 60),
        ex("Hammer Curl", 3, "10-12", 60)
      ),
      workout("Legs B",
        ex("Front Squat", 4, "8-10", 150),
        ex("Leg Curl", 3, "12-15", 60),
        ex("Leg Extension", 3, "12-15", 60),
        ex("Calf Raise", 4, "12-15", 60)
      ),
      rest()
    )
  )

  private fun upperLower() = PlanProgram(
    name = "Upper / Lower",
    description = "4-day strength and size",
    goal = ProgramGoal.HYPERTROPHY,
    workouts = listOf(
      workout("Upper A",
        ramped("Barbell Bench Press", 4, "6-8"),
        ex("Barbell Row", 4, "6-8", 150),
        ex("Overhead Press", 3, "8-10"),
        ex("Lat Pulldown", 3, "10-12"),
        ex("Barbell Curl", 3, "10-12", 60)
      ),
      workout("Lower A",
        ramped("Back Squat", 4, "6-8", 180),
        ex("Romanian Deadlift", 3, "8-10"),
        ex("Leg Press", 3, "10-12"),
        ex("Calf Raise", 4, "12-15", 60)
      ),
      rest(),
      workout("Upper B",
        ex("Incline Dumbbell Press", 4, "8-10"),
        ex("Pull-up", 4, "8-10"),
        ex("Dumbbell Shoulder Press", 3, "10-12"),
        ex("Cable Row", 3, "10-12"),
        ex("Rope Pushdown", 3, "12-15", 60)
      ),
      workout("Lower B",
        ex("Deadlift", 3, "5", 180),
        ex("Front Squat", 3, "8-10"),
        ex("Leg Curl", 3, "12-15", 60),
        ex("Hanging Leg Raise", 3, "12-15", 60)
      ),
      rest()
    )
  )

  private fun fullBody() = PlanProgram(
    name = "Full Body",
    description = "3 days a week, everything each session",
    goal = ProgramGoal.GENERAL,
    workouts = listOf(
      workout("Full Body A",
        ex("Back Squat", 3, "5", 180),
        ex("Barbell Bench Press", 3, "5", 150),
        ex("Barbell Row", 3, "8-10"),
        ex("Plank", 3, "45s", 60)
      ),
      rest(),
      workout("Full Body B",
        ex("Deadlift", 3, "5", 180),
        ex("Overhead Press", 3, "5", 150),
        ex("Lat Pulldown", 3, "8-10"),
        ex("Hanging Leg Raise", 3, "12-15", 60)
      ),
      rest(),
      workout("Full Body C",
        ex("Front Squat", 3, "8-10"),
        ex("Incline Dumbbell Press", 3, "8-10"),
        ex("Cable Row", 3, "10-12"),
        ex("Calf Raise", 3, "12-15", 60)
      ),
      rest()
    )
  )

  private fun broSplit() = PlanProgram(
    name = "Bro Split",
    description = "One muscle group a day, 5 days",
    goal = ProgramGoal.HYPERTROPHY,
    workouts = listOf(
      workout("Chest",
        ex("Barbell Bench Press", 4, "8-10"),
        ex("Incline Dumbbell Press", 4, "10-12"),
        ex("Cable Fly", 3, "12-15", 60),
        ex("Push-up", 3, "AMRAP", 60)
      ),
      workout("Back",
        ex("Deadlift", 3, "5", 180),
        ex("Pull-up", 4, "8-10"),
        ex("Barbell Row", 4, "8-10"),
        ex("Face Pull", 3, "15", 60)
      ),
      workout("Shoulders",
        ex("Overhead Press", 4, "8-10"),
        ex("Lateral Raise", 4, "12-15", 60),
        ex("Rear Delt Fly", 3, "15", 60),
        ex("Shrug", 3, "12-15", 60)
      ),
      workout("Arms",
        ex("Barbell Curl", 4, "10-12", 60),
        ex("Rope Pushdown", 4, "10-12", 60),
        ex("Hammer Curl", 3, "12-15", 60),
        ex("Overhead Tricep Extension", 3, "12-15", 60)
      ),
      workout("Legs",
        ex("Back Squat", 4, "8-10", 180),
        ex("Romanian Deadlift", 3, "10-12"),
        ex("Leg Press", 3, "12-15"),
        ex("Calf Raise", 4, "15", 60)
      ),
      rest()
    )
  )

  private fun fiveByFive() = PlanProgram(
    name = "5×5 Strength",
    description = "Three lifts a session, linear progression",
    goal = ProgramGoal.STRENGTH,
    progression = ProgressionSettings(
      method = ProgressionMethod.LINEAR_LOAD, repRangeLow = 5, repRangeHigh = 5,
      loadIncrement = 2.5f, requiredSuccessfulSets = 5
    ),
    workouts = listOf(
      workout("Workout A",
        ramped("Back Squat", 5, "5", 180),
        ex("Barbell Bench Press", 5, "5", 180),
        ex("Barbell Row", 5, "5", 180)
      ),
      rest(),
      workout("Workout B",
        ramped("Back Squat", 5, "5", 180),
        ex("Overhead Press", 5, "5", 180),
        ex("Deadlift", 1, "5", 180)
      ),
      rest()
    )
  )

  private fun beginnerThreeDay() = PlanProgram(
    name = "Beginner 3-Day",
    description = "Simple, low volume, easy to recover from",
    goal = ProgramGoal.GENERAL,
    experience = ExperienceLevel.BEGINNER,
    workouts = listOf(
      workout("Day 1",
        ex("Goblet Squat", 3, "10-12"),
        ex("Dumbbell Bench Press", 3, "10-12"),
        ex("Lat Pulldown", 3, "10-12"),
        ex("Plank", 3, "30s", 60)
      ),
      rest(),
      workout("Day 2",
        ex("Romanian Deadlift", 3, "10-12"),
        ex("Dumbbell Shoulder Press", 3, "10-12"),
        ex("Cable Row", 3, "10-12"),
        ex("Bicycle Crunch", 3, "15", 60)
      ),
      rest(),
      workout("Day 3",
        ex("Leg Press", 3, "12-15"),
        ex("Push-up", 3, "AMRAP", 60),
        ex("Face Pull", 3, "15", 60),
        ex("Calf Raise", 3, "15", 60)
      ),
      rest()
    )
  )

  private fun homeWorkout() = PlanProgram(
    name = "Home Workout",
    description = "Bodyweight only, no equipment",
    goal = ProgramGoal.GENERAL,
    workouts = listOf(
      workout("Home A",
        ex("Push-up", 4, "AMRAP", 60),
        ex("Bodyweight Squat", 4, "15-20", 60),
        ex("Plank", 3, "45s", 60),
        ex("Glute Bridge", 3, "15-20", 60)
      ),
      rest(),
      workout("Home B",
        ex("Pull-up", 4, "AMRAP", 90),
        ex("Bulgarian Split Squat", 3, "12-15"),
        ex("Dip", 3, "AMRAP", 90),
        ex("Hanging Leg Raise", 3, "12-15", 60)
      ),
      rest()
    )
  )
}
