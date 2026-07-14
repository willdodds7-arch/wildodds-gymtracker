package com.wildodds.gymtracker.ui.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

@Composable
fun MarkdownBlock(block: Markdown.Block) {
  when (block) {
    is Markdown.Block.Heading -> {
      val size = when (block.level) { 1 -> 24.sp; 2 -> 19.sp; else -> 16.sp }
      val topPad = when (block.level) { 1 -> 8.dp; 2 -> 20.dp; else -> 14.dp }
      Text(
        inlineText(block.spans),
        fontSize = size, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = topPad, bottom = 6.dp)
      )
    }
    is Markdown.Block.Paragraph ->
      LinkableText(block.spans, Modifier.padding(vertical = 6.dp))
    is Markdown.Block.BulletItem ->
      Row(Modifier.padding(vertical = 3.dp, horizontal = 4.dp)) {
        Text("•  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinkableText(block.spans, Modifier)
      }
    is Markdown.Block.Quote ->
      Column(Modifier.padding(vertical = 8.dp)) {
        LinkableText(
          block.spans,
          Modifier.padding(start = 12.dp),
        )
      }
  }
}

/** Bold/plain inline text as an AnnotatedString (no links styled). */
private fun inlineText(spans: List<Markdown.Span>): AnnotatedString = buildAnnotatedString {
  spans.forEach { s ->
    if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) } else append(s.text)
  }
}

/** Renders spans with tappable links (accent + underline), opening them in the browser. */
@Composable
private fun LinkableText(spans: List<Markdown.Span>, modifier: Modifier) {
  val accent = LocalAccentColor.current
  val uriHandler = LocalUriHandler.current
  val body = MaterialTheme.colorScheme.onBackground

  val annotated = buildAnnotatedString {
    spans.forEach { s ->
      when {
        s.linkUrl != null -> {
          pushStringAnnotation("url", s.linkUrl)
          withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(s.text) }
          pop()
        }
        s.bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = body)) { append(s.text) }
        else -> withStyle(SpanStyle(color = body)) { append(s.text) }
      }
    }
  }

  androidx.compose.foundation.text.ClickableText(
    text = annotated,
    style = LocalTextStyle.current.copy(lineHeight = 22.sp),
    modifier = modifier,
    onClick = { offset ->
      annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
        runCatching { uriHandler.openUri(it.item) }
      }
    }
  )
}
