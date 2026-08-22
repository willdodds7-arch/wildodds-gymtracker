package com.wildodds.gymtracker.data.reader.ingest

/**
 * `.html` / `.htm` / `.mhtml`, and the rich text a browser or email client puts on the clipboard.
 *
 * Two things beyond tag-stripping, both of which decide whether the result is importable:
 *  - `<table>` becomes a [Grid], because a program pasted from a coach's web page or a Google Sheets
 *    "copy" is a table, and flattening it loses which number belongs to which exercise;
 *  - `<br>`, `<p>`, `<li>`, `<tr>`, `<div>` and headings become LINE BREAKS. HTML has no newlines,
 *    so a stripper that only removes tags produces one continuous line and the segmenter has
 *    nothing to work with.
 *
 * `<script>` and `<style>` contents are dropped entirely — they are not text, and leaving them in
 * puts CSS selectors and JavaScript into the exercise list.
 */
object HtmlExtractor : Extractor {

  override val handles = setOf(SourceKind.HTML, SourceKind.MHTML)

  private val BLOCK_TAGS = setOf(
    "p", "br", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "article", "blockquote", "pre", "hr", "table", "thead", "tbody"
  )
  // NOT "head": the document title lives in it, and that title is the best program name a web
  // page offers. Everything inside head that is not text is dropped by name here anyway.
  private val DROP_CONTENT = setOf("script", "style", "noscript", "svg", "template")

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val decoded = TextDecoding.decode(input.bytes, input.limits.maxTextChars)
    val html = if (input.kind == SourceKind.MHTML) unwrapMhtml(decoded.text) else decoded.text
    val parsed = parse(html, input.limits)
    if (parsed.text.isBlank() && parsed.tables.isEmpty()) {
      throw IngestError.Empty("That page has no readable text.")
    }
    return ExtractedDocument(
      text = parsed.text,
      tables = parsed.tables,
      title = parsed.title,
      sourceKind = input.kind
    )
  }

  data class Parsed(val text: String, val tables: List<Grid>, val title: String?)

  fun parse(html: String, limits: IngestLimits = IngestLimits.DEFAULT): Parsed {
    val out = StringBuilder()
    val tables = ArrayList<Grid>()
    var title: String? = null

    var dropDepth = 0
    var inTitle = false
    var tableRows: ArrayList<List<String>>? = null
    var rowCells: ArrayList<String>? = null
    val cell = StringBuilder()
    var inCell = false
    var budget = limits.maxCells

    MiniXml.parse(html) { e ->
      when (e) {
        is MiniXml.Event.Start -> {
          val t = e.name.lowercase()
          when {
            t in DROP_CONTENT -> dropDepth++
            t == "title" -> inTitle = true
            t == "table" -> { tableRows = ArrayList() }
            t == "tr" -> rowCells = ArrayList()
            t == "td" || t == "th" -> { inCell = true; cell.setLength(0) }
            t in BLOCK_TAGS && dropDepth == 0 && !inCell -> out.append('\n')
          }
        }
        is MiniXml.Event.Text -> if (dropDepth == 0) {
          when {
            inTitle -> title = ((title ?: "") + e.value).trim().takeIf { it.isNotBlank() }
            inCell -> cell.append(e.value)
            else -> out.append(e.value)
          }
        }
        is MiniXml.Event.End -> {
          val t = e.name.lowercase()
          when {
            t in DROP_CONTENT -> if (dropDepth > 0) dropDepth--
            t == "title" -> inTitle = false
            t == "td" || t == "th" -> {
              inCell = false
              if (budget-- > 0) rowCells?.add(collapse(cell.toString()))
            }
            t == "tr" -> rowCells?.let { r -> tableRows?.add(r.toList()) }
            t == "table" -> {
              val rows = tableRows
              tableRows = null
              if (rows != null && rows.any { r -> r.any { it.isNotBlank() } }) {
                tables += Grid("table ${tables.size + 1}", rows)
                for (r in rows) if (r.any { it.isNotBlank() }) out.append('\n').append(r.joinToString("\t"))
              }
            }
            t in BLOCK_TAGS && dropDepth == 0 -> out.append('\n')
          }
        }
      }
      budget > 0
    }

    val text = MiniXml.decodeEntities(out.toString())
      .lineSequence().map { collapse(it) }.filter { it.isNotBlank() }.joinToString("\n")
    return Parsed(text, tables, title)
  }

  /** HTML collapses runs of whitespace, including newlines inside a cell, to a single space. */
  private fun collapse(s: String): String =
    s.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()

  /**
   * `.mhtml` is a MIME multipart archive. Take the first `text/html` part and, when it is
   * quoted-printable, undo that — otherwise every `=` in the program becomes `=3D`.
   */
  fun unwrapMhtml(raw: String): String {
    val parts = raw.split(Regex("""(?m)^--\S+\s*$"""))
    val htmlPart = parts.firstOrNull { it.contains("Content-Type: text/html", true) } ?: return raw
    val headerEnd = htmlPart.indexOf("\n\n").takeIf { it >= 0 } ?: return htmlPart
    val header = htmlPart.substring(0, headerEnd)
    val body = htmlPart.substring(headerEnd + 2)
    return if (header.contains("quoted-printable", true)) decodeQuotedPrintable(body) else body
  }

  fun decodeQuotedPrintable(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c != '=') { out.append(c); i++; continue }
      // "=\n" is a soft line break and disappears entirely.
      if (i + 1 < s.length && (s[i + 1] == '\n' || s[i + 1] == '\r')) {
        i += if (s[i + 1] == '\r' && i + 2 < s.length && s[i + 2] == '\n') 3 else 2
        continue
      }
      val hex = s.substring(i + 1, minOf(i + 3, s.length))
      val v = hex.toIntOrNull(16)
      if (v == null) { out.append(c); i++ } else { out.append(v.toChar()); i += 3 }
    }
    return out.toString()
  }
}
