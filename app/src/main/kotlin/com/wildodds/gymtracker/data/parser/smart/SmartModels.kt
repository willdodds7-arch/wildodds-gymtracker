package com.wildodds.gymtracker.data.parser.smart

// ─── Rich intermediate models ─────────────────────────────────────────────────
// These are used internally by SmartXlsxParser and shown in the preview screen.
// They are converted to the simpler ParsedProgram/ParsedExercise before DB write.

enum class WeightUnit { KG, LBS, UNKNOWN }

enum class Layout { VERTICAL, HORIZONTAL, HYBRID, UNKNOWN }

data class ParseWarning(
  val severity: Severity,
  val message: String,
  val rawValue: String? = null
) {
  enum class Severity { INFO, WARNING, ERROR }
}

data class SmartParsedExercise(
  val name: String,           // cleaned / expanded
  val nameOriginal: String,   // exactly as in spreadsheet
  val sets: Int,
  val reps: String,           // "8" | "8-10" | "AMRAP"
  val weight: Float?,
  val weightUnit: WeightUnit,
  val rpeTarget: String?,     // prescribed RPE from spreadsheet column (e.g. "8", "7-8")
  val pct1rmTarget: String?,  // prescribed %1RM from spreadsheet column (e.g. "75%", "70-80%")
  val notes: String?,
  val restSeconds: Int?,
  val supersetGroup: String?,  // "A" | "B" etc., or null
  val orderIndex: Int
)

data class SmartParsedDay(
  val name: String,
  val dayNumber: Int,
  val exercises: List<SmartParsedExercise>,
  val muscleGroups: String = ""
)

data class SmartParsedProgram(
  val name: String,
  val weeksDetected: Int,
  val weekTemplate: List<SmartParsedDay>,
  val warnings: List<ParseWarning>,
  val rawSheetNames: List<String>,
  val layoutDetected: Layout,
  val weekDetectionMethod: String  // human-readable description
)
