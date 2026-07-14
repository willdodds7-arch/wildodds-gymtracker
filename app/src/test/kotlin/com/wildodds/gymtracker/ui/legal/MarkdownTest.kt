package com.wildodds.gymtracker.ui.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

  @Test
  fun headingsAtEachLevel() {
    val b = Markdown.parse("# One\n\n## Two\n\n### Three")
    assertEquals(listOf(1, 2, 3), b.filterIsInstance<Markdown.Block.Heading>().map { it.level })
  }

  @Test
  fun blankLinesSeparateParagraphs_andWrappedLinesJoin() {
    val b = Markdown.parse("first line\nstill first\n\nsecond para")
    val paras = b.filterIsInstance<Markdown.Block.Paragraph>()
    assertEquals(2, paras.size)
    assertEquals("first line still first", paras[0].spans.joinToString("") { it.text })
    assertEquals("second para", paras[1].spans.joinToString("") { it.text })
  }

  @Test
  fun bulletsBecomeItems() {
    val b = Markdown.parse("- a\n- b\n- c")
    assertEquals(3, b.filterIsInstance<Markdown.Block.BulletItem>().size)
  }

  @Test
  fun boldInline() {
    val spans = Markdown.parseInline("plain **bold** more")
    assertEquals(3, spans.size)
    assertEquals("bold", spans[1].text)
    assertTrue(spans[1].bold)
    assertTrue(!spans[0].bold && !spans[2].bold)
  }

  @Test
  fun markdownLink_andBareAutolink() {
    val link = Markdown.parseInline("see [our page](https://example.com/x) now")
    val l = link.first { it.linkUrl != null }
    assertEquals("our page", l.text)
    assertEquals("https://example.com/x", l.linkUrl)

    val auto = Markdown.parseInline("go to <https://example.com/y>")
    val a = auto.first { it.linkUrl != null }
    assertEquals("https://example.com/y", a.linkUrl)
    assertEquals("https://example.com/y", a.text)
  }

  @Test
  fun blockquoteParsed() {
    val b = Markdown.parse("> a note")
    assertEquals(1, b.filterIsInstance<Markdown.Block.Quote>().size)
  }

  @Test
  fun unterminatedBold_isTreatedAsPlainText() {
    val spans = Markdown.parseInline("a **b c")
    assertEquals("a **b c", spans.joinToString("") { it.text })
    assertTrue(spans.none { it.bold })
  }
}
