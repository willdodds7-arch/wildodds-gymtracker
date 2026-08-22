package com.wildodds.gymtracker.data.importer

/**
 * Deterministic importer for pasted plain-text programs (Reddit copy-pasta, notes-app dumps).
 * Recognises `Week N` and session headers (`Day N`, `Upper/Lower/Push/Pull/Legs`, weekdays); every
 * other non-blank line is an exercise parsed by [SetNotationParser]. No LLM needed for clean text.
 *
 * It does not invent weeks it wasn't given: a single written week stays one week
 * ([ProgramStructure.SINGLE_WEEK_REPEATED], `repeatWeeks = 1`) and the review step decides length.
 */
object TextProgramImporter {

  private val weekRe    = Regex("""(?i)^week\s*(\d+)\b(.*)$""")
  private val dayRe      = Regex("""(?i)^day\s*(\d+)\b[:.\-\s]*(.*)$""")
  private val splitRe    = Regex("""(?i)^(upper(?:\s*\w+)?|lower(?:\s*\w+)?|push|pull|legs|full\s*body|rest\s*day)\b[:.\-\s]*(.*)$""")
  private val weekdayRe  = Regex("""(?i)^(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b[:.\-\s]*(.*)$""")

  private class SessAcc(val label: String, val day: Int, val weekday: String, val slots: MutableList<SlotDraft> = mutableListOf())
  private class WeekAcc(val index: Int, val sessions: MutableList<SessAcc> = mutableListOf())

  fun import(name: String, raw: String): ProgramImportDraft {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val weeks = mutableListOf<WeekAcc>()
    var curWeek: WeekAcc? = null
    var curSess: SessAcc? = null
    var dayCounter = 0
    var slotIdx = 0

    fun ensureWeek(): WeekAcc = curWeek ?: WeekAcc(1).also { curWeek = it; weeks.add(it) }
    fun startSession(label: String, weekday: String) {
      dayCounter++
      curSess = SessAcc(label.ifBlank { "Day $dayCounter" }, dayCounter, weekday)
      ensureWeek().sessions.add(curSess!!)
      slotIdx = 0
    }
    fun ensureSession(): SessAcc = curSess ?: run { startSession("Day 1", ""); curSess!! }
    fun addExercise(text: String) {
      SetNotationParser.parseExerciseLine(text, slotIdx)?.let {
        ensureSession().slots.add(it.copy(orderIndex = slotIdx++))
      }
    }

    for (line in lines) {
      val week = weekRe.matchEntire(line)
      val day = dayRe.matchEntire(line)
      val split = splitRe.matchEntire(line)
      val weekday = weekdayRe.matchEntire(line)
      // A line that is itself a full exercise (has a sets×reps core) must never be swallowed by a
      // session-keyword match — otherwise "Pull Up 3x10" reads as a "Pull" day + exercise "Up".
      // Only the ambiguous keyword headers (split / weekday) need this guard; "Day N"/"Week N" don't.
      val lineIsExercise = isExercise(line)
      when {
        week != null -> { curWeek = WeekAcc(week.groupValues[1].toInt()).also { weeks.add(it) }; curSess = null; dayCounter = 0 }
        day != null -> {
          // "Day 1: Upper" → label "Upper"; "Day 1: Squat 5x5" → label "Day 1" + inline exercise.
          val tail = day.groupValues[2].trim()
          val inline = isExercise(tail)
          startSession(if (tail.isNotBlank() && !inline) tail else "Day ${day.groupValues[1]}", "")
          if (inline) addExercise(tail)
        }
        split != null && !lineIsExercise -> {
          startSession(split.groupValues[1].trim().replaceFirstChar { it.uppercaseChar() }, "")
          split.groupValues[2].trim().let { if (isExercise(it)) addExercise(it) }
        }
        weekday != null && !lineIsExercise -> {
          startSession(weekday.groupValues[1].replaceFirstChar { it.uppercaseChar() }, weekday.groupValues[1])
          weekday.groupValues[2].trim().let { if (isExercise(it)) addExercise(it) }
        }
        else -> addExercise(line)
      }
    }

    val weekDrafts = weeks.map { w ->
      WeekDraft(
        index = w.index,
        isDeload = w.sessions.any { it.label.contains("deload", true) },
        sessions = w.sessions.map { s ->
          SessionDraft(label = s.label, dayNumber = s.day, weekdayHint = s.weekday, slots = s.slots)
        }
      )
    }.ifEmpty { listOf(WeekDraft(1, sessions = emptyList())) }

    val multiWeek = weekDrafts.size > 1
    return ProgramImportDraft(
      name = name.ifBlank { "Imported Program" },
      source = "pasted text",
      weeks = weekDrafts,
      structure = if (multiWeek) ProgramStructure.WEEK_BY_WEEK else ProgramStructure.SINGLE_WEEK_REPEATED,
      repeatWeeks = 1
    )
  }

  /** True when a header tail is clearly an exercise (has a number and a recognised sets/reps core). */
  private fun isExercise(t: String): Boolean =
    t.isNotBlank() && t.any { it.isDigit() } &&
      (SetNotationParser.parseExerciseLine(t, 0)?.sets?.firstOrNull()?.kind ?: SetKind.UNKNOWN) != SetKind.UNKNOWN
}
