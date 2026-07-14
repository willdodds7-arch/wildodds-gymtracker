package com.wildodds.gymtracker.ui.legal

/**
 * A deliberately small Markdown parser for the legal docs WE author — headings (#/##/###),
 * paragraphs, bullet lists (- ), a blockquote (>), plus inline **bold** and [text](url) links.
 * Not a general Markdown engine; it only needs to handle the subset our own documents use, and
 * being a pure function it's fully unit-testable. Adding syntax to a legal doc means extending
 * this + its tests.
 */
object Markdown {

  sealed class Block {
    data class Heading(val level: Int, val spans: List<Span>) : Block()
    data class Paragraph(val spans: List<Span>) : Block()
    data class BulletItem(val spans: List<Span>) : Block()
    data class Quote(val spans: List<Span>) : Block()
  }

  data class Span(val text: String, val bold: Boolean = false, val linkUrl: String? = null)

  fun parse(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
      if (paragraph.isNotBlank()) blocks += Block.Paragraph(parseInline(paragraph.trim().toString()))
      paragraph.setLength(0)
    }

    for (raw in markdown.replace("\r\n", "\n").split("\n")) {
      val line = raw.trimEnd()
      when {
        line.isBlank() -> flushParagraph()
        line.startsWith("### ") -> { flushParagraph(); blocks += Block.Heading(3, parseInline(line.removePrefix("### "))) }
        line.startsWith("## ") -> { flushParagraph(); blocks += Block.Heading(2, parseInline(line.removePrefix("## "))) }
        line.startsWith("# ") -> { flushParagraph(); blocks += Block.Heading(1, parseInline(line.removePrefix("# "))) }
        line.startsWith("- ") -> { flushParagraph(); blocks += Block.BulletItem(parseInline(line.removePrefix("- "))) }
        line.startsWith("> ") -> { flushParagraph(); blocks += Block.Quote(parseInline(line.removePrefix("> "))) }
        else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(line.trim()) }
      }
    }
    flushParagraph()
    return blocks
  }

  /** Inline pass: **bold** and [text](url) (and bare <url> autolinks). Non-nesting, left-to-right. */
  internal fun parseInline(text: String): List<Span> {
    val spans = mutableListOf<Span>()
    var i = 0
    val plain = StringBuilder()
    fun flushPlain() { if (plain.isNotEmpty()) { spans += Span(plain.toString()); plain.setLength(0) } }

    while (i < text.length) {
      when {
        text.startsWith("**", i) -> {
          val end = text.indexOf("**", i + 2)
          if (end > i + 1) {
            flushPlain(); spans += Span(text.substring(i + 2, end), bold = true); i = end + 2
          } else { plain.append(text[i]); i++ }
        }
        text[i] == '[' -> {
          val close = text.indexOf(']', i)
          if (close > i && close + 1 < text.length && text[close + 1] == '(') {
            val urlEnd = text.indexOf(')', close + 2)
            if (urlEnd > close) {
              flushPlain()
              spans += Span(text.substring(i + 1, close), linkUrl = text.substring(close + 2, urlEnd))
              i = urlEnd + 1; continue
            }
          }
          plain.append(text[i]); i++
        }
        text[i] == '<' && text.indexOf('>', i) > i && text.substring(i + 1, text.indexOf('>', i)).startsWith("http") -> {
          val end = text.indexOf('>', i)
          val url = text.substring(i + 1, end)
          flushPlain(); spans += Span(url, linkUrl = url); i = end + 1
        }
        else -> { plain.append(text[i]); i++ }
      }
    }
    flushPlain()
    return spans
  }
}
