package com.wildodds.gymtracker.data.importer.vocab

import com.wildodds.gymtracker.data.reader.ingest.MiniJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Reader's recognisable-token vocabulary, loaded from `resources/reader/vocabulary.json`.
 *
 * **Rules are data, not code.** The abbreviations, modifier surface forms, section labels, weekday
 * names, unit words, rest phrasings, column-role header hints and app-export signatures all live in
 * a versioned resource, so growing the vocabulary never means touching the grammar — and the SAME
 * vocabulary is read by the text pipeline, the delimited path and the spreadsheet layout engine.
 * One vocabulary, three consumers, which is what stops them drifting apart.
 *
 * It is a **classpath** resource rather than an Android asset on purpose: an asset needs a
 * `Context`, and a `Context` below the extraction boundary would end the purity that makes the
 * corpus a plain JVM test. A classpath resource is packaged into the APK by the same build step and
 * is readable from both.
 *
 * Everything is matched under `Locale.ROOT` (via [lower]) so a Turkish or Azeri locale cannot change
 * how `I` lowercases and silently break every abbreviation on those devices — which is exactly the
 * class of bug the determinism rule exists to prevent.
 */
object ReaderVocabulary {

  /** One canonical modifier and every surface form that means it. */
  data class ModifierEntry(val canonical: String, val patterns: List<String>)

  private val data: JsonObject by lazy { load() }

  val version: Int by lazy { intOf(data["version"]) ?: 0 }

  /** Whole-token abbreviation expansions: `bb` -> `barbell`. */
  val abbreviations: Map<String, String> by lazy {
    (data["abbreviations"] as? JsonObject).orEmptyObject()
      .mapNotNull { (k, v) -> MiniJson.scalar(v)?.let { lower(k) to lower(it) } }
      .toMap()
  }

  val modifiers: List<ModifierEntry> by lazy {
    (data["modifiers"] as? JsonArray).orEmptyArray()
      .filterIsInstance<JsonObject>()
      .mapNotNull { o ->
        val c = MiniJson.scalar(o["canonical"]) ?: return@mapNotNull null
        ModifierEntry(c, strings(o["patterns"]).map(::lower))
      }
  }

  /**
   * Every modifier surface form, **longest first**. Order matters: matching "each side" before
   * "each" is the difference between one modifier and a modifier plus a stray word in the name.
   */
  val modifierPatterns: List<Pair<String, String>> by lazy {
    modifiers.flatMap { m -> m.patterns.map { it to m.canonical } }
      .sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })
  }

  val sectionLabels: Map<String, List<String>> by lazy { stringMap(data["sectionLabels"]) }
  val restDayWords: List<String> by lazy { strings(data["restDayWords"]).map(::lower) }
  val splitKeywords: List<String> by lazy { strings(data["splitKeywords"]).map(::lower) }
  val weekdays: Map<String, List<String>> by lazy { stringMap(data["weekdays"]) }
  val units: Map<String, List<String>> by lazy { stringMap(data["units"]) }
  val restWords: List<String> by lazy { strings(data["restWords"]).map(::lower) }
  val restAsNeeded: List<String> by lazy { strings(data["restAsNeeded"]).map(::lower) }
  val noRestWords: List<String> by lazy { strings(data["noRestWords"]).map(::lower) }
  val amrapWords: List<String> by lazy { strings(data["amrapWords"]).map(::lower) }
  val genericSheetNames: List<String> by lazy { strings(data["genericSheetNames"]).map(::lower) }
  val columnRoleHints: Map<String, List<String>> by lazy { stringMap(data["columnRoleHints"]) }

  /**
   * ISO code -> concept -> words, for the languages the vocabulary knows. Read by
   * [LanguageDetector], which is the only thing that decides whether they apply.
   */
  val languages: Map<String, Map<String, List<String>>> by lazy {
    val obj = (data["languages"] as? JsonObject).orEmptyObject()
    LinkedHashMap<String, Map<String, List<String>>>().apply {
      for ((code, value) in obj) {
        if (code.startsWith("_")) continue          // "_note" is documentation, not a language
        val concepts = stringMap(value)
        if (concepts.isNotEmpty()) put(code, concepts)
      }
    }
  }
  val appExportSignatures: Map<String, List<String>> by lazy { stringMap(data["appExportSignatures"]) }

  /** `monday` for any of "mon", "Montag", "lunes". Null when the token is not a weekday. */
  fun weekdayFor(token: String): String? {
    val t = lower(token).trim('.', ',', ':')
    return weekdays.entries.firstOrNull { (_, forms) -> t in forms }?.key
  }

  /** Which section a heading names ("warmup", "accessory", …), or null. */
  fun sectionFor(line: String): String? {
    val t = lower(line).trim().trim(':', '-', '—', '·', '.', ' ')
    return sectionLabels.entries.firstOrNull { (_, forms) -> forms.any { it == t } }?.key
      // A heading like "Accessory work (optional)" still names its section.
      ?: sectionLabels.entries.firstOrNull { (_, forms) ->
        forms.any { f -> t.startsWith("$f ") || t.startsWith("$f:") }
      }?.key
  }

  /**
   * True when a day label means "no training today".
   *
   * A rest DAY is a short label: "Rest", "Wednesday - REST", "Day off". A rest INSTRUCTION is a
   * sentence with numbers in it: "Rest 2-3 minutes between sets". Both start with the same word,
   * and treating the second as a day would delete a coach's instruction and invent a day off.
   */
  fun isRestDayLabel(label: String): Boolean {
    val t = lower(label).trim().trim(':', '-', '—', '·', '.', ' ')
    if (t.isBlank()) return false
    if (t.any { it.isDigit() } && t.split(' ').size > 2) return false
    if (t.split(' ').size > 4) return false
    return restDayWords.any { it == t || t.startsWith("$it ") || t.endsWith(" $it") }
  }

  /** Expands whole tokens only: `db bench` -> `dumbbell bench`, but `Dbell` is left alone. */
  fun expandAbbreviations(name: String): String =
    name.split(' ').joinToString(" ") { word ->
      val bare = word.trim('.', ',', ')', '(')
      abbreviations[lower(bare)]?.let { word.replace(bare, it, ignoreCase = true) } ?: word
    }

  /** Locale-independent lowercase. Never `String.lowercase()` without a locale in parsing code. */
  fun lower(s: String): String = s.lowercase(java.util.Locale.ROOT)

  // ── loading ──────────────────────────────────────────────────────────────────

  private const val RESOURCE = "/reader/vocabulary.json"

  /**
   * A missing or malformed vocabulary is a build error, not a runtime degradation: silently parsing
   * with an empty vocabulary would produce plausible-looking drafts with every abbreviation and
   * modifier missed, which is far worse than failing where someone will see it.
   */
  private fun load(): JsonObject {
    val stream = ReaderVocabulary::class.java.getResourceAsStream(RESOURCE)
      ?: error("Reader vocabulary missing from the classpath at $RESOURCE")
    val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
    return MiniJson.parse(text) as? JsonObject
      ?: error("Reader vocabulary at $RESOURCE is not a JSON object")
  }

  private fun strings(e: kotlinx.serialization.json.JsonElement?): List<String> =
    (e as? JsonArray).orEmptyArray().filterIsInstance<JsonPrimitive>().map { it.content }

  private fun stringMap(e: kotlinx.serialization.json.JsonElement?): Map<String, List<String>> =
    (e as? JsonObject).orEmptyObject()
      // Keys starting with `_` are documentation inside the data file, not entries.
      .filterKeys { !it.startsWith("_") }
      .mapValues { (_, v) -> strings(v).map(::lower) }

  private fun intOf(e: kotlinx.serialization.json.JsonElement?): Int? =
    MiniJson.scalar(e)?.toIntOrNull()

  private fun JsonObject?.orEmptyObject(): Map<String, kotlinx.serialization.json.JsonElement> =
    this ?: emptyMap()

  private fun JsonArray?.orEmptyArray(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
}
