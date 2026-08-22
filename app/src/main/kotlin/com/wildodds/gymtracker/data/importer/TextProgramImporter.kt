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
    // Most pasted programs are markdown from a chat assistant. Flatten headings, bullets, bold and
    // tables first, or every `## Day 1 - Squat` reads as an exercise and the program becomes one
    // nameless day. See [MarkdownNormalizer].
    val lines = MarkdownNormalizer.normalize(raw).lines().map { it.trim() }.filter { it.isNotBlank() }
    val weeks = mutableListOf<WeekAcc>()
    var curWeek: WeekAcc? = null
    var curSess: SessAcc? = null
    var dayCounter = 0
    var slotIdx = 0
    // Text before the first week/day header is the document title and any preamble. Turning it
    // into an exercise invents a phantom "Day 1" that shadows the real first day, so it is captured
    // as a name suggestion and otherwise dropped.
    var seenHeader = false
    var titleLine = ""

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

    /**
     * True when [index] is a title rather than a session header.
     *
     * A document title can easily look like a session ("Upper/Lower Split - 4 Days" starts with the
     * word "upper"), which would create a phantom empty day that shadows the real first one. The
     * tell is what FOLLOWS it: a session header is followed by exercises, a title is followed by
     * another header.
     */
    fun isTitleNotHeader(index: Int): Boolean {
      val next = lines.getOrNull(index + 1) ?: return false
      return weekRe.matchEntire(next) != null ||
        dayRe.matchEntire(next) != null ||
        (splitRe.matchEntire(next) != null && !isExercise(next)) ||
        (weekdayRe.matchEntire(next) != null && !isExercise(next))
    }

    for ((lineIndex, line) in lines.withIndex()) {
      val week = weekRe.matchEntire(line)
      val day = dayRe.matchEntire(line)
      val split = splitRe.matchEntire(line)
      val weekday = weekdayRe.matchEntire(line)
      // A line that is itself a full exercise (has a sets×reps core) must never be swallowed by a
      // session-keyword match — otherwise "Pull Up 3x10" reads as a "Pull" day + exercise "Up".
      // Only the ambiguous keyword headers (split / weekday) need this guard; "Day N"/"Week N" don't.
      val lineIsExercise = isExercise(line)
      val looksLikeHeader = week != null || day != null ||
        (split != null && !lineIsExercise) || (weekday != null && !lineIsExercise)
      // Before any real header, a non-exercise line is the document title/preamble — including one
      // that only LOOKS like a header because it happens to start with a split keyword.
      val isPreamble = !seenHeader && !lineIsExercise &&
        (!looksLikeHeader || isTitleNotHeader(lineIndex))
      if (looksLikeHeader && !isPreamble) seenHeader = true

      when {
        isPreamble -> { if (titleLine.isBlank()) titleLine = line }
        week != null -> { curWeek = WeekAcc(week.groupValues[1].toInt()).also { weeks.add(it) }; curSess = null; dayCounter = 0 }
        day != null -> {
          // "Day 1: Upper" → label "Upper"; "Day 1: Squat 5x5" → label "Day 1" + inline exercise.
          val tail = day.groupValues[2].trim()
          val inline = isExercise(tail)
          startSession(if (tail.isNotBlank() && !inline) tail else "Day ${day.groupValues[1]}", "")
          if (inline) addExercise(tail)
        }
        split != null && !lineIsExercise -> {
          val keyword = split.groupValues[1].trim().replaceFirstChar { it.uppercaseChar() }
          val tail = split.groupValues[2].trim()
          val inline = isExercise(tail)
          // "Upper - Heavy" keeps the qualifier; the keyword alone loses what makes the day distinct.
          startSession(if (tail.isNotBlank() && !inline) "$keyword $tail" else keyword, "")
          if (inline) addExercise(tail)
        }
        weekday != null && !lineIsExercise -> {
          val day = weekday.groupValues[1]
          val tail = weekday.groupValues[2].trim()
          val inline = isExercise(tail)
          // "Monday - Upper Body (Heavy)" becomes "Upper Body (Heavy)", not "Monday": four sessions
          // called Mon/Tue/Thu/Fri tell the user nothing about what they are training. The weekday
          // is kept separately as the scheduling hint.
          startSession(
            if (tail.isNotBlank() && !inline) tail else day.replaceFirstChar { it.uppercaseChar() },
            day
          )
          if (inline) addExercise(tail)
        }
        // Explanatory prose ("Rest 2-3 minutes between working sets.") is dropped rather than
        // becoming an unparseable exercise.
        MarkdownNormalizer.isProse(line, lineIsExercise) -> Unit
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
      // Prefer the pasted document's own title over a generic placeholder — "4-Week Strength Block"
      // is a better default name than "Imported Program", and the user can still edit it in review.
      name = name.ifBlank { titleLine.ifBlank { "Imported Program" } },
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
