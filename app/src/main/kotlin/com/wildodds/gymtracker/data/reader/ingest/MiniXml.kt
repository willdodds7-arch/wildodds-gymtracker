package com.wildodds.gymtracker.data.reader.ingest

/**
 * A minimal, dependency-free, **pure-Kotlin** streaming XML reader.
 *
 * Every zip-based office format the reader handles (xlsx, ods, odt, docx) is XML inside a zip, and
 * the previous readers each used `android.util.Xml` — which drags the whole extraction layer into
 * Android, forces Robolectric on every spreadsheet test, and makes the corpus runner impossible.
 * This exists so the extraction boundary can be genuinely pure.
 *
 * It deliberately handles only the subset OOXML and ODF actually use:
 *   elements, attributes (single or double quoted), text, CDATA, comments, processing instructions,
 *   self-closing tags, the five predefined entities plus numeric character references.
 * It does NOT do DTDs, external entities, or namespace resolution — namespaces are handled by
 * comparing local names, which is what every consumer here wants anyway.
 *
 * Not doing DTDs or external entities is also a security property: an XXE payload in a user-supplied
 * .docx has nothing to resolve against.
 *
 * Malformed input never throws. It stops early and reports what it read, because a truncated
 * spreadsheet should import the rows it does have rather than nothing at all.
 */
object MiniXml {

  /** One SAX-ish event. [name] is the LOCAL name — `w:tbl` reports as `tbl`. */
  sealed class Event {
    data class Start(val name: String, val qName: String, val attrs: Map<String, String>) : Event()
    data class End(val name: String, val qName: String) : Event()
    data class Text(val value: String) : Event()
  }

  /**
   * Parse [xml] and hand each event to [onEvent]. Returning `false` from [onEvent] stops the parse
   * early — the caller's cancellation and cell-cap hook.
   */
  fun parse(xml: String, onEvent: (Event) -> Boolean) {
    var i = 0
    val n = xml.length
    val sb = StringBuilder()

    fun flushText(): Boolean {
      if (sb.isEmpty()) return true
      val t = sb.toString()
      sb.setLength(0)
      return onEvent(Event.Text(decodeEntities(t)))
    }

    while (i < n) {
      val c = xml[i]
      if (c != '<') { sb.append(c); i++; continue }
      if (!flushText()) return

      // <!-- comment -->  /  <![CDATA[ ... ]]>  /  <!DOCTYPE ...>
      if (xml.startsWith("<!--", i)) {
        val end = xml.indexOf("-->", i + 4)
        i = if (end < 0) n else end + 3
        continue
      }
      if (xml.startsWith("<![CDATA[", i)) {
        val end = xml.indexOf("]]>", i + 9)
        val body = if (end < 0) xml.substring(i + 9) else xml.substring(i + 9, end)
        if (!onEvent(Event.Text(body))) return
        i = if (end < 0) n else end + 3
        continue
      }
      if (xml.startsWith("<!", i) || xml.startsWith("<?", i)) {
        val end = xml.indexOf('>', i)
        i = if (end < 0) n else end + 1
        continue
      }

      // A real tag. Find its end, respecting quoted attribute values.
      var j = i + 1
      var quote = ' '
      while (j < n) {
        val ch = xml[j]
        when {
          quote != ' ' -> if (ch == quote) quote = ' '
          ch == '"' || ch == '\'' -> quote = ch
          ch == '>' -> break
        }
        j++
      }
      if (j >= n) return  // truncated tag — stop, keeping everything read so far
      val body = xml.substring(i + 1, j)
      i = j + 1

      if (body.startsWith("/")) {
        val q = body.drop(1).trim()
        if (!onEvent(Event.End(local(q), q))) return
        continue
      }
      val selfClosing = body.endsWith("/")
      val inner = if (selfClosing) body.dropLast(1) else body
      val qName = inner.takeWhile { !it.isWhitespace() }
      val attrs = parseAttrs(inner.drop(qName.length))
      if (!onEvent(Event.Start(local(qName), qName, attrs))) return
      if (selfClosing && !onEvent(Event.End(local(qName), qName))) return
    }
    flushText()
  }

  /**
   * All text inside [xml], with a separator emitted at each of [breakOn]'s closing tags. The
   * workhorse for "give me this document as lines" (docx paragraphs, odt paragraphs).
   */
  fun textWithBreaks(xml: String, breakOn: Set<String>, separator: String = "\n"): String {
    val out = StringBuilder()
    parse(xml) { e ->
      when (e) {
        is Event.Text -> out.append(e.value)
        is Event.End -> if (e.name in breakOn) out.append(separator)
        else -> Unit
      }
      true
    }
    return out.toString()
  }

  /** `w:tbl` -> `tbl`. Namespace prefixes carry no information any consumer here needs. */
  fun local(qName: String): String = qName.substringAfterLast(':')

  private fun parseAttrs(s: String): Map<String, String> {
    if (s.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < s.length) {
      while (i < s.length && (s[i].isWhitespace() || s[i] == '/')) i++
      if (i >= s.length) break
      val eq = s.indexOf('=', i)
      if (eq < 0) break
      val key = s.substring(i, eq).trim()
      var k = eq + 1
      while (k < s.length && s[k].isWhitespace()) k++
      if (k >= s.length) break
      val q = s[k]
      if (q != '"' && q != '\'') {
        // Unquoted attribute value — not legal XML, but real files contain it.
        val end = s.indexOfFirst(k) { it.isWhitespace() }
        val v = s.substring(k, if (end < 0) s.length else end)
        if (key.isNotEmpty()) out[key] = decodeEntities(v)
        i = if (end < 0) s.length else end
        continue
      }
      val end = s.indexOf(q, k + 1)
      if (end < 0) break
      if (key.isNotEmpty()) out[key] = decodeEntities(s.substring(k + 1, end))
      i = end + 1
    }
    return out
  }

  private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
    for (idx in from until length) if (pred(this[idx])) return idx
    return -1
  }

  /**
   * The five predefined entities plus numeric character references. Anything else is left verbatim:
   * a stray `&` in a coach's notes is far more likely than a custom entity, and mangling it would
   * corrupt the text we are trying to preserve.
   */
  fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c != '&') { out.append(c); i++; continue }
      val semi = s.indexOf(';', i + 1)
      if (semi < 0 || semi - i > 12) { out.append(c); i++; continue }
      when (val ent = s.substring(i + 1, semi)) {
        "amp" -> out.append('&')
        "lt" -> out.append('<')
        "gt" -> out.append('>')
        "quot" -> out.append('"')
        "apos" -> out.append('\'')
        else -> {
          val cp = when {
            ent.startsWith("#x") || ent.startsWith("#X") -> ent.drop(2).toIntOrNull(16)
            ent.startsWith("#") -> ent.drop(1).toIntOrNull()
            else -> null
          }
          if (cp != null && cp in 1..0x10FFFF) out.appendCodePoint(cp) else { out.append(c); i++; continue }
        }
      }
      i = semi + 1
    }
    return out.toString()
  }
}
