package com.wildodds.gymtracker.data.importer.segment

import com.wildodds.gymtracker.data.importer.ConfidenceBreakdown
import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.ProgramStructure
import com.wildodds.gymtracker.data.importer.SessionDraft
import com.wildodds.gymtracker.data.importer.SessionType
import com.wildodds.gymtracker.data.importer.SetNotationParser
import com.wildodds.gymtracker.data.importer.SlotDraft
import com.wildodds.gymtracker.data.importer.WeekDraft
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary

/**
 * Builds a [ProgramImportDraft] from the labelled lines.
 *
 * The labelling is [Segmenter]'s job; this turns it into structure, which means resolving the things
 * a label alone does not say: which week a session belongs to when the header said "Weeks 1-4", what
 * day number a weekday implies, whether a week is a deload, and where a section label's exercises
 * end.
 *
 * The rules that survive from the previous importer, because they were right:
 *  - a session named after a weekday takes the DESCRIPTIVE tail as its name ("Monday - Upper Body"
 *    becomes "Upper Body"), keeping the weekday separately as a scheduling hint. Four sessions
 *    called Mon/Tue/Thu/Fri tell the user nothing about what they are training;
 *  - a document title is captured as a name suggestion rather than becoming a phantom first day;
 *  - explanatory prose is dropped rather than becoming an unparseable exercise.
 */
object ProgramAssembler {

  private val vocab = ReaderVocabulary

  fun assemble(name: String, labelled: List<Segmenter.Labelled>, source: String = "pasted text"): ProgramImportDraft {
    val builder = Builder()
    var pendingSection: String? = null

    for (l in labelled) {
      when (l.role) {
        LineRole.IGNORE -> Unit

        LineRole.PROGRAM_TITLE -> builder.title = builder.title.ifBlank { l.features.text }

        LineRole.PHASE_HEADER -> {
          builder.sawHeader = true
          builder.phase = l.features.phaseNumber
          builder.phaseLabel = l.features.text
        }

        LineRole.WEEK_HEADER -> {
          builder.sawHeader = true
          val weeks = l.features.weekNumbers.ifEmpty { listOf(builder.nextWeekNumber()) }
          builder.startWeeks(weeks, isDeload = looksLikeDeload(l.features.text))
          // "W1D3" names a week AND a day in one line.
          LineClassifier.compactDayIn(l.features.text)?.let { d ->
            builder.startSession("Day $d", d, "", SessionType.TRAINING)
          }
        }

        LineRole.SESSION_HEADER -> {
          builder.sawHeader = true
          val (label, weekday) = sessionName(l.features)
          builder.startSession(label, null, weekday, SessionType.TRAINING)
          pendingSection = null
          // "Day 1: Squat 5x5" — a header with an exercise written inline.
          inlineExercise(l.features.text)?.let { builder.addSlot(it, null) }
        }

        LineRole.REST_DAY -> {
          builder.sawHeader = true
          val (label, weekday) = sessionName(l.features)
          val type = if (isActiveRecovery(l.features.text)) SessionType.ACTIVE_RECOVERY else SessionType.REST
          builder.startSession(label.ifBlank { "Rest" }, null, weekday, type)
          pendingSection = null
        }

        LineRole.SECTION_LABEL -> pendingSection = l.features.sectionLabel

        LineRole.EXERCISE -> {
          val slot = SetNotationParser.parseExerciseLine(l.features.text, 0)
          if (slot != null) builder.addSlot(slot, pendingSection, l.confidence)
        }

        LineRole.SET_DETAIL_CONTINUATION -> builder.appendDetail(l.features.text)

        LineRole.NOTE -> builder.addNote(l.features.text)
      }
    }

    return builder.build(name, source)
  }

  // ── naming ───────────────────────────────────────────────────────────────────

  /**
   * The session's display name and its weekday hint.
   *
   * "Monday - Upper Body (Heavy)" becomes "Upper Body (Heavy)" with a Monday hint, because the
   * weekday is scheduling and the tail is what the user is actually training. "Day 1: Upper" becomes
   * "Upper" for the same reason. A header with nothing but a weekday keeps the weekday.
   */
  fun sessionName(f: LineFeatures): Pair<String, String> {
    val text = f.text.trim()
    val weekday = f.weekday.orEmpty()
    val tail = text.substringAfter(':', "").ifBlank { text.substringAfter(" - ", "") }
      .trim().trim('-', '–', ':', '·', ' ')

    // A tail that is itself an exercise is not a name — "Day 1: Squat 5x5" is a day plus an exercise.
    val tailIsExercise = tail.isNotBlank() && SetNotationParser.hasPrescription(tail)

    val base = when {
      tail.isNotBlank() && !tailIsExercise -> tail
      f.dayLabel != null -> "Day ${f.dayLabel}"
      weekday.isNotBlank() -> weekday.replaceFirstChar { it.uppercaseChar() }
      else -> text.trim(':', '-', ' ')
    }
    return base to weekday
  }

  private fun inlineExercise(text: String): SlotDraft? {
    val tail = text.substringAfter(':', "").ifBlank { text.substringAfter(" - ", "") }.trim()
    if (tail.isBlank() || !SetNotationParser.hasPrescription(tail)) return null
    return SetNotationParser.parseExerciseLine(tail, 0)
  }

  private fun looksLikeDeload(text: String) =
    Regex("""(?i)\bde-?load\b""").containsMatchIn(text)

  private fun isActiveRecovery(text: String) =
    Regex("""(?i)\bactive\s+(recovery|rest)\b""").containsMatchIn(text)

  // ── assembly ─────────────────────────────────────────────────────────────────

  private class Builder {
    var title: String = ""
    var phase: Int? = null
    var phaseLabel: String = ""
    /** True once any week / day / phase / rest-day header has been seen. */
    var sawHeader = false
    val notes = ArrayList<String>()

    /** week index -> ordered sessions. LinkedHashMap so week order is the document's order. */
    private val weeks = LinkedHashMap<Int, MutableList<Session>>()
    private val deloadWeeks = HashSet<Int>()
    /** The weeks the current header covers — "Weeks 1-4" writes every session into all four. */
    private var currentWeeks: List<Int> = emptyList()
    private var current: Session? = null
    private var dayCounter = 0

    private class Session(
      val label: String, val dayNumber: Int, val weekday: String, val type: SessionType
    ) {
      val slots = ArrayList<SlotDraft>()
      var notes = ""
    }

    fun nextWeekNumber(): Int = (weeks.keys.maxOrNull() ?: 0) + 1

    fun startWeeks(indices: List<Int>, isDeload: Boolean) {
      currentWeeks = indices
      for (w in indices) {
        weeks.getOrPut(w) { ArrayList() }
        if (isDeload) deloadWeeks += w
      }
      current = null
      dayCounter = 0
    }

    fun startSession(label: String, explicitDay: Int?, weekday: String, type: SessionType) {
      if (currentWeeks.isEmpty()) startWeeks(listOf(1), isDeload = false)
      dayCounter = explicitDay ?: (dayCounter + 1)
      val s = Session(label.ifBlank { "Day $dayCounter" }, dayCounter, weekday, type)
      current = s
      for (w in currentWeeks) weeks.getOrPut(w) { ArrayList() } += s
    }

    fun addSlot(slot: SlotDraft, section: String?, structureConfidence: Double = 1.0) {
      val s = current ?: run { startSession("Day 1", 1, "", SessionType.TRAINING); current!! }
      val order = s.slots.size
      val withSection = if (section.isNullOrBlank()) slot
        else slot.copy(notes = listOf(section.replaceFirstChar { it.uppercaseChar() }, slot.notes)
          .filter { it.isNotBlank() }.joinToString(" · "))
      val breakdown = withSection.breakdown.copy(structure = withSection.breakdown.structure * structureConfidence)
      s.slots += withSection.copy(
        orderIndex = order,
        breakdown = breakdown,
        confidence = minOf(withSection.confidence, breakdown.score)
      )
    }

    /** A continuation line belongs to the exercise above it; keep it verbatim in that slot's notes. */
    fun appendDetail(text: String) {
      val s = current ?: return
      val last = s.slots.lastOrNull() ?: return
      s.slots[s.slots.lastIndex] = last.copy(
        notes = listOf(last.notes, text.trim()).filter { it.isNotBlank() }.joinToString(" · ")
      )
    }

    fun addNote(text: String) {
      val s = current
      if (s == null) notes += text
      else s.notes = listOf(s.notes, text).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun build(name: String, source: String): ProgramImportDraft {
      // A session object is SHARED across the weeks its header covered ("Weeks 1-4"), so each week
      // gets its own copy at build time and nothing aliases.
      val weekDrafts = weeks.entries.sortedBy { it.key }.map { (index, sessions) ->
        WeekDraft(
          index = index,
          isDeload = index in deloadWeeks || sessions.any { it.label.contains("deload", true) },
          sessions = sessions.mapIndexed { i, s ->
            SessionDraft(
              label = s.label,
              dayNumber = if (s.dayNumber > 0) s.dayNumber else i + 1,
              weekdayHint = s.weekday,
              notes = s.notes,
              type = s.type,
              slots = s.slots.toList()
            )
          }
        )
      }.ifEmpty { listOf(WeekDraft(1, sessions = emptyList())) }

      val multiWeek = weekDrafts.size > 1
      return ProgramImportDraft(
        // The document's own title beats a generic placeholder — "4-Week Strength Block" is a
        // better default than "Imported Program", and review can still change it.
        name = name.ifBlank { title.ifBlank { "Imported Program" } },
        source = source,
        weeks = weekDrafts,
        structure = if (multiWeek) ProgramStructure.WEEK_BY_WEEK else ProgramStructure.SINGLE_WEEK_REPEATED,
        repeatWeeks = 1,
        hasExplicitStructure = sawHeader,
        notes = notes.toList()
      )
    }
  }
}
