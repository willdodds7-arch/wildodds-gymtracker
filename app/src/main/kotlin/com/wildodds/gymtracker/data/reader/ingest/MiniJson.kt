package com.wildodds.gymtracker.data.reader.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON helpers for the ingest layer, over `kotlinx.serialization`'s tree API.
 *
 * The job here is not "parse JSON" — it is "make an UNKNOWN JSON document readable by a pipeline
 * that understands lines and grids". Two transforms do that:
 *
 *  - [tablesIn] finds arrays of uniform objects anywhere in the tree and turns each into a [Grid]
 *    with the union of the keys as the header. That is what makes an export from an app nobody has
 *    written an adapter for still import: the column classifier already knows what an `exercise`
 *    column is.
 *  - [flatten] renders everything else as indented text, so nothing is silently dropped.
 *
 * Key order is preserved throughout ([JsonObject] is backed by a LinkedHashMap), which is what keeps
 * the same bytes producing the same draft every time.
 */
object MiniJson {

  /** Lenient on purpose: a hand-edited export with a trailing comma should still import. */
  val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowTrailingComma = true
  }

  fun parse(text: String): JsonElement? =
    runCatching { json.parseToJsonElement(text) }.getOrNull()

  /** The scalar value of a primitive, without JSON's quotes. Null for objects, arrays and null. */
  fun scalar(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

  /** A likely program title: the first of these keys found anywhere near the top of the tree. */
  private val TITLE_KEYS = listOf("title", "name", "programName", "program_name", "routineName", "label")

  fun titleIn(root: JsonElement): String? {
    val obj = when (root) {
      is JsonObject -> root
      is JsonArray -> root.firstOrNull() as? JsonObject ?: return null
      else -> return null
    }
    for (k in TITLE_KEYS) scalar(obj[k])?.takeIf { it.isNotBlank() }?.let { return it }
    // One level down: exports commonly wrap the program in {"program": {...}} or {"data": {...}}.
    for ((_, v) in obj) {
      if (v is JsonObject) for (k in TITLE_KEYS) scalar(v[k])?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
  }

  /**
   * Every array of ≥2 objects in the tree, as a [Grid]. Nested objects and arrays inside a row are
   * rendered as compact JSON rather than dropped — an unreadable cell is still evidence, and the
   * anti-invention rule means losing it would be worse than showing it.
   */
  fun tablesIn(root: JsonElement, maxCells: Int = Int.MAX_VALUE): List<Grid> {
    val out = ArrayList<Grid>()
    var budget = maxCells
    fun walk(name: String, e: JsonElement, depth: Int) {
      if (depth > 12 || budget <= 0) return
      when (e) {
        is JsonArray -> {
          val objects = e.filterIsInstance<JsonObject>()
          if (objects.size >= 2 && objects.size == e.size) {
            val header = objects.flatMap { it.keys }.distinct()
            if (header.isNotEmpty()) {
              val rows = objects.map { o -> header.map { cellText(o[it]) } }
              budget -= header.size * (rows.size + 1)
              out += Grid(name, listOf(header) + rows)
            }
          }
          // Recurse regardless: a wrapper array can hold the array we actually want.
          e.forEachIndexed { i, child -> walk("$name[$i]", child, depth + 1) }
        }
        is JsonObject -> e.forEach { (k, v) -> walk(k, v, depth + 1) }
        else -> Unit
      }
    }
    walk("root", root, 0)
    return out
  }

  private fun cellText(e: JsonElement?): String = when (e) {
    null, is JsonNull -> ""
    is JsonPrimitive -> e.content
    else -> e.toString()
  }

  /** `key: value` per line, two spaces of indent per level. Arrays of scalars join with ", ". */
  fun flatten(root: JsonElement): String {
    val sb = StringBuilder()
    fun walk(key: String?, e: JsonElement, indent: Int) {
      if (indent > 24) return
      val pad = "  ".repeat(indent)
      when (e) {
        is JsonPrimitive -> sb.append(pad).append(if (key != null) "$key: " else "").append(e.content).append('\n')
        is JsonObject -> {
          if (key != null) sb.append(pad).append(key).append(":\n")
          e.forEach { (k, v) -> walk(k, v, indent + if (key != null) 1 else 0) }
        }
        is JsonArray -> {
          val allScalar = e.all { it is JsonPrimitive }
          if (allScalar) {
            sb.append(pad).append(if (key != null) "$key: " else "")
              .append(e.joinToString(", ") { (it as JsonPrimitive).content }).append('\n')
          } else {
            if (key != null) sb.append(pad).append(key).append(":\n")
            e.forEach { walk(null, it, indent + if (key != null) 1 else 0) }
          }
        }
      }
    }
    walk(null, root, 0)
    return sb.toString()
  }
}
