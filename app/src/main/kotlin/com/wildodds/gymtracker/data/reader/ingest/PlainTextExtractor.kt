package com.wildodds.gymtracker.data.reader.ingest

/**
 * Plain text, markdown, and everything a chat app hands over as "text": a WhatsApp export, an
 * iMessage or Discord paste, an email body, a `.log`.
 *
 * All this does is decode honestly (see [TextDecoding]) and strip the per-message chrome that chat
 * exports wrap every line in — because `[21/03/2026, 19:04] Coach: Bench 3x8` is a program line
 * with a prefix, and leaving the prefix on makes every line unparseable.
 */
object PlainTextExtractor : Extractor {

  override val handles = setOf(SourceKind.PLAIN_TEXT, SourceKind.MARKDOWN)

  /** `[21/03/2026, 19:04] Coach:` and `3/21/26, 7:04 PM - Coach:` — the two WhatsApp export forms. */
  private val WHATSAPP = Regex(
    """^\s*\[?\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4},?\s+\d{1,2}:\d{2}(:\d{2})?\s*([AaPp][Mm])?\]?\s*[-–]?\s*([^:]{1,40}):\s*"""
  )

  /** Discord's `Coach — Today at 19:04` header line, which carries no program content at all. */
  private val DISCORD_HEADER = Regex("""^\s*.{1,40}\s+[-–—]\s+(Today|Yesterday|\d{1,2}/\d{1,2}/\d{2,4})\s+at\s+\d{1,2}:\d{2}""")

  /** Quoted-reply markers from email. `>` alone is stripped by the normaliser; `> > ` is not. */
  private val EMAIL_QUOTE = Regex("""^\s*(>\s?)+""")

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val decoded = TextDecoding.decode(input.bytes, input.limits.maxTextChars)
    if (decoded.text.isBlank()) throw IngestError.Empty("That file has no text in it.")

    val warnings = mutableListOf<IngestWarning>()
    if (decoded.charset != Charsets.UTF_8) {
      warnings += IngestWarning(
        "Read as ${decoded.charset.name()} rather than UTF-8 — check for odd characters.")
    }
    if (input.bytes.size.toLong() > input.limits.maxTextChars) {
      warnings += IngestWarning("Only the first ${input.limits.maxTextChars / 1024} KB were read.")
    }

    var stripped = 0
    val lines = decoded.text.lines().map { line ->
      var l = EMAIL_QUOTE.replace(line, "")
      WHATSAPP.find(l)?.let { m -> l = l.removeRange(m.range); stripped++ }
      l
    }.filterNot { DISCORD_HEADER.containsMatchIn(it) }

    if (stripped > 2) {
      warnings += IngestWarning("Chat timestamps were stripped from $stripped lines.")
    }

    return ExtractedDocument(
      text = lines.joinToString("\n"),
      sourceKind = input.kind,
      confidence = 1.0,
      warnings = warnings
    )
  }
}
