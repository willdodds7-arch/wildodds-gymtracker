package com.wildodds.gymtracker.data.parser

object TextProgramParser {

  private val dayPattern = Regex("""(?i)\bday\s*(\d+)\b""")
  private val setsRepsPattern = Regex("""(\d+)\s*[xX×]\s*([\d\-\/]+)\s*$""")

  fun parse(name: String, weeks: Int, rawText: String): ParsedProgram {
  // Normalize: split on commas/semicolons/newlines
  val lines = rawText
  .replace(Regex("[,;]+"), "\n")
  .lines()
  .map { it.trim() }
  .filter { it.isNotBlank() }

  // Group lines into days
  data class DayChunk(val dayNumber: Int, val lines: MutableList<String> = mutableListOf())
  val chunks = mutableListOf<DayChunk>()
  var current: DayChunk? = null

  for (line in lines) {
  val dayMatch = dayPattern.find(line)
  if (dayMatch != null) {
  val dayNum = dayMatch.groupValues[1].toInt()
  current = DayChunk(dayNum)
  chunks.add(current)
  // If there's text after the day marker (e.g. "Day 1: Squat 5x5"), include it
  val rest = line.removeRange(dayMatch.range).trim().trimStart(':', '-')
  if (rest.isNotBlank()) current.lines.add(rest)
  } else {
  current?.lines?.add(line)
  }
  }

  // If no day markers at all, treat whole text as Day 1
  if (chunks.isEmpty() && lines.isNotEmpty()) {
  chunks.add(DayChunk(1, lines.toMutableList()))
  }

  // Build sessions template from parsed days
  val sessionsTemplate = chunks.map { chunk ->
  val exercises = chunk.lines.mapIndexedNotNull { idx, line ->
  parseExerciseLine(line, idx)
  }
  Triple(chunk.dayNumber, "Day ${chunk.dayNumber}", exercises)
  }

  // Repeat for each week
  val sessions = (1..weeks).flatMap { week ->
  sessionsTemplate.map { (dayNum, dayName, exercises) ->
  ParsedSession(
  weekNumber  = week,
  dayNumber  = dayNum,
  name  = dayName,
  muscleGroups = "",
  exercises  = exercises
  )
  }
  }

  return ParsedProgram(name = name, totalWeeks = weeks, sessions = sessions)
  }

  private fun parseExerciseLine(line: String, orderIndex: Int): ParsedExercise? {
  val trimmed = line.trim().trimEnd(',', '-', ':')
  if (trimmed.isBlank()) return null

  val match = setsRepsPattern.find(trimmed)
  return if (match != null) {
  val exerciseName = trimmed.substring(0, match.range.first).trim().trimEnd(',', '-')
  if (exerciseName.isBlank()) return null
  val sets = match.groupValues[1].toIntOrNull()?.coerceIn(1, 20) ?: 3
  val reps = match.groupValues[2]
  ParsedExercise(
  name  = exerciseName.replaceFirstChar { it.uppercaseChar() },
  sets  = sets,
  repsTarget = reps,
  notes  = "",
  orderIndex = orderIndex
  )
  } else {
  // No sets/reps pattern - include with sensible defaults
  if (trimmed.isBlank()) return null
  ParsedExercise(
  name  = trimmed.replaceFirstChar { it.uppercaseChar() },
  sets  = 3,
  repsTarget = "8-10",
  notes  = "",
  orderIndex = orderIndex
  )
  }
  }
}
