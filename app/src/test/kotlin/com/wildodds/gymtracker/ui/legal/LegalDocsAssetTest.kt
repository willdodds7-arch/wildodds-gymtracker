package com.wildodds.gymtracker.ui.legal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.ui.settings.SettingControl
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 6 gate: every bundled legal doc renders OFFLINE (loads from assets + parses to real
 * content) and is reachable via Settings search. Also guards Rule 8 (honesty): the docs must not
 * claim the app doesn't collect data / is fully offline / doesn't sync.
 */
@RunWith(RobolectricTestRunner::class)
class LegalDocsAssetTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun read(doc: LegalDoc): String =
    context.assets.open(doc.asset).bufferedReader().use { it.readText() }

  @Test
  fun everyBundledDocLoadsAndParsesToContent() {
    LegalDoc.entries.forEach { doc ->
      val text = read(doc)
      assertTrue("${doc.asset} should be non-trivial", text.length > 100)
      val blocks = Markdown.parse(text)
      assertTrue("${doc.asset} should parse to blocks", blocks.isNotEmpty())
      // First block is the document's H1 title.
      val h1 = blocks.filterIsInstance<Markdown.Block.Heading>().firstOrNull { it.level == 1 }
      assertTrue("${doc.asset} needs an H1", h1 != null)
    }
  }

  @Test
  fun everyLegalDocIsReachableViaSettingsSearch() {
    LegalDoc.entries.forEach { doc ->
      // A term from each doc's title finds its Settings row.
      val term = doc.title.substringBefore(" ").lowercase()
      val hits = SettingsRegistry.search(term)
      assertTrue(
        "expected a Legal & privacy row for '${doc.title}' searching '$term'",
        hits.any { it.control == SettingControl.LEGAL_DOC && it.key == doc.key }
      )
    }
  }

  @Test
  fun settingsRegistryHasALegalRowForEveryDoc() {
    val legalKeys = SettingsRegistry.entries
      .filter { it.control == SettingControl.LEGAL_DOC }.map { it.key }.toSet()
    assertEquals(LegalDoc.entries.map { it.key }.toSet(), legalKeys)
  }

  @Test
  fun docsDoNotMakeDishonestNoCollectionClaims() {
    // Rule 8: no doc may claim it collects nothing at all, or that it is offline WITHOUT naming the
    // one thing that isn't. AI Import posts the text you give it to Anthropic using your own key, so
    // "works fully offline" is honest only when that exception is stated alongside it.
    val alwaysBanned = listOf("no data collection", "no tracking", "does not collect")
    LegalDoc.entries.forEach { doc ->
      val text = read(doc).lowercase()
      alwaysBanned.forEach { phrase ->
        assertFalse("${doc.asset} must not claim '$phrase'", text.contains(phrase))
      }
      if (text.contains("fully offline")) {
        assertTrue(
          "${doc.asset} says 'fully offline' without naming the AI Import exception",
          text.contains("ai import") || text.contains("anthropic")
        )
      }
    }
  }

  @Test
  fun privacyPolicyDescribesTheRealPipeline() {
    // The Supabase-backed Friends layer was removed — the app now ships no backend at all, and the
    // only third party that can ever receive data is Anthropic, via opt-in AI Import with the
    // user's own API key. This asserts the policy describes THAT pipeline, not the retired one.
    val privacy = read(LegalDoc.PRIVACY).lowercase()
    assertTrue("names the only third-party processor", privacy.contains("anthropic"))
    assertTrue("covers deletion", privacy.contains("delete"))
    assertTrue("covers export", privacy.contains("export"))
    assertTrue("13+ statement", privacy.contains("13"))
  }
}
