package com.wildodds.gymtracker.data.reader.ingest

/**
 * `.json`, `.yaml` / `.yml`, `.xml` — the shapes another app's export arrives in.
 *
 * This extractor does NOT try to understand the schema; that is the job of the app-export adapters
 * in `reader.adapters`, which sniff a known signature and parse it natively. What this does is turn
 * an *unknown* structured document into something the text pipeline can still read, by flattening
 * it into indented `key: value` lines and, where an array of uniform objects is found, into a
 * [Grid] with the union of the keys as the header.
 *
 * That flattening is why a JSON export from an app nobody has written an adapter for still imports:
 * `{"exercise":"Bench","sets":3,"reps":8}` becomes a row under `exercise / sets / reps`, which the
 * column classifier already knows how to read.
 */
object StructuredExtractor : Extractor {

  override val handles = setOf(SourceKind.JSON, SourceKind.YAML, SourceKind.XML)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val decoded = TextDecoding.decode(input.bytes, input.limits.maxTextChars)
    if (decoded.text.isBlank()) throw IngestError.Empty("That file has no content.")

    return when (input.kind) {
      SourceKind.JSON -> fromJson(decoded.text, input.limits)
      SourceKind.YAML -> fromYaml(decoded.text)
      else -> fromXml(decoded.text, input.limits)
    }
  }

  // ── JSON ─────────────────────────────────────────────────────────────────────

  private fun fromJson(text: String, limits: IngestLimits): ExtractedDocument {
    val value = MiniJson.parse(text) ?: throw IngestError.Corrupt("That JSON couldn't be read.")
    val grids = MiniJson.tablesIn(value, limits.maxCells)
    return ExtractedDocument(
      text = MiniJson.flatten(value),
      tables = grids,
      title = MiniJson.titleIn(value),
      sourceKind = SourceKind.JSON,
      // Adapters for known exports need the real JSON, not the flattened rendering of it.
      raw = text
    )
  }

  // ── YAML ─────────────────────────────────────────────────────────────────────

  /**
   * A deliberately shallow YAML reader: indentation, `key: value`, and `- item`. That covers every
   * hand-written program YAML seen in the wild, and anything more exotic still flattens to readable
   * lines rather than failing. Anchors, multi-document streams and block scalars are not resolved —
   * the text simply passes through, which is the honest outcome.
   */
  private fun fromYaml(text: String): ExtractedDocument {
    val out = StringBuilder()
    for (raw in text.lines()) {
      val line = raw.trimEnd()
      if (line.isBlank() || line.trimStart().startsWith("#") || line.trim() == "---") continue
      val trimmed = line.trimStart()
      val body = if (trimmed.startsWith("- ")) trimmed.removePrefix("- ") else trimmed
      // `key:` with nothing after it is a heading; `key: value` is a fact. Both read fine as text.
      out.append(body.removeSurrounding("\"")).append('\n')
    }
    val flat = out.toString()
    if (flat.isBlank()) throw IngestError.Empty("That YAML has no content.")
    return ExtractedDocument(text = flat, sourceKind = SourceKind.YAML, raw = text)
  }

  // ── XML ──────────────────────────────────────────────────────────────────────

  /**
   * Repeated sibling elements with the same name and the same child-element names are a table —
   * that is what an XML export of a program looks like. Everything else flattens to text.
   */
  private fun fromXml(text: String, limits: IngestLimits): ExtractedDocument {
    val lines = StringBuilder()
    // element name -> list of (childName -> value) maps
    val groups = LinkedHashMap<String, MutableList<LinkedHashMap<String, String>>>()
    val stack = ArrayList<String>()
    var current: LinkedHashMap<String, String>? = null
    var currentOwner: String? = null
    val buf = StringBuilder()
    var budget = limits.maxCells

    MiniXml.parse(text) { e ->
      when (e) {
        is MiniXml.Event.Start -> {
          stack.add(e.name)
          buf.setLength(0)
          // Depth 2+ elements that carry attributes or children are candidate rows.
          if (current == null && stack.size >= 2) {
            current = LinkedHashMap()
            currentOwner = e.name
            e.attrs.forEach { (k, v) -> current!![k] = v }
          } else {
            current?.let { c -> e.attrs.forEach { (k, v) -> c["${e.name}.$k"] = v } }
          }
        }
        is MiniXml.Event.Text -> buf.append(e.value)
        is MiniXml.Event.End -> {
          val v = buf.toString().trim()
          if (v.isNotEmpty()) {
            lines.append(v).append('\n')
            if (e.name != currentOwner) current?.put(e.name, v)
          }
          buf.setLength(0)
          if (e.name == currentOwner) {
            val c = current
            if (c != null && c.isNotEmpty() && budget > 0) {
              groups.getOrPut(e.name) { ArrayList() }.add(c)
              budget -= c.size
            }
            current = null; currentOwner = null
          }
          if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
      }
      budget > 0
    }

    val tables = groups.filter { it.value.size >= 2 }.map { (name, rows) ->
      val header = rows.flatMap { it.keys }.distinct()
      Grid(name, listOf(header) + rows.map { r -> header.map { r[it].orEmpty() } })
    }
    val flat = lines.toString()
    if (flat.isBlank() && tables.isEmpty()) throw IngestError.Empty("That XML has no content.")
    return ExtractedDocument(text = flat, tables = tables, sourceKind = SourceKind.XML)
  }
}
